package dev.sweep.assistant.api.external.bridge

import dev.sweep.assistant.api.external.ExternalAgentEvent
import dev.sweep.assistant.api.external.TextKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Translates a normalized [BridgeEvent.Payload] into an [ExternalAgentEvent].
 *
 * The daemon already normalizes SDK-specific event shapes to a small
 * event vocabulary (`text.delta`, `tool.start`, `tool.done`, `permission.request`,
 * `turn.started`, `turn.done`, `turn.failed`, `error`, `thread.started`,
 * `thread.created`, `thread.resumed`), so this adapter is intentionally thin.
 * Provider-agnostic events like `sdk.raw` are ignored.
 */
object NodeBridgeEventAdapter {
    fun translate(
        remoteSessionId: String,
        payload: BridgeEvent.Payload,
    ): ExternalAgentEvent? {
        val data = payload.data as? JsonObject
        return when (payload.eventName) {
            "text.delta" -> {
                val delta = data?.stringOrNull("delta") ?: return null
                val kind = if (data.stringOrNull("kind") == "thinking") TextKind.THINKING else TextKind.CONTENT
                ExternalAgentEvent.TextDelta(delta, kind)
            }
            "tool.start" -> {
                val id = data?.stringOrNull("toolCallId") ?: return null
                val name = data.stringOrNull("toolName") ?: "tool"
                val args = (data["args"] as? JsonObject)?.let { jsonObjectToMap(it) } ?: emptyMap()
                ExternalAgentEvent.ToolCallStarted(id, name, args)
            }
            "tool.done" -> {
                val id = data?.stringOrNull("toolCallId") ?: return null
                val ok = data["ok"]?.jsonPrimitive?.booleanOrNull ?: true
                val output = data.stringOrNull("output") ?: ""
                ExternalAgentEvent.ToolCallCompleted(id, ok, output)
            }
            "permission.request" -> {
                val pid = data?.stringOrNull("permissionId") ?: return null
                val name = data.stringOrNull("toolName") ?: "tool"
                val args = (data["args"] as? JsonObject)?.let { jsonObjectToMap(it) } ?: emptyMap()
                ExternalAgentEvent.PermissionRequested(pid, name, args)
            }
            "turn.done" -> {
                val usage = (data?.get("usage") as? JsonObject)
                    ?.entries
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { k to it } }
                    ?.toMap()
                    ?: emptyMap()
                ExternalAgentEvent.TurnCompleted(remoteSessionId, usage)
            }
            "turn.failed" -> {
                val message = data?.stringOrNull("message") ?: "turn failed"
                ExternalAgentEvent.Error(message, recoverable = true)
            }
            "turn.cancelled" -> {
                ExternalAgentEvent.Error("Cancelled by user", recoverable = true)
            }
            "error" -> {
                val message = data?.stringOrNull("message") ?: "unknown bridge error"
                ExternalAgentEvent.Error(message, recoverable = false)
            }
            "session.id_updated" -> {
                val oldId = data?.stringOrNull("oldId") ?: return null
                val newId = data.stringOrNull("newId") ?: return null
                ExternalAgentEvent.RemoteSessionIdUpdated(oldId, newId)
            }
            "thread.started", "thread.created", "thread.resumed", "turn.started", "sdk.raw" -> null
            else -> null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> =
        obj.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> v.contentOrNull
                is JsonObject -> jsonObjectToMap(v)
                else -> v.toString()
            }
        }
}
