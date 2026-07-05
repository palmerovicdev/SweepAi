package dev.sweep.assistant.api.external.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-level owner of the single Node.js sidecar process. Every call to
 * [call] pushes an NDJSON line onto stdin and returns a [Flow] of [BridgeEvent]s
 * that terminates when the daemon writes `done` or `error` for the matching id.
 *
 * The process is warm-started once and reused for the lifetime of the IDE
 * session; consumers never spawn Node themselves. Multiple concurrent calls
 * multiplex over the same stdin/stdout — this is why every request needs a
 * unique numeric id.
 *
 * State machine:
 *   NOT_STARTED → STARTING → READY → (DEAD)
 * The `DEAD` transition cancels every in-flight channel and allows the next
 * `ensureRunning` to resurrect the process.
 */
@Service(Service.Level.APP)
class NodeBridgeClient : Disposable {
    private val logger = Logger.getInstance(NodeBridgeClient::class.java)

    @Volatile private var process: Process? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var stderrThread: Thread? = null

    private val nextId = AtomicLong(1)
    private val inflight = ConcurrentHashMap<Long, Channel<BridgeEvent>>()

    private val startupLock = Any()
    @Volatile private var startupFailure: String? = null

    /** True when the process is alive and has answered `ping` at least once. */
    fun isReady(): Boolean = process?.isAlive == true && startupFailure == null

    /** Reason of the last startup failure, or null on success / not attempted. */
    fun lastError(): String? = startupFailure

