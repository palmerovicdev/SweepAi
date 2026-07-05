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
 * OpenCode provider backed by the Node.js sidecar. Delegates every call to
 * `channels/opencode.js`, which uses `@opencode-ai/sdk`'s `createOpencode()`
 * to spin up an in-process server and streams its SSE events into our
 * normalized NDJSON vocabulary.
 *
 * All settings (`opencodeAgent`, `codexModel` for the assistant model) are
 * re-read on every [sendUserMessage] so pill changes take effect immediately.
 */
class OpencodeBridgeProvider : ExternalAgentProvider {
    private val logger = Logger.getInstance(OpencodeBridgeProvider::class.java)

    override val id: String = "opencode"
    override val displayName: String = "OpenCode"
    override val processScope: ProcessScope = ProcessScope.PER_IDE

    override fun detectExecutable(): String? = NodeDetector.detect(
        SweepSettings.getInstance().aiBridgeNodePath.ifBlank { null },
    )

    override suspend fun ensureRunning(project: Project, settings: SweepSettings): ProviderHandle {
        NodeBridgeClient.getInstance().warmStart()
        return ProviderHandle(Unit)
    }

    override suspend fun close(handle: ProviderHandle) {
        // Bridge is process-wide; nothing to close here.
    }

    override suspend fun createSession(handle: ProviderHandle, cwd: String, hints: SessionHints): String {
        val params = buildJsonObject {
            put("cwd", cwd)
            hints.agent?.let { put("agent", it) }
        }
        var sessionId: String? = null
        NodeBridgeClient.getInstance().call("opencode.createSession", params).collect { payload ->
            if (payload.eventName == "session.created") {
                sessionId = (payload.data as? JsonObject)?.get("sessionId")?.let {
                    (it as? JsonPrimitive)?.contentOrNull
                } ?: sessionId
            }
        }
        return sessionId ?: throw BridgeException("opencode.createSession returned no sessionId")
    }

    override suspend fun resumeSession(handle: ProviderHandle, remoteSessionId: String): ResumeResult {
        val params = buildJsonObject { put("sessionId", remoteSessionId) }
        return try {
            var restored: String? = null
            NodeBridgeClient.getInstance().call("opencode.resumeSession", params).collect { payload ->
                if (payload.eventName == "session.resumed") {
                    restored = (payload.data as? JsonObject)?.get("sessionId")?.let {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: remoteSessionId
                }
            }
            if (restored != null) ResumeResult.Ok(restored!!) else ResumeResult.NotFound
        } catch (e: BridgeException) {
            if (e.code == -32602 || (e.message ?: "").contains("not found", ignoreCase = true)) {
                ResumeResult.NotFound
            } else {
                ResumeResult.Failed(e.message ?: "unknown")
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
        val params = buildJsonObject {
            put("sessionId", remoteSessionId)
            put("prompt", prompt)
            put("agent", settings.opencodeAgent)
            // OpenCode uses `providerID/modelID`; empty = server default.
            if (settings.opencodeModel.isNotBlank()) put("model", settings.opencodeModel)
        }
        NodeBridgeClient.getInstance().call("opencode.send", params).collect { payload ->
            NodeBridgeEventAdapter.translate(remoteSessionId, payload)?.let { emit(it) }
        }
    }

    override suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String) {
        try {
            val params = buildJsonObject { put("sessionId", remoteSessionId) }
            NodeBridgeClient.getInstance().call("opencode.cancel", params).collect { /* drain */ }
        } catch (e: BridgeException) {
            logger.warn("opencode.cancel failed for $remoteSessionId: ${e.message}")
        }
    }

    override suspend fun answerPermission(
        handle: ProviderHandle,
        remoteSessionId: String,
        permissionId: String,
        allow: Boolean,
    ) {
        try {
            val params = buildJsonObject {
                put("sessionId", remoteSessionId)
                put("permissionId", permissionId)
                put("allow", allow)
            }
            NodeBridgeClient.getInstance().call("opencode.answerPermission", params).collect { /* drain */ }
        } catch (e: BridgeException) {
            logger.warn("opencode.answerPermission failed: ${e.message}")
        }
    }

    override suspend fun testConnection(handle: ProviderHandle): TestResult = try {
        val versions = NodeBridgeClient.getInstance().ping()
        val sdks = versions["sdks"] as? JsonObject
        val opencodeVersion = sdks?.get("opencode")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "not installed"
        TestResult.Ok("Bridge ready · @opencode-ai/sdk $opencodeVersion")
    } catch (e: BridgeException) {
        TestResult.Fail(e.message ?: "bridge unavailable")
    } catch (e: Throwable) {
        TestResult.Fail(e.message ?: e.javaClass.simpleName)
    }
}
