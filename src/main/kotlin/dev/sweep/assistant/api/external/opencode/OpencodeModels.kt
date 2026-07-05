package dev.sweep.assistant.api.external.opencode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// DTOs for the OpenCode `serve` HTTP surface. Only the fields the plugin consumes
// are modeled; the shared `defaultJson` has ignoreUnknownKeys = true so upstream
// additions don't break decoding. Cross-checked against sst/opencode v1.17.x
// (packages/opencode/src/session/prompt.ts and packages/sdk/js/src/v2/gen/types.gen.ts).

// ===== Session =====

@Serializable
data class OpencodeSession(
    val id: String,
    val directory: String? = null,
    val parentID: String? = null,
)

@Serializable
data class OpencodeCreateSessionRequest(
    val directory: String,
    val parentID: String? = null,
)

// ===== Message payload =====

@Serializable
data class OpencodeModelRef(
    val providerID: String,
    val modelID: String,
)

@Serializable
data class OpencodePromptRequest(
    val parts: List<OpencodePromptPart>,
    val agent: String? = null,
    val model: OpencodeModelRef? = null,
)

@Serializable
data class OpencodePromptPart(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
)

// ===== Permission =====

@Serializable
data class OpencodePermissionRequest(
    val response: String,
)

// ===== SSE event envelope =====

@Serializable
data class OpencodeEventEnvelope(
    val id: String? = null,
    val type: String,
    val properties: JsonObject = JsonObject(emptyMap()),
)

object OpencodeEventTypes {
    const val SERVER_CONNECTED = "server.connected"
    const val SESSION_IDLE = "session.idle"
    const val SESSION_ERROR = "session.error"
    const val MESSAGE_PART_UPDATED = "message.part.updated"
    const val MESSAGE_PART_DELTA = "message.part.delta"
    const val MESSAGE_UPDATED = "message.updated"
    const val PERMISSION_ASKED = "permission.asked"
    const val FILE_EDITED = "file.edited"
}

// ===== Part payloads carried inside properties.part =====

@Serializable
data class OpencodePart(
    val id: String? = null,
    val sessionID: String? = null,
    val messageID: String? = null,
    val type: String,
    // TextPart / ReasoningPart
    val text: String? = null,
    // ToolPart
    val tool: String? = null,
    val callID: String? = null,
    val state: OpencodeToolState? = null,
)

@Serializable
data class OpencodeToolState(
    val status: String,
    val input: JsonElement? = null,
    val output: String? = null,
    val error: String? = null,
    val title: String? = null,
)

// ===== Alternative v2 delta shape (`message.part.delta`) =====

@Serializable
data class OpencodeMessagePartDelta(
    val sessionID: String,
    val messageID: String? = null,
    val partID: String? = null,
    val field: String,
    val delta: String,
)

// ===== Permission asked payload =====

@Serializable
data class OpencodePermissionAsked(
    val sessionID: String? = null,
    val permission: OpencodePermission,
)

@Serializable
data class OpencodePermission(
    val id: String,
    val sessionID: String? = null,
    val tool: String? = null,
    val input: JsonElement? = null,
)

// ===== Session-scoped event wrapper (e.g. session.idle, session.error) =====

@Serializable
data class OpencodeSessionScoped(
    val sessionID: String,
    val error: JsonElement? = null,
)