    /**
     * Fires the daemon in the background if not already running. Non-blocking;
     * pings the daemon to prove liveness and mark it READY. Safe to call from a
     * project startup activity.
     */
    fun warmStart() {
        if (isReady()) return
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                ensureRunning()
                runBlocking { ping() }
            } catch (e: Throwable) {
                logger.warn("sweep-ai-bridge warm start failed", e)
                startupFailure = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Block-restart of the daemon — useful after a `Restart bridge` action or an npm reinstall. */
    fun restart() {
        synchronized(startupLock) {
            shutdownProcess()
        }
        ensureRunning()
    }

    /**
     * Issue a request and return a Flow of streaming events. The Flow completes
     * normally when the daemon writes `{done:true}` and throws when it writes
     * `{error:...}` or when the process dies.
     */
    fun call(method: String, params: JsonElement? = null): Flow<BridgeEvent.Payload> = channelFlow {
        ensureRunning()
        val id = nextId.getAndIncrement()
        val channel = Channel<BridgeEvent>(Channel.BUFFERED)
        inflight[id] = channel

        val line = buildJsonObject {
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        try {
            writeLine(line.toString())
        } catch (e: Throwable) {
            inflight.remove(id)
            throw BridgeException("Failed to write bridge request `$method`: ${e.message}", e)
        }

        var terminated = false
        try {
            for (event in channel) {
                when (event) {
                    is BridgeEvent.Payload -> send(event)
                    is BridgeEvent.Terminal -> {
                        terminated = true
                        when (val r = event.result) {
                            BridgeCallResult.Done -> return@channelFlow
                            is BridgeCallResult.Failed -> throw BridgeException(r.message, code = r.code)
                        }
                    }
                }
            }
            // Channel closed without terminal → treat as EOF.
            throw BridgeException("Bridge stream ended without terminal for `$method`")
        } finally {
            inflight.remove(id)
            channel.close()
            // If we bail before the daemon told us it was done (typically because
            // the downstream collector was cancelled), tell the daemon to abort
            // its side. Otherwise the SDK keeps streaming and burning tokens.
            if (!terminated) {
                val cancelLine = buildJsonObject {
                    put("id", nextId.getAndIncrement())
                    put("method", "cancel")
                    put("params", buildJsonObject { put("requestId", id) })
                }
                runCatching { writeLine(cancelLine.toString()) }
            }
        }
    }

    /** Convenience — one-shot ping used by tests and the SDK panel. */
    suspend fun ping(): JsonObject {
        var last: JsonObject? = null
        call("ping").collect { payload ->
            if (payload.eventName == "pong") {
                last = payload.data as? JsonObject
            }
        }
        return last ?: JsonObject(emptyMap())
    }

    /** Returns the models exposed by the running OpenCode server. */
    suspend fun listOpencodeModels(cwd: String): List<OpencodeModel> {
        var models = emptyList<OpencodeModel>()
        val params = buildJsonObject { put("cwd", cwd) }
        call("opencode.listModels", params).collect { payload ->
            if (payload.eventName != "models") return@collect
            val data = payload.data as? JsonObject ?: return@collect
            models =
                data["models"]
                    ?.jsonArray
                    ?.mapNotNull { item ->
                        val value = item.jsonObject
                        val providerId = value["providerId"]?.jsonPrimitive?.content.orEmpty()
                        val modelId = value["modelId"]?.jsonPrimitive?.content.orEmpty()
                        if (providerId.isBlank() || modelId.isBlank()) {
                            null
                        } else {
                            OpencodeModel(
                                providerId = providerId,
                                providerName = value["providerName"]?.jsonPrimitive?.content ?: providerId,
                                modelId = modelId,
                                name = value["name"]?.jsonPrimitive?.content ?: modelId,
                            )
                        }
                    }.orEmpty()
        }
        return models
    }

    // ============================================================================================
    // Internals
    // ============================================================================================

    private fun ensureRunning() {
        if (isReady()) return
        synchronized(startupLock) {
            if (isReady()) return
            startupFailure = null
            val settings = SweepSettings.getInstance()
            val nodePath = NodeDetector.detect(settings.aiBridgeNodePath.ifBlank { null })
                ?: throw BridgeException(
                    "Node.js executable not found. Install Node.js 18+ or configure the path in Settings → Sweep AI → Chat Provider.",
                )
            val bridgeDir = NodeDaemonManager.getInstance().ensureExtracted()
            val daemonScript = NodeDaemonManager.getInstance().daemonScript()
            if (!daemonScript.toFile().exists()) {
                throw BridgeException("Bridge daemon.js missing at $daemonScript — plugin resources are corrupted.")
            }
            // First-run: if the SDKs are missing the daemon boots but every
            // send() fails with "Failed to load @openai/codex-sdk". Kick off
            // the install synchronously here so the first user message succeeds
            // without a trip to Settings → Chat Provider.
            try {
                SdkManager.getInstance().ensureInstalledIfMissingBlocking { line ->
                    logger.info("[ai-bridge npm] $line")
                }
            } catch (e: Throwable) {
                logger.warn("Automatic SDK install failed; user must run 'Update all' in Settings", e)
            }

            val pb = ProcessBuilder(nodePath, daemonScript.toAbsolutePath().toString())
                .directory(bridgeDir.toFile())
                .redirectErrorStream(false)
            // Preserve the login shell PATH so `require`d SDK dependencies find their peer binaries.
            try {
                val env = EnvironmentUtil.getEnvironmentMap()
                env["PATH"]?.let { pb.environment()["PATH"] = it }
            } catch (_: Throwable) { /* fall back to inherited env */ }

            val started = try {
                pb.start()
            } catch (e: Throwable) {
                throw BridgeException("Failed to spawn Node.js sidecar: ${e.message}", e)
            }
            process = started
            writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
            readerThread = Thread({ readLoop(started) }, "sweep-ai-bridge-reader").apply { isDaemon = true; start() }
            stderrThread = Thread({ drainStderr(started) }, "sweep-ai-bridge-stderr").apply { isDaemon = true; start() }
            logger.info("sweep-ai-bridge spawned (pid=${started.pid()}) via $nodePath")
        }
    }

    private fun readLoop(p: Process) {
        BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    dispatchLine(line)
                }
            } catch (e: Throwable) {
                logger.warn("sweep-ai-bridge read loop failed", e)
            } finally {
                onProcessTerminated()
            }
        }
    }

    private fun drainStderr(p: Process) {
        BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).use { reader ->
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    logger.info("[ai-bridge] $line")
                }
            } catch (_: Throwable) { /* process shutting down */ }
        }
    }

    private fun dispatchLine(line: String) {
        val obj = try {
            defaultJson.parseToJsonElement(line) as? JsonObject ?: return
        } catch (_: Throwable) {
            logger.warn("Malformed bridge line dropped: ${line.take(200)}")
            return
        }
        val id = (obj["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id == null) {
            // Broadcast diagnostic; nothing to correlate.
            return
        }
        val event = classifyBridgeLine(obj)
        val channel = inflight[id] ?: return
        val ok = channel.trySend(event).isSuccess
        if (!ok) {
            logger.warn("Bridge channel for id=$id is full; dropping event")
        }
    }

    private fun onProcessTerminated() {
        val err = BridgeException("Node.js sidecar exited unexpectedly")
        // Fan out cancellation to every in-flight caller.
        inflight.forEach { (_, ch) ->
            ch.trySend(BridgeEvent.Terminal(BridgeCallResult.Failed(err.message ?: "process died")))
            ch.close()
        }
        inflight.clear()
        process?.let { logger.info("sweep-ai-bridge exit code=${runCatching { it.exitValue() }.getOrNull()}") }
        process = null
        writer = null
    }

    private fun writeLine(payload: String) {
        val w = writer ?: throw BridgeException("Bridge writer is not initialized")
        try {
            synchronized(w) {
                w.write(payload)
                w.write("\n")
                w.flush()
            }
        } catch (e: java.io.IOException) {
            // The daemon died between the writer reference we captured and the
            // actual write; surface as a bridge-level failure so callers can
            // retry / restart instead of leaking a raw IOException.
            throw BridgeException("Bridge stdin write failed (daemon likely exited): ${e.message}", e)
        }
    }

    private fun shutdownProcess() {
        try {
            val w = writer
            if (w != null) {
                val shutdownLine = buildJsonObject {
                    put("id", nextId.getAndIncrement())
                    put("method", "shutdown")
                }
                try {
                    writeLine(shutdownLine.toString())
                } catch (_: Throwable) { /* best effort */ }
            }
        } finally {
            process?.destroy()
            process = null
            writer = null
            inflight.forEach { (_, ch) -> ch.close() }
            inflight.clear()
        }
    }

    override fun dispose() {
        shutdownProcess()
    }

    companion object {
        fun getInstance(): NodeBridgeClient =
            ApplicationManager.getApplication().getService(NodeBridgeClient::class.java)
    }
}

data class OpencodeModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String,
) {
    val id: String = "$providerId/$modelId"
}

/** Terminal / transport error surfaced by [NodeBridgeClient.call]. */
class BridgeException(
    message: String,
    cause: Throwable? = null,
    val code: Int? = null,
) : RuntimeException(message, cause)
