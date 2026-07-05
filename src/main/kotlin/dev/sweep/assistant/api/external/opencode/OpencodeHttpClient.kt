package dev.sweep.assistant.api.external.opencode

import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.utils.defaultJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Thin Ktor-CIO wrapper over the OpenCode `serve` HTTP surface. Uses the
 * session-pinned route tree (`/session/...` + `?directory=`) as documented for
 * SDK 1.17.x. All non-SSE calls follow the plugin's kotlinx-serialization +
 * `defaultJson` convention (unknown fields are tolerated).
 *
 * One instance per active project + runtime; the caller owns the lifecycle and
 * must call [close] when tearing the runtime down.
 */
class OpencodeHttpClient(
    private val baseUrl: String,
    private val authHeader: String?,
    private val directory: String,
) : AutoCloseable {
    private val client =
        HttpClient(CIO) {
            install(SSE)
            engine {
                // 0 = no timeout: SSE and long-running message calls stay open.
                requestTimeout = 0
            }
            defaultRequest {
                authHeader?.let { header("Authorization", it) }
                header("Accept", "application/json")
                header("x-opencode-directory", directory)
            }
        }

    // ===== Health / status =====

    suspend fun ping(): Boolean =
        try {
            // Bound the ping tightly so Test Connection never hangs when the
            // server socket is up but the request handler is stuck.
            val response = kotlinx.coroutines.withTimeout(PING_TIMEOUT_MS) {
                client.get(url("/session"))
            }
            response.status.value in 200..299
        } catch (e: Exception) {
            logger.warn("OpenCode ping failed: ${e.message}")
            false
        }

    // ===== Session lifecycle =====

    suspend fun createSession(): OpencodeSession {
        val response =
            client.post(url("/session")) {
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(
                    defaultJson.encodeToString(
                        OpencodeCreateSessionRequest.serializer(),
                        OpencodeCreateSessionRequest(directory = directory),
                    ),
                )
            }
        response.raiseForStatus("createSession")
        return defaultJson.decodeFromString(OpencodeSession.serializer(), response.bodyAsText())
    }

    suspend fun getSession(sessionId: String): OpencodeSession? {
        val response = client.get(url("/session/$sessionId"))
        if (response.status == HttpStatusCode.NotFound) return null
        response.raiseForStatus("getSession")
        return defaultJson.decodeFromString(OpencodeSession.serializer(), response.bodyAsText())
    }

    // ===== Turn =====

    /**
     * Send a user prompt. Fires the (blocking) `/message` endpoint — the caller
     * drives the UI purely from the SSE stream and treats this call's return
     * value as a backstop for "no more updates coming".
     *
     * Throws on 4xx/5xx from the server so `sendUserMessage` can surface auth /
     * config errors as an [ExternalAgentEvent.Error].
     */
    suspend fun sendMessage(
        sessionId: String,
        prompt: String,
        agent: String?,
        model: OpencodeModelRef?,
    ) {
        val body =
            OpencodePromptRequest(
                parts = listOf(OpencodePromptPart(type = "text", text = prompt)),
                agent = agent?.takeIf { it.isNotBlank() },
                model = model,
            )
        val response =
            client.post(url("/session/$sessionId/message")) {
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(defaultJson.encodeToString(OpencodePromptRequest.serializer(), body))
            }
        response.raiseForStatus("sendMessage")
    }

    suspend fun abort(sessionId: String) {
        val response = client.post(url("/session/$sessionId/abort"))
        // 404 = already ended; not fatal.
        if (response.status != HttpStatusCode.NotFound) response.raiseForStatus("abort")
    }

    suspend fun answerPermission(
        sessionId: String,
        permissionId: String,
        response: String,
    ) {
        val body = OpencodePermissionRequest(response = response)
        val http =
            client.post(url("/session/$sessionId/permissions/$permissionId")) {
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(defaultJson.encodeToString(OpencodePermissionRequest.serializer(), body))
            }
        http.raiseForStatus("answerPermission")
    }

    // ===== Event stream =====

    /**
     * Open a live SSE subscription to `/event` scoped to this runtime's
     * directory. The returned flow completes when the server closes the stream
     * (process shutdown, network drop). Callers typically hand this flow to
     * `shareIn` on the runtime scope so multiple sessions can multiplex.
     *
     * Frames are decoded as [OpencodeEventEnvelope]; unknown envelopes are
     * skipped with a log line rather than aborting the subscription.
     */
    fun eventStream(): Flow<OpencodeEventEnvelope> =
        channelFlow {
            client.sse(urlString = url("/event")) {
                incoming.collect { sse ->
                    val data = sse.data ?: return@collect
                    try {
                        val envelope = defaultJson.decodeFromString(OpencodeEventEnvelope.serializer(), data)
                        send(envelope)
                    } catch (e: Exception) {
                        logger.warn("Skipping malformed OpenCode SSE frame (${e.message}): ${data.take(200)}")
                    }
                }
            }
            awaitClose()
        }

    override fun close() {
        try {
            client.close()
        } catch (_: Exception) {
        }
    }

    private fun url(path: String): String {
        val root = baseUrl.trimEnd('/')
        val rel = if (path.startsWith("/")) path else "/$path"
        return "$root$rel?directory=${directory.encodeURLParameter()}"
    }

    private suspend fun HttpResponse.raiseForStatus(op: String) {
        if (status.value !in 200..299) {
            val bodyText = try {
                bodyAsText()
            } catch (_: Exception) {
                ""
            }
            throw OpencodeHttpException(op, status.value, bodyText)
        }
    }

    companion object {
        private val logger = Logger.getInstance(OpencodeHttpClient::class.java)
        private const val PING_TIMEOUT_MS: Long = 5_000L
    }
}

class OpencodeHttpException(op: String, val statusCode: Int, val body: String) :
    RuntimeException("OpenCode $op failed with HTTP $statusCode: ${body.take(500)}")
