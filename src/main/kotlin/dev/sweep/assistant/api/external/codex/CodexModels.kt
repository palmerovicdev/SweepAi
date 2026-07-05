package dev.sweep.assistant.api.external.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// DTOs for the `codex app-server --listen stdio://` protocol. Per plan §10.1
// the wire is line-delimited JSON and the `"jsonrpc":"2.0"` marker is omitted.
// Only the fields the plugin consumes are modeled; the shared `defaultJson` has
// ignoreUnknownKeys = true so upstream additions don't break decoding.
//
// Cross-checked against openai/codex `codex-rs/app-server/README.md` at
// protocolVersion `rust_v0_29_1_alpha_7` (thread/start, thread/resume,
// turn/start, turn/interrupt, item/* notifications).

// ===== JSON-RPC envelopes =====
//
// The framing is minimal on purpose — Codex omits `jsonrpc` on the wire. Every
// line is one of three shapes:
//   1. Client request:      { "id": 1, "method": "...", "params": {...} }
//   2. Server response:     { "id": 1, "result": {...} }         or with "error"
//   3. Server notification: { "method": "turn/started", "params": {...} }
// Notifications never carry an id; responses never carry a method.

@Serializable
data class CodexRpcRequest(
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class CodexRpcNotification(
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class CodexRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

// ===== `initialize` =====

@Serializable
data class CodexInitializeParams(
    val clientCapabilities: JsonObject = JsonObject(emptyMap()),
    val protocolVersion: String = SUPPORTED_PROTOCOL_VERSION,
    val clientInfo: CodexClientInfo = CodexClientInfo(),
) {
    companion object {
        const val SUPPORTED_PROTOCOL_VERSION = "rust_v0_29_1_alpha_7"
    }
}

@Serializable
data class CodexClientInfo(
    val name: String = "sweep-ai-jetbrains",
    val version: String = "1.0",
)

@Serializable
data class CodexInitializeResult(
    val serverCapabilities: JsonObject = JsonObject(emptyMap()),
    val protocolVersion: String? = null,
    val serverInfo: JsonObject? = null,
    val authRequired: Boolean? = null,
)

// ===== `thread/start`, `thread/resume` =====

@Serializable
data class CodexThreadStartParams(
    val cwd: String,
    val model: String? = null,
    val approvalPolicy: String? = null,
    val sandbox: String? = null,
)

@Serializable
data class CodexThreadResumeParams(
    val threadId: String,
    val excludeTurns: Boolean = true,
)

@Serializable
data class CodexThread(
    val threadId: String,
)

// ===== `turn/start`, `turn/interrupt` =====

@Serializable
data class CodexTurnStartParams(
    val threadId: String,
    val input: List<CodexTurnInputPart>,
)

@Serializable
data class CodexTurnInputPart(
    val type: String,
    val text: String? = null,
)

@Serializable
data class CodexTurnInterruptParams(
    val threadId: String,
)

@Serializable
data class CodexTurnStarted(
    val threadId: String,
    val turnId: String,
)

@Serializable
data class CodexTurnCompleted(
    val threadId: String? = null,
    val turnId: String? = null,
    val usage: CodexUsage? = null,
)

@Serializable
data class CodexUsage(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)

// ===== `item/*` notifications =====
//
// Codex streams turn state as a series of `item/updated` frames plus terminal
// frames when the item settles (`item/completed`, `item/failed`). Rather than
// model every kind we let JSON stay opaque under `properties.item`; the
// protocol adapter reads only the fields it needs and dedupes by `itemId`.

@Serializable
data class CodexItemNotification(
    val threadId: String? = null,
    val turnId: String? = null,
    val item: JsonObject = JsonObject(emptyMap()),
)

object CodexMethods {
    // Client → server
    const val INITIALIZE = "initialize"
    const val THREAD_START = "thread/start"
    const val THREAD_RESUME = "thread/resume"
    const val TURN_START = "turn/start"
    const val TURN_INTERRUPT = "turn/interrupt"

    // Server → client notifications
    const val TURN_STARTED = "turn/started"
    const val TURN_COMPLETED = "turn/completed"
    const val TURN_FAILED = "turn/failed"
    const val ITEM_STARTED = "item/started"
    const val ITEM_UPDATED = "item/updated"
    const val ITEM_COMPLETED = "item/completed"
    const val ITEM_FAILED = "item/failed"

    // Server → client requests (Sweep MVP declines these — see plan §17)
    const val PERMISSION_REQUEST = "permission/request"
    const val APPROVAL_REQUEST = "approval/request"
    const val ITEM_TOOL_CALL = "item/tool/call"
}

/**
 * Item kinds we care about while translating notifications. Everything else
 * (raw system reminders, tool listings, etc.) is dropped by the adapter.
 * String values mirror the `type` field on the `item` payload.
 */
object CodexItemTypes {
    const val AGENT_MESSAGE = "agent_message"
    const val AGENT_REASONING = "agent_reasoning"
    const val TOOL_CALL = "tool_call"
    const val USER_MESSAGE = "user_message"
    const val ERROR = "error"
}
