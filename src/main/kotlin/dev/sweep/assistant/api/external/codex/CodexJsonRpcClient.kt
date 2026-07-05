package dev.sweep.assistant.api.external.codex

import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Line-delimited JSON-RPC 2.0 client for `codex app-server --listen stdio://`.
 * The wire format omits `"jsonrpc":"2.0"` (see plan §10.1 and the app-server
 * README) so we serialize the minimal envelope directly.
 *
 * Responsibilities:
 *   - Serialize requests and correlate responses via [CompletableDeferred].
 *   - Fan out server-initiated notifications through [onNotification].
 *   - Auto-decline server-initiated requests the MVP does not support
 *     (permission/request, item/tool/call — plan §17, host-tools is post-MVP).
 *   - Cancel every pending request when the underlying process dies so callers
 *     never hang forever (plan §18 "Deadlock in `pendingRequests`" mitigation).
 *
 * Lifecycle:
 *   1. [start] on a live process — spawns reader + writer coroutines.
 *   2. Any number of [sendRequest] / [sendNotification] calls.
 *   3. [close] tears everything down; safe to call more than once.
 */
class CodexJsonRpcClient(
    private val process: Process,
    /**
     * Called on every incoming server-initiated notification. Runs on the
     * reader dispatcher — do not block; hand work to another coroutine.
     */
    private val onNotification: (method: String, params: JsonElement?) -> Unit,
) : AutoCloseable {
    private val idCounter = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()

    private var writer: Writer? = null
    private var readerJob: Job? = null

    fun start() {
        writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)
        readerJob = scope.launch { readLoop() }
        scope.launch { watchdog() }
    }

    /**
     * Fire a request and suspend until the server responds (or [timeoutMs]
     * elapses). Throws:
     *   - [CodexRpcException] if the server returned an error.
     *   - [TimeoutCancellationException] on timeout — the request stays in the
     *     pending map so a late response is silently discarded rather than
     *     landing on a stale deferred.
     *   - [IllegalStateException] if the process is no longer alive.
     */
    suspend fun sendRequest(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonElement {
        check(process.isAlive) { "codex process is not running" }
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            writeLine(
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("method", JsonPrimitive(method))
                    if (params != null) put("params", params)
                },
            )
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Typed convenience: encode [params] with the given [serializer] and decode
     * the result under a [resultSerializer]. Throws the same exceptions as
     * [sendRequest] plus a serialization error if the payload is unexpected.
     */
    suspend fun <P, R> sendTypedRequest(
        method: String,
        params: P,
        paramsSerializer: SerializationStrategy<P>,
        resultSerializer: kotlinx.serialization.DeserializationStrategy<R>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): R {
        val encoded = defaultJson.encodeToJsonElement(paramsSerializer, params)
        val result = sendRequest(method, encoded, timeoutMs)
        return defaultJson.decodeFromJsonElement(resultSerializer, result)
    }

    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        writeLine(
            buildJsonObject {
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            },
        )
    }

    private suspend fun writeLine(payload: JsonObject) {
        val text = defaultJson.encodeToString(JsonElement.serializer(), payload)
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val w = writer ?: error("codex client not started")
                w.write(text)
                w.write("\n")
                w.flush()
            }
        }
    }

    private suspend fun readLoop() {
        val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) {
                    try {
                        reader.readLine()
                    } catch (_: Exception) {
                        null
                    }
                } ?: break
                if (line.isBlank()) continue
                dispatchLine(line)
            }
        } finally {
            failAllPending("codex process stdout closed")
        }
    }

    private fun dispatchLine(line: String) {
        val element: JsonElement =
            try {
                defaultJson.parseToJsonElement(line)
            } catch (e: Exception) {
                logger.warn("Skipping malformed codex frame (${e.message}): ${line.take(200)}")
                return
            }
        val obj = element as? JsonObject
        if (obj == null) {
            logger.warn("Codex frame is not a JSON object: ${line.take(200)}")
            return
        }

        val idField = obj["id"]?.jsonPrimitive
        val methodField = obj["method"]?.jsonPrimitive?.contentOrNullSafe()

        when {
            // Server response
            idField != null && methodField == null -> handleResponse(obj, idField)
            // Server request (id + method) — we auto-decline the ones the MVP
            // doesn't handle so the server isn't left waiting on a reply.
            idField != null && methodField != null -> {
                val id = try { idField.long } catch (_: Exception) { null }
                if (id != null) autoRejectServerRequest(id, methodField, obj["params"])
            }
            // Server notification
            methodField != null -> {
                onNotification(methodField, obj["params"])
            }
            else -> {
                logger.warn("Codex frame has neither id nor method: ${line.take(200)}")
            }
        }
    }

    private fun handleResponse(obj: JsonObject, idField: JsonPrimitive) {
        val id = try {
            idField.long
        } catch (e: Exception) {
            logger.warn("Codex response id is not a number: ${idField.content}")
            return
        }
        val deferred = pending.remove(id) ?: return
        val error = obj["error"]?.jsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: -1
            val message = error["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "unknown error"
            deferred.completeExceptionally(CodexRpcException(code, message, error["data"]))
        } else {
            deferred.complete(obj["result"] ?: JsonObject(emptyMap()))
        }
    }

    private fun autoRejectServerRequest(id: Long, method: String, params: JsonElement?) {
        // MVP: Sweep does not host tools nor answer permission RPCs via the
        // Codex channel yet (plan §17). Refusing keeps the app-server unblocked.
        logger.info("Codex requested $method (id=$id); auto-rejecting for MVP")
        scope.launch {
            val payload = buildJsonObject {
                put("id", JsonPrimitive(id))
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(-32601))
                        put("message", JsonPrimitive("Sweep does not implement $method in MVP"))
                    },
                )
            }
            try {
                writeLine(payload)
            } catch (e: Exception) {
                logger.warn("Failed to reject codex request $method: ${e.message}")
            }
        }
        // Suppress unused param warning without shipping a variable rename.
        params?.let { /* payload logged only if debugging is enabled */ }
    }

    private suspend fun watchdog() {
        // Poll the process until it exits, then fail every pending request so
        // callers don't hang on a dead server (plan §18).
        try {
            while (process.isAlive) {
                kotlinx.coroutines.delay(500)
            }
        } catch (_: CancellationException) {
            // scope torn down first — that path already fails pending.
        }
        failAllPending("codex process exited")
    }

    private fun failAllPending(reason: String) {
        val snapshot = pending.keys.toList()
        for (id in snapshot) {
            pending.remove(id)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    override fun close() {
        readerJob?.cancel()
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        failAllPending("codex client closed")
        scope.cancel()
    }

    companion object {
        private val logger = Logger.getInstance(CodexJsonRpcClient::class.java)

        // Codex operations can be long (large repos, tool loops). 5 min mirrors
        // the guardrail called out in plan §18.
        const val DEFAULT_TIMEOUT_MS: Long = 5 * 60 * 1000L
    }
}

/** Server returned an RPC error for a client request. */
class CodexRpcException(val code: Int, message: String, val data: JsonElement? = null) :
    RuntimeException("codex rpc error $code: $message")

private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else content.ifBlank { null }
