package dev.sweep.assistant.api.external.opencode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.api.external.ExternalAgentEvent
import dev.sweep.assistant.api.external.ExternalAgentImage
import dev.sweep.assistant.api.external.ExternalAgentProvider
import dev.sweep.assistant.api.external.ProcessScope
import dev.sweep.assistant.api.external.ProviderHandle
import dev.sweep.assistant.api.external.ResumeResult
import dev.sweep.assistant.api.external.SessionHints
import dev.sweep.assistant.api.external.TestResult
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenCode (`opencode serve`) provider.
 *
 * Lifecycle (see plan §8.3 and §9):
 *  - A single `opencode serve` subprocess is shared across the IDE. Boot on
 *    first [ensureRunning]; skip entirely when `settings.opencodeBaseUrl` is set.
 *  - Per (baseUrl, projectDirectory) we keep an [OpencodeRuntime] holding a Ktor
 *    HTTP client and an eagerly-shared SSE subscription. `sendUserMessage`
 *    fires the POST in a launched coroutine and drives the returned flow purely
 *    from the SSE side, filtered by sessionId.
 *
 * Registered by [ExternalAgentProviderRegistry.registerBuiltIns].
 */
class OpencodeAgentProvider : ExternalAgentProvider {
    override val id: String = "opencode"
    override val displayName: String = "OpenCode"
    override val processScope: ProcessScope = ProcessScope.PER_IDE

    private val processMutex = Mutex()
    private val runtimeMutex = Mutex()

    @Volatile
    private var sharedProcess: OpencodeProcess? = null

    private val runtimes = ConcurrentHashMap<RuntimeKey, OpencodeRuntime>()

    override fun detectExecutable(): String? {
        val settings = SweepSettings.getInstance()
        val requested = settings.opencodeCommand.ifBlank { "opencode" }
        if (requested.contains(File.separatorChar)) {
            val f = File(requested)
            return if (f.isFile && f.canExecute()) f.absolutePath else null
        }
        val path = loadPathEnv()
        return OpencodeProcess.resolveExecutableOnPath(requested, path)
            ?: FALLBACK_INSTALL_DIRS
                .map { File(File(it), if (isWindows) "opencode.exe" else "opencode") }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
    }

    override suspend fun ensureRunning(project: Project, settings: SweepSettings): ProviderHandle {
        val process = ensureProcess(settings)
        val baseUrl = (process?.baseUrl ?: settings.opencodeBaseUrl.trim().trimEnd('/'))
            .ifBlank { error("OpenCode baseUrl is not configured and process failed to start") }
        val authHeader = process?.authHeader
        val projectDirectory = resolveProjectDirectory(project)

        val key = RuntimeKey(baseUrl, projectDirectory)
        val runtime = runtimeMutex.withLock {
            runtimes.getOrPut(key) { buildRuntime(baseUrl, authHeader, projectDirectory) }
        }
        return ProviderHandle(runtime)
    }

    override suspend fun close(handle: ProviderHandle) {
        val runtime = handle.opaque as? OpencodeRuntime ?: return
        runtimeMutex.withLock {
            runtimes.entries.removeAll { it.value === runtime }
        }
        runtime.close()
    }

    override suspend fun createSession(handle: ProviderHandle, cwd: String, hints: SessionHints): String {
        val runtime = handle.opaque as OpencodeRuntime
        val session = runtime.http.createSession()
        return session.id
    }

