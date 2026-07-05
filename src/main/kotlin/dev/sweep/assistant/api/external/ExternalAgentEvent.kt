package dev.sweep.assistant.api.external

// Normalized event stream emitted by every ExternalAgentProvider. Provider adapters
// translate their native events (Codex item/... notifications, OpenCode SSE
// message.updated parts, future ACP sessionUpdate payloads) into these shapes so the
// ExternalAgentChatEngine and the UI never need to know which agent is answering.
sealed interface ExternalAgentEvent {
    data class TextDelta(
        val delta: String,
        val kind: TextKind = TextKind.CONTENT,
    ) : ExternalAgentEvent

    data class ToolCallStarted(
        val toolCallId: String,
        val toolName: String,
        val args: Map<String, Any?>,
    ) : ExternalAgentEvent

    data class ToolCallCompleted(
        val toolCallId: String,
        val ok: Boolean,
        val output: String,
    ) : ExternalAgentEvent

    data class PermissionRequested(
        val permissionId: String,
        val toolName: String,
        val args: Map<String, Any?>,
    ) : ExternalAgentEvent

    data class TurnCompleted(
        val remoteSessionId: String,
        val usage: Map<String, Long> = emptyMap(),
    ) : ExternalAgentEvent

    data class Error(
        val reason: String,
        val recoverable: Boolean,
    ) : ExternalAgentEvent

    // Emitted when the provider assigns a durable session id after the
    // caller was handed a placeholder. Codex hands out a synthetic tempId at
    // startThread and only mints the real thread_id during the first send —
    // this event carries the mapping so the session store can be repointed.
    data class RemoteSessionIdUpdated(
        val oldId: String,
        val newId: String,
    ) : ExternalAgentEvent
}

enum class TextKind {
    CONTENT,
    THINKING,
}
