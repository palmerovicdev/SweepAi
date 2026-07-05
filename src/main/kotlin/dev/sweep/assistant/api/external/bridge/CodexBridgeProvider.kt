package dev.sweep.assistant.api.external.bridge

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Codex provider backed by the Node.js sidecar (see [NodeBridgeClient]).
 *
 * All heavy lifting — spawning the CLI, JSON-RPC framing, streaming, retries —
 * lives in `@openai/codex-sdk` inside the daemon. This provider only translates
 * between the [ExternalAgentProvider] contract and NDJSON calls.
 *
 * Every [sendUserMessage] re-reads `SweepSettings` so approval / model /
 * reasoning / sandbox / thinking pills can be flipped between turns without
 * restarting anything.
 */
class CodexBridgeProvider : ExternalAgentProvider {
    private val logger = Logger.getInstance(CodexBridgeProvider::class.java)

    override val id: String = "codex"
    override val displayName: String = "Codex"
    override val processScope: ProcessScope = ProcessScope.PER_CONVERSATION

    override fun detectExecutable(): String? = NodeDetector.detect(
        SweepSettings.getInstance().aiBridgeNodePath.ifBlank { null },
    )

    override suspend fun ensureRunning(project: Project, settings: SweepSettings): ProviderHandle {
        NodeBridgeClient.getInstance().warmStart()
        return ProviderHandle(Unit)
    }

    override suspend fun close(handle: ProviderHandle) {
        // Bridge is shared across the IDE; not our place to shut it down here.
    }

    override suspend fun createSession(handle: ProviderHandle, cwd: String, hints: SessionHints): String {
        val params = paramsToJson(buildParams(cwd, hints))
        var threadId: String? = null
        NodeBridgeClient.getInstance().call("codex.startThread", params).collect { payload ->
            if (payload.eventName == "thread.created" || payload.eventName == "thread.started") {
                threadId = (payload.data as? JsonObject)?.get("threadId")?.let {
                    (it as? JsonPrimitive)?.contentOrNull
                } ?: threadId
            }
        }
        return threadId ?: throw BridgeException("codex.startThread returned no threadId")
    }

    override suspend fun resumeSession(handle: ProviderHandle, remoteSessionId: String): ResumeResult {
        val base = buildParams(cwd = null, hints = SessionHints())
        val jsonParams = paramsToJson(base + ("threadId" to remoteSessionId))
        return try {
            var restored: String? = null
            NodeBridgeClient.getInstance().call("codex.resumeThread", jsonParams).collect { payload ->
                if (payload.eventName == "thread.resumed") {
                    restored = (payload.data as? JsonObject)?.get("threadId")?.let {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: remoteSessionId
                }
            }
            if (restored != null) ResumeResult.Ok(restored!!) else ResumeResult.NotFound
        } catch (e: BridgeException) {
            val message = e.message.orEmpty()
            if (message.contains("not found", ignoreCase = true) || e.code == -32602) {
                ResumeResult.NotFound
            } else {
                ResumeResult.Failed(message)
            }
        }
    }

    override suspend fun sendUserMessage(
        handle: ProviderHandle,
        remoteSessionId: String,
        prompt: String,
        images: List<ExternalAgentImage>,
    ): Flow<ExternalAgentEvent> = flow {
        val settings = SweepSettings.getInstance()
        if (images.isNotEmpty()) {
            // Codex SDK's local_image part needs a filesystem path, but our
            // ExternalAgentImage carries raw bytes. Until we persist them to
            // a temp file, drop them and warn — silently sending a prompt
            // with no image would be worse than a visible degradation.
            logger.warn("Codex provider received ${images.size} image(s); dropping (path-based upload not implemented)")
        }
        val params = buildJsonObject {
            put("threadId", remoteSessionId)
            put("prompt", prompt)
            put("model", settings.codexModel)
            put("approvalPolicy", settings.codexApprovalPolicy)
            put("sandbox", settings.codexSandbox)
            put("reasoningEffort", settings.codexReasoningEffort)
            // Codex SDK does not currently expose "thinking mode"; keep the
            // channel forward-compatible by passing it through and letting the
            // JS side ignore unknown params.
            put("thinking", settings.codexThinking)
        }
        NodeBridgeClient.getInstance().call("codex.send", params).collect { payload ->
            NodeBridgeEventAdapter.translate(remoteSessionId, payload)?.let { emit(it) }
        }
    }

    override suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String) {
        // The `send` call flow will collapse when its collector is cancelled
        // downstream; the daemon's abort signal is threaded via the same
        // request id. Nothing extra to do here for the SDK-backed path.
        logger.info("cancelCurrentTurn requested for $remoteSessionId (flow cancellation drives the abort)")
    }

    override suspend fun answerPermission(
        handle: ProviderHandle,
        remoteSessionId: String,
        permissionId: String,
        allow: Boolean,
    ) {
        // Codex SDK does not surface interactive approvals via the JSONL stream
        // (they are consumed inside the CLI). Retained for future compatibility.
    }

    override suspend fun testConnection(handle: ProviderHandle): TestResult {
        return try {
            val versions = NodeBridgeClient.getInstance().ping()
            val sdks = versions["sdks"] as? JsonObject
            val codexVersion = sdks?.get("codex")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: "not installed"
            TestResult.Ok("Bridge ready · @openai/codex-sdk $codexVersion")
        } catch (e: BridgeException) {
            TestResult.Fail(e.message ?: "bridge unavailable")
        } catch (e: Throwable) {
            TestResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    // =====================================================================================

    private fun buildParams(cwd: String?, hints: SessionHints): Map<String, String?> {
        val s = SweepSettings.getInstance()
        return mapOf(
            "cwd" to cwd,
            "model" to (hints.model ?: s.codexModel).takeIf { !it.isNullOrBlank() },
            "approvalPolicy" to s.codexApprovalPolicy,
            "sandbox" to s.codexSandbox,
            "reasoningEffort" to s.codexReasoningEffort,
        )
    }

    private fun paramsToJson(params: Map<String, String?>): JsonObject = buildJsonObject {
        for ((k, v) in params) {
            if (v != null) put(k, v)
        }
    }
}