    override suspend fun resumeSession(handle: ProviderHandle, remoteSessionId: String): ResumeResult {
        val runtime = handle.opaque as OpencodeRuntime
        return try {
            val session = runtime.http.getSession(remoteSessionId)
            if (session != null) ResumeResult.Ok(session.id) else ResumeResult.NotFound
        } catch (e: OpencodeHttpException) {
            if (e.statusCode == 404) ResumeResult.NotFound
            else ResumeResult.Failed("HTTP ${e.statusCode}: ${e.body.take(200)}")
        } catch (e: Exception) {
            ResumeResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun sendUserMessage(
        handle: ProviderHandle,
        remoteSessionId: String,
        prompt: String,
        images: List<ExternalAgentImage>,
    ): Flow<ExternalAgentEvent> {
        val runtime = handle.opaque as OpencodeRuntime
        val settings = SweepSettings.getInstance()
        val agent = settings.opencodeAgent.ifBlank { "build" }
        val tracker = PartTextTracker()

        return channelFlow {
            val postJob = launch {
                try {
                    runtime.http.sendMessage(remoteSessionId, prompt, agent, null)
                } catch (e: OpencodeHttpException) {
                    trySend(
                        ExternalAgentEvent.Error(
                            reason = "OpenCode POST failed (HTTP ${e.statusCode}): ${e.body.take(300)}",
                            recoverable = e.statusCode in 500..599,
                        ),
                    )
                    close()
                } catch (e: Exception) {
                    trySend(
                        ExternalAgentEvent.Error(
                            reason = "Failed to send prompt to OpenCode: ${e.message ?: e.javaClass.simpleName}",
                            recoverable = false,
                        ),
                    )
                    close()
                }
            }

            try {
                // The shared flow is hot and never terminates on its own; drive
                // completion off the terminal ExternalAgentEvent instead so we
                // still forward it downstream before ending the collect.
                runtime.sharedEvents
                    .transform<OpencodeEventEnvelope, ExternalAgentEvent> { envelope ->
                        OpencodeProtocolAdapter.translate(envelope, remoteSessionId, tracker)
                            .forEach { emit(it) }
                    }
                    .transformWhile { ev ->
                        emit(ev)
                        !OpencodeProtocolAdapter.isTerminal(ev)
                    }
                    .collect { send(it) }
            } finally {
                postJob.cancel()
            }
        }
    }

    override suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String) {
        val runtime = handle.opaque as OpencodeRuntime
        try {
            runtime.http.abort(remoteSessionId)
        } catch (e: Exception) {
            logger.warn("OpenCode abort failed for session=$remoteSessionId: ${e.message}")
        }
    }

    override suspend fun answerPermission(
        handle: ProviderHandle,
        remoteSessionId: String,
        permissionId: String,
        allow: Boolean,
    ) {
        val runtime = handle.opaque as OpencodeRuntime
        val response = if (allow) "once" else "reject"
        runtime.http.answerPermission(remoteSessionId, permissionId, response)
    }

    override suspend fun testConnection(handle: ProviderHandle): TestResult {
        val runtime = handle.opaque as OpencodeRuntime
        return try {
            val ok = runtime.http.ping()
            if (ok) TestResult.Ok("Reached ${runtime.baseUrl}") else TestResult.Fail("Server at ${runtime.baseUrl} did not respond 2xx")
        } catch (e: Exception) {
            TestResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    // ===== Internals =====

    private suspend fun ensureProcess(settings: SweepSettings): OpencodeProcess? {
        if (settings.opencodeBaseUrl.isNotBlank()) return null
        return processMutex.withLock {
            val current = sharedProcess
            if (current != null && current.isAlive()) return@withLock current
            current?.close()
            val command = settings.opencodeCommand.ifBlank { "opencode" }
            val extraArgs =
                settings.opencodeExtraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
            val fresh = withContext(Dispatchers.IO) {
                OpencodeProcess.start(command = command, extraArgs = extraArgs)
            }
            sharedProcess = fresh
            fresh
        }
    }

    private fun buildRuntime(
        baseUrl: String,
        authHeader: String?,
        projectDirectory: String,
    ): OpencodeRuntime {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val http = OpencodeHttpClient(baseUrl, authHeader, projectDirectory)
        val shared: SharedFlow<OpencodeEventEnvelope> =
            http.eventStream()
                .catch { e -> logger.warn("OpenCode SSE stream error: ${e.message}") }
                .shareIn(scope, SharingStarted.Eagerly, replay = 0)
        return OpencodeRuntime(
            baseUrl = baseUrl,
            projectDirectory = projectDirectory,
            http = http,
            scope = scope,
            sharedEvents = shared,
        )
    }

    private fun resolveProjectDirectory(project: Project): String {
        val base = project.basePath ?: error("Project has no basePath — cannot route OpenCode")
        return try {
            Paths.get(base).toRealPath().toString()
        } catch (_: Exception) {
            File(base).absolutePath
        }
    }

    private fun loadPathEnv(): String? =
        try {
            com.intellij.util.EnvironmentUtil.getEnvironmentMap()["PATH"]
        } catch (_: Throwable) {
            System.getenv("PATH")
        }

    private data class RuntimeKey(val baseUrl: String, val projectDirectory: String)

    companion object {
        private val logger = Logger.getInstance(OpencodeAgentProvider::class.java)

        private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        private val FALLBACK_INSTALL_DIRS: List<String> =
            if (isWindows) {
                listOfNotNull(
                    System.getenv("LOCALAPPDATA")?.let { "$it\\opencode\\bin" },
                    System.getenv("USERPROFILE")?.let { "$it\\.opencode\\bin" },
                )
            } else {
                val home = System.getProperty("user.home") ?: ""
                listOf(
                    "$home/.local/bin",
                    "$home/.cargo/bin",
                    "$home/.bun/bin",
                    "$home/.opencode/bin",
                    "/opt/homebrew/bin",
                    "/usr/local/bin",
                )
            }
    }
}

/**
 * Per-(baseUrl, projectDirectory) state held under the opaque [ProviderHandle].
 * Owns the Ktor client and the eagerly-shared SSE subscription. Torn down by
 * [OpencodeAgentProvider.close] or when the plugin is unloaded.
 */
class OpencodeRuntime(
    val baseUrl: String,
    val projectDirectory: String,
    val http: OpencodeHttpClient,
    val scope: CoroutineScope,
    val sharedEvents: SharedFlow<OpencodeEventEnvelope>,
) : AutoCloseable {
    override fun close() {
        try {
            http.close()
        } catch (_: Exception) {
        }
        try {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        } catch (_: Exception) {
        }
    }
}
