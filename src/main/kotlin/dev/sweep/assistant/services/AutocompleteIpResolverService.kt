package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.utils.CompressionUtils
import dev.sweep.assistant.utils.encodeString
import dev.sweep.assistant.utils.getCurrentSweepPluginVersion
import dev.sweep.assistant.utils.getDebugInfo
import dev.sweep.assistant.utils.defaultJson
import dev.sweep.assistant.utils.raiseForStatus
import dev.sweep.assistant.utils.streamJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.future.await
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Service that periodically resolves the IP address of autocomplete.sweep.dev
 * to keep DNS cache warm while using HTTPS with the domain name directly.
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)

        private const val HOSTNAME = "autocomplete.sweep.dev"
        private const val RESOLUTION_INTERVAL_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 25_000L // Just under 30 seconds
        private const val READ_TIMEOUT_MS = 10_000L
        private const val LOCAL_READ_TIMEOUT_MS = 10_000L
        private const val USER_ACTIVITY_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastLatencyMs = AtomicLong(-1L) // -1 indicates no measurement yet
    private val lastUserActionTimestamp = AtomicLong(System.currentTimeMillis()) // Initialize with current time
    private var resolutionJob: Job? = null
    private var healthCheckJob: Job? = null

    /**
     * Checks if the user is pointed to the cloud version of the plugin.
     * Returns true if either:
     * 1. The user is on the cloud environment (plugin version), OR
     * 2. Their backend URL is pointed to https://backend.app.sweep.dev
     */
    private fun isPointedToCloud(): Boolean =
        SweepSettingsParser.isCloudEnvironment() ||
            SweepSettings.getInstance().baseUrl == "https://backend.app.sweep.dev"

    // HTTP client with connection pooling and keep-alive
    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    /**
     * Gets the shared HttpClient instance for connection pooling.
     * This allows other services to use the same connection pool.
     */
    fun getSharedHttpClient(): HttpClient = httpClient

    /**
     * Executes a next edit autocomplete request.
     * This centralizes the entire HTTP request flow in the DNS resolver service.
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        try {
            val isLocalMode = SweepConfig.getInstance(project).isAutocompleteLocalMode()
            val requestStartTime = System.currentTimeMillis()
            if (isLocalMode) {
                AutocompleteDebugLog.log("fetch: ensuring local server is running")
                LocalAutocompleteServerManager.getInstance().ensureServerRunning()
            }

            val postData = encodeString(request, NextEditAutocompleteRequest.serializer())
            val postDataBytes = postData.toByteArray(Charsets.UTF_8)

            // Try to compress the request data
            val (finalData, useCompression) =
                if (CompressionUtils.isBrotliAvailable()) {
                    val compressedData = CompressionUtils.compress(postDataBytes, CompressionUtils.CompressionType.BROTLI)
                    if (compressedData.size < postDataBytes.size) {
                        val compressionRatio = CompressionUtils.calculateCompressionRatio(postDataBytes.size, compressedData.size)
                        logger.info(
                            "Request compressed: ${postDataBytes.size} -> ${compressedData.size} bytes (${String.format(
                                "%.1f",
                                compressionRatio,
                            )}% reduction)",
                        )
                        Pair(compressedData, true)
                    } else {
                        logger.info("Compression not beneficial, sending uncompressed")
                        Pair(postDataBytes, false)
                    }
                } else {
                    logger.info("Brotli not available, sending uncompressed")
                    Pair(postDataBytes, false)
                }

            val authorization =
                if (SweepSettings.getInstance().githubToken.isBlank()) {
                    "Bearer device_id_${PermanentInstallationID.get()}"
                } else {
                    "Bearer ${SweepSettings.getInstance().githubToken}"
                }

            val baseUrl = getBaseUrl()
            val timeoutMs = if (isLocalMode) LOCAL_READ_TIMEOUT_MS else READ_TIMEOUT_MS
            AutocompleteDebugLog.log(
                "fetch: POST $baseUrl/backend/next_edit_autocomplete " +
                    "local=$isLocalMode timeout_ms=$timeoutMs file=${request.file_path} cursor=${request.cursor_position}",
            )

            val httpRequestBuilder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$baseUrl/backend/next_edit_autocomplete"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization)
                    .header("X-Plugin-Version", getCurrentSweepPluginVersion() ?: "unknown")
                    .header("X-IDE-Name", ApplicationInfo.getInstance().fullApplicationName)
                    .header("X-IDE-Version", ApplicationInfo.getInstance().fullVersion)
                    .header("X-Debug-Info", getDebugInfo())

            if (useCompression) {
                httpRequestBuilder.header("Content-Encoding", CompressionUtils.CompressionType.BROTLI.encoding)
            }

            val httpRequest = httpRequestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(finalData)).build()

            var result: NextEditAutocompleteResponse? = null

            if (isLocalMode) {
                try {
                    result =
                        withTimeout(LOCAL_READ_TIMEOUT_MS) {
                            val response =
                                httpClient
                                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                                    .await()
                                    .raiseForStatus()

                            var localResult: NextEditAutocompleteResponse? = null
                            response.body().lineSequence().forEach { l ->
                                if (l.isBlank()) return@forEach
                                val jsonElement =
                                    try {
                                        defaultJson.parseToJsonElement(l)
                                    } catch (e: Exception) {
                                        logger.warn("Error parsing local server response: ${e.message}")
                                        AutocompleteDebugLog.log("fetch: parse error from local response: ${e.message}; line=${l.take(500)}")
                                        return@forEach
                                    }

                                if (jsonElement is JsonObject && jsonElement.containsKey("status")) {
                                    val status = jsonElement["status"]?.jsonPrimitive?.contentOrNull
                                    if (status == "error") {
                                        val errorMsg = jsonElement["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                        logger.warn("Local autocomplete server error: $errorMsg")
                                        AutocompleteDebugLog.log("fetch: local server error: $errorMsg")
                                        throw LocalAutocompleteServerException(errorMsg)
                                    }
                                }

                                try {
                                    val decoded = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                                    localResult = decoded
                                    AutocompleteDebugLog.log(
                                        "fetch: decoded local response autocomplete_id=${decoded.autocomplete_id} " +
                                            "completions=${decoded.completions.size}",
                                    )
                                } catch (e: Exception) {
                                    logger.warn("Error parsing local server response: ${e.message}")
                                    AutocompleteDebugLog.log("fetch: parse error from local response: ${e.message}; line=${l.take(500)}")
                                }
                            }
                            localResult
                        }
                } catch (e: TimeoutCancellationException) {
                    AutocompleteDebugLog.log(
                        "fetch: local timeout after ${LOCAL_READ_TIMEOUT_MS}ms " +
                            "elapsed_ms=${System.currentTimeMillis() - requestStartTime}",
                    )
                }

                if (result != null) {
                    LocalAutocompleteServerManager.getInstance().reportSuccess()
                } else {
                    LocalAutocompleteServerManager.getInstance().reportFailure()
                }
            } else {
                val response =
                    httpClient
                        .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                        .await()
                        .raiseForStatus()

                response.streamJson<NextEditAutocompleteResponse>().collect {
                    result = it
                }
            }

            AutocompleteDebugLog.log(
                "fetch: completed elapsed_ms=${System.currentTimeMillis() - requestStartTime} " +
                    "result=${result != null} completions=${result?.completions?.size ?: 0}",
            )
            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            AutocompleteDebugLog.log("fetch: exception ${e.javaClass.simpleName}: ${e.message}")
            if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }
            throw e
        }

    private class LocalAutocompleteServerException(
        message: String,
    ) : RuntimeException(message)

    init {
        startPeriodicResolution()
        startPeriodicHealthCheck()
    }

    /**
     * Gets the base URL using the configured backend URL or autocomplete.sweep.dev.
     */
    fun getBaseUrl(): String {
        if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) {
            return LocalAutocompleteServerManager.getInstance().getServerUrl()
        }

        if (!isPointedToCloud()) {
            // Use the configured backend URL when not pointed to cloud
            return SweepSettings.getInstance().baseUrl
        }

        // Always use https://autocomplete.sweep.dev directly, let OS handle DNS caching
        return "https://autocomplete.sweep.dev"
    }

    /**
     * Gets the last measured latency in milliseconds.
     * Returns -1 if no measurement has been taken yet.
     */
    fun getLastLatencyMs(): Long = lastLatencyMs.get()

    /**
     * Updates the timestamp of the last user action.
     * Call this whenever the user performs any action (typing, clicking, etc.).
     */
    fun updateLastUserActionTimestamp() {
        lastUserActionTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Checks if there was user activity within the last 10 minutes.
     */
    private fun hasRecentUserActivity(): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastActivity = lastUserActionTimestamp.get()
        return (currentTime - lastActivity) <= USER_ACTIVITY_TIMEOUT_MS
    }

    private fun startPeriodicResolution() {
        resolutionJob =
            scope.launch {
                // Initial resolution
                resolveIpAddress()

                // Periodic resolution every 15 seconds, but only if user was active in last 10 minutes
                while (isActive) {
                    delay(RESOLUTION_INTERVAL_MS)
                    if (hasRecentUserActivity()) {
                        resolveIpAddress()
                    }
                }
            }
    }

    private fun startPeriodicHealthCheck() {
        healthCheckJob =
            scope.launch {
                // Initial health check
                performHealthCheck()

                // Periodic health check every 25 seconds, but only if user was active in last 10 minutes
                while (isActive) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                    if (hasRecentUserActivity()) {
                        performHealthCheck()
                    }
                }
            }
    }

    private suspend fun resolveIpAddress() {
        if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) return
        if (!isPointedToCloud()) return
        try {
            withContext(Dispatchers.IO) {
                // Just resolve the hostname to keep DNS cache warm
                // We don't use the IP addresses, just let the OS cache them
                InetAddress.getAllByName(HOSTNAME)
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve $HOSTNAME: ${e.message}")
        }
    }

    private suspend fun performHealthCheck() {
        if (SweepConfig.getInstance(project).isAutocompleteLocalMode()) return
        if (!isPointedToCloud()) return
        try {
            withContext(Dispatchers.IO) {
                val baseUrl = getBaseUrl()
                val startTime = System.currentTimeMillis()

                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(baseUrl))
                        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                val endTime = System.currentTimeMillis()
                val latency = endTime - startTime

                if (response.statusCode() in 200..299) {
                    lastLatencyMs.set(latency)
//                    println("AutocompleteIpResolverService: Health check to $baseUrl successful, latency: ${latency}ms")
                } else {
                    logger.warn("Health check to $baseUrl failed with response code: ${response.statusCode()}")
                }
            }
        } catch (e: Exception) {
            logger.warn("Health check failed: ${e.message}")
            // Keep the last latency value on failure
        }
    }

    override fun dispose() {
        resolutionJob?.cancel()
        healthCheckJob?.cancel()
        scope.cancel()
    }
}
