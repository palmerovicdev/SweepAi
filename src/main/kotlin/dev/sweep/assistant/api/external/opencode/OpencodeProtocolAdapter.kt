package dev.sweep.assistant.api.external.opencode

import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.api.external.ExternalAgentEvent
import dev.sweep.assistant.api.external.TextKind
import dev.sweep.assistant.utils.defaultJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure translation from OpenCode SSE envelopes to the plugin's normalized
 * [ExternalAgentEvent] stream. Stateful behaviour (tracking last-seen text on
 * a part id so we can emit *deltas* instead of full snapshots) lives on the
 * per-flow [PartTextTracker] the caller instantiates.
 *
 * Cross-checked against sst/opencode v1.17.x — `packages/opencode/src/server/
 * routes/instance/httpapi/handlers/event.ts` (envelope shape) and
 * `packages/opencode/src/message/message-v2.ts` (part.state.status transitions).
 */
object OpencodeProtocolAdapter {
    private val logger = Logger.getInstance(OpencodeProtocolAdapter::class.java)

    /**
     * Attempt to translate a single envelope into zero or more downstream
     * events, filtered to the caller's session. Returns an empty list for
     * frames that don't concern this session (other tabs sharing the same
     * process, keep-alives, etc.).
     */
    fun translate(
        envelope: OpencodeEventEnvelope,
        forSessionId: String,
        tracker: PartTextTracker,
    ): List<ExternalAgentEvent> =
        when (envelope.type) {
            OpencodeEventTypes.SERVER_CONNECTED -> emptyList()
            OpencodeEventTypes.MESSAGE_PART_UPDATED -> translatePartUpdated(envelope.properties, forSessionId, tracker)
            OpencodeEventTypes.MESSAGE_PART_DELTA -> translatePartDelta(envelope.properties, forSessionId)
            OpencodeEventTypes.PERMISSION_ASKED -> translatePermission(envelope.properties, forSessionId)
            OpencodeEventTypes.SESSION_IDLE -> translateSessionIdle(envelope.properties, forSessionId)
            OpencodeEventTypes.SESSION_ERROR -> translateSessionError(envelope.properties, forSessionId)
            OpencodeEventTypes.MESSAGE_UPDATED, OpencodeEventTypes.FILE_EDITED -> emptyList()
            else -> emptyList()
        }

    /** True when [event] terminates the current turn (idle or fatal error). */
    fun isTerminal(event: ExternalAgentEvent): Boolean =
        event is ExternalAgentEvent.TurnCompleted ||
            (event is ExternalAgentEvent.Error && !event.recoverable)

    private fun translatePartUpdated(
        properties: JsonObject,
        forSessionId: String,
        tracker: PartTextTracker,
    ): List<ExternalAgentEvent> {
        val partJson = properties["part"]?.jsonObject ?: return emptyList()
        val part =
            try {
                defaultJson.decodeFromJsonElement(OpencodePart.serializer(), partJson)
            } catch (e: Exception) {
                logger.warn("Skipping unparseable OpenCode part: ${e.message}")
                return emptyList()
            }
        if (part.sessionID != null && part.sessionID != forSessionId) return emptyList()

        return when (part.type) {
            "text" -> emitTextDelta(part, tracker, TextKind.CONTENT)
            "reasoning" -> emitTextDelta(part, tracker, TextKind.THINKING)
            "tool" -> emitToolState(part, tracker)
            else -> emptyList()
        }
    }

    private fun emitTextDelta(
        part: OpencodePart,
        tracker: PartTextTracker,
        kind: TextKind,
    ): List<ExternalAgentEvent> {
        val text = part.text ?: return emptyList()
        val partId = part.id ?: return emptyList()
        val delta = tracker.deltaFor(partId, text) ?: return emptyList()
        return listOf(ExternalAgentEvent.TextDelta(delta = delta, kind = kind))
    }

    private fun emitToolState(part: OpencodePart, tracker: PartTextTracker): List<ExternalAgentEvent> {
        val callId = part.callID ?: part.id ?: return emptyList()
        val toolName = part.tool ?: "tool"
        val state = part.state ?: return emptyList()
        val args = extractArgs(state)
        return when (state.status) {
            // "pending" (queued) is a no-op — we surface the tool call on the
            // first "running" event so the UI doesn't see two Started events
            // for the same callId. OpenCode also re-emits the running state
            // whenever the tool posts progress, so we dedupe on callId.
            "pending" -> emptyList()
            "running" ->
                if (tracker.markToolStarted(callId)) {
                    listOf(ExternalAgentEvent.ToolCallStarted(callId, toolName, args))
                } else {
                    emptyList()
                }
            "completed" ->
                if (tracker.markToolCompleted(callId)) {
                    listOf(
                        ExternalAgentEvent.ToolCallCompleted(
                            toolCallId = callId,
                            ok = true,
                            output = state.output ?: state.title ?: "",
                        ),
                    )
                } else {
                    emptyList()
                }
            "error" ->
                if (tracker.markToolCompleted(callId)) {
                    listOf(
                        ExternalAgentEvent.ToolCallCompleted(
                            toolCallId = callId,
                            ok = false,
                            output = state.error ?: "tool errored",
                        ),
                    )
                } else {
                    emptyList()
                }
            else -> emptyList()
        }
    }

    private fun extractArgs(state: OpencodeToolState): Map<String, Any?> {
        val input = state.input ?: return emptyMap()
        val obj = input as? JsonObject ?: return mapOf("input" to input.toString())
        return obj.mapValues { (_, value) -> value.toString().trim('"') }
    }

    private fun translatePartDelta(
        properties: JsonObject,
        forSessionId: String,
    ): List<ExternalAgentEvent> {
        val delta =
            try {
                defaultJson.decodeFromJsonElement(OpencodeMessagePartDelta.serializer(), properties)
            } catch (e: Exception) {
                logger.warn("Skipping unparseable OpenCode delta: ${e.message}")
                return emptyList()
            }
        if (delta.sessionID != forSessionId) return emptyList()
        val kind =
            when (delta.field) {
                "reasoning" -> TextKind.THINKING
                else -> TextKind.CONTENT
            }
        return listOf(ExternalAgentEvent.TextDelta(delta = delta.delta, kind = kind))
    }

    private fun translatePermission(
        properties: JsonObject,
        forSessionId: String,
    ): List<ExternalAgentEvent> {
        val asked =
            try {
                defaultJson.decodeFromJsonElement(OpencodePermissionAsked.serializer(), properties)
            } catch (e: Exception) {
                logger.warn("Skipping unparseable permission.asked: ${e.message}")
                return emptyList()
            }
        val sessionId = asked.sessionID ?: asked.permission.sessionID
        if (sessionId != null && sessionId != forSessionId) return emptyList()
        return listOf(
            ExternalAgentEvent.PermissionRequested(
                permissionId = asked.permission.id,
                toolName = asked.permission.tool ?: "tool",
                args = emptyMap(),
            ),
        )
    }

    private fun translateSessionIdle(
        properties: JsonObject,
        forSessionId: String,
    ): List<ExternalAgentEvent> {
        val sessionId = properties["sessionID"]?.jsonPrimitive?.content ?: return emptyList()
        if (sessionId != forSessionId) return emptyList()
        return listOf(ExternalAgentEvent.TurnCompleted(remoteSessionId = sessionId))
    }

    private fun translateSessionError(
        properties: JsonObject,
        forSessionId: String,
    ): List<ExternalAgentEvent> {
        val sessionId = properties["sessionID"]?.jsonPrimitive?.content ?: return emptyList()
        if (sessionId != forSessionId) return emptyList()
        val reason =
            properties["error"]?.toString()
                ?: properties["message"]?.jsonPrimitive?.content
                ?: "OpenCode reported a session error"
        return listOf(ExternalAgentEvent.Error(reason = reason, recoverable = false))
    }
}

/**
 * OpenCode's `message.part.updated` re-emits the FULL current text of a part on
 * each keystroke *and* re-fires the same tool state (running / completed) as
 * the tool posts progress. To render Sweep's Message-oriented UI incrementally
 * we track:
 *   - last-seen text per part id → emit the suffix as a delta
 *   - callIds we've already marked started / completed → skip duplicates
 * State is per-flow so each `sendUserMessage` call gets a fresh tracker and
 * the values never leak across turns.
 */
class PartTextTracker {
    private val lastSeen = HashMap<String, String>()
    private val startedTools = HashSet<String>()
    private val completedTools = HashSet<String>()

    fun deltaFor(partId: String, fullText: String): String? {
        val previous = lastSeen[partId] ?: ""
        if (fullText == previous) return null
        // Streaming should always append; on the rare replace-from-scratch case we
        // still emit the whole new text so nothing is silently dropped.
        val delta = if (fullText.startsWith(previous)) fullText.substring(previous.length) else fullText
        lastSeen[partId] = fullText
        return delta.ifEmpty { null }
    }

    /** Returns true only the first time [callId] is seen — subsequent calls dedupe. */
    fun markToolStarted(callId: String): Boolean = startedTools.add(callId)

    /** Returns true only the first time [callId] is seen — subsequent calls dedupe. */
    fun markToolCompleted(callId: String): Boolean = completedTools.add(callId)
}
