package dev.sweep.assistant.api.external.codex

import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.api.external.ExternalAgentEvent
import dev.sweep.assistant.api.external.TextKind
import dev.sweep.assistant.utils.defaultJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure translation from Codex item and turn notifications (`item/started`,
 * `item/updated`, `item/completed`, `item/failed`, `turn/started`,
 * `turn/completed`, `turn/failed`) to the normalized [ExternalAgentEvent]
 * stream. Stateful bookkeeping — last-seen text per item id, which tool calls
 * have already been surfaced — lives on the per-turn [CodexItemTracker] the
 * caller instantiates.
 *
 * Codex re-emits full item snapshots on each `item/updated` frame, so we emit
 * deltas by diffing against the previous seen text (mirrors the OpenCode
 * adapter). Terminal `item/completed` and `item/failed` frames carry the final
 * state and are used to mark tool calls as finished.
 */
object CodexProtocolAdapter {
    private val logger = Logger.getInstance(CodexProtocolAdapter::class.java)

    /**
     * Translate one server notification. Returns an empty list for notifications
     * this turn doesn't care about (other threads reusing the same process,
     * echoed user messages, unrelated item types).
     */
    fun translate(
        method: String,
        params: JsonElement?,
        forThreadId: String,
        tracker: CodexItemTracker,
    ): List<ExternalAgentEvent> {
        val obj = params as? JsonObject ?: return emptyList()
        return when (method) {
            CodexMethods.TURN_STARTED -> emptyList()
            CodexMethods.TURN_COMPLETED -> translateTurnCompleted(obj, forThreadId)
            CodexMethods.TURN_FAILED -> translateTurnFailed(obj, forThreadId)
            CodexMethods.ITEM_STARTED,
            CodexMethods.ITEM_UPDATED,
            CodexMethods.ITEM_COMPLETED,
            CodexMethods.ITEM_FAILED,
            -> translateItem(method, obj, forThreadId, tracker)
            else -> emptyList()
        }
    }

    /** True when [event] terminates the current turn. */
    fun isTerminal(event: ExternalAgentEvent): Boolean =
        event is ExternalAgentEvent.TurnCompleted ||
            (event is ExternalAgentEvent.Error && !event.recoverable)

    private fun translateTurnCompleted(obj: JsonObject, forThreadId: String): List<ExternalAgentEvent> {
        val threadId = obj["threadId"]?.jsonPrimitive?.content
        if (threadId != null && threadId != forThreadId) return emptyList()
        val decoded =
            try {
                defaultJson.decodeFromJsonElement(CodexTurnCompleted.serializer(), obj)
            } catch (e: Exception) {
                logger.warn("Skipping unparseable turn/completed: ${e.message}")
                CodexTurnCompleted()
            }
        val usage =
            buildMap<String, Long> {
                decoded.usage?.inputTokens?.let { put("inputTokens", it) }
                decoded.usage?.cachedInputTokens?.let { put("cachedInputTokens", it) }
                decoded.usage?.outputTokens?.let { put("outputTokens", it) }
                decoded.usage?.totalTokens?.let { put("totalTokens", it) }
            }
        return listOf(ExternalAgentEvent.TurnCompleted(remoteSessionId = forThreadId, usage = usage))
    }

    private fun translateTurnFailed(obj: JsonObject, forThreadId: String): List<ExternalAgentEvent> {
        val threadId = obj["threadId"]?.jsonPrimitive?.content
        if (threadId != null && threadId != forThreadId) return emptyList()
        val reason =
            obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: obj["reason"]?.jsonPrimitive?.content
                ?: "Codex reported a turn failure"
        return listOf(ExternalAgentEvent.Error(reason = reason, recoverable = false))
    }

    private fun translateItem(
        method: String,
        obj: JsonObject,
        forThreadId: String,
        tracker: CodexItemTracker,
    ): List<ExternalAgentEvent> {
        val threadId = obj["threadId"]?.jsonPrimitive?.content
        if (threadId != null && threadId != forThreadId) return emptyList()
        val itemJson = obj["item"]?.jsonObject ?: return emptyList()
        val type = itemJson["type"]?.jsonPrimitive?.content ?: return emptyList()
        val itemId =
            itemJson["id"]?.jsonPrimitive?.content
                ?: itemJson["itemId"]?.jsonPrimitive?.content
                ?: return emptyList()

        return when (type) {
            CodexItemTypes.AGENT_MESSAGE -> emitTextDelta(itemId, itemJson, tracker, TextKind.CONTENT)
            CodexItemTypes.AGENT_REASONING -> emitTextDelta(itemId, itemJson, tracker, TextKind.THINKING)
            CodexItemTypes.TOOL_CALL -> emitToolState(method, itemId, itemJson, tracker)
            CodexItemTypes.ERROR -> emitError(itemJson)
            CodexItemTypes.USER_MESSAGE -> emptyList()
            else -> emptyList()
        }
    }

    private fun emitTextDelta(
        itemId: String,
        itemJson: JsonObject,
        tracker: CodexItemTracker,
        kind: TextKind,
    ): List<ExternalAgentEvent> {
        val text = readText(itemJson) ?: return emptyList()
        val delta = tracker.deltaFor(itemId, text) ?: return emptyList()
        return listOf(ExternalAgentEvent.TextDelta(delta = delta, kind = kind))
    }

    /**
     * Codex delivers text either as `text: "..."` or `content: [{"type":"text","text":"..."}, ...]`
     * depending on the item variant. Accept both.
     */
    private fun readText(itemJson: JsonObject): String? {
        val direct = itemJson["text"]?.jsonPrimitive?.content
        if (!direct.isNullOrEmpty()) return direct
        val content = itemJson["content"] as? kotlinx.serialization.json.JsonArray ?: return null
        val builder = StringBuilder()
        for (part in content) {
            val partObj = part as? JsonObject ?: continue
            val partType = partObj["type"]?.jsonPrimitive?.content
            if (partType == "text" || partType == "output_text" || partType == null) {
                partObj["text"]?.jsonPrimitive?.content?.let { builder.append(it) }
            }
        }
        return builder.toString().ifEmpty { null }
    }

    private fun emitToolState(
        method: String,
        itemId: String,
        itemJson: JsonObject,
        tracker: CodexItemTracker,
    ): List<ExternalAgentEvent> {
        val toolName =
            itemJson["name"]?.jsonPrimitive?.content
                ?: itemJson["toolName"]?.jsonPrimitive?.content
                ?: "tool"

        // First frame carrying the call → surface Started once. Codex fires
        // `item/started` first, but for defensive coding we also catch the
        // first `item/updated` that mentions the tool.
        val startEvents =
            if (method == CodexMethods.ITEM_STARTED || method == CodexMethods.ITEM_UPDATED) {
                if (tracker.markToolStarted(itemId)) {
                    val args = extractArgs(itemJson)
                    listOf(ExternalAgentEvent.ToolCallStarted(itemId, toolName, args))
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val completionEvents =
            when (method) {
                CodexMethods.ITEM_COMPLETED ->
                    if (tracker.markToolCompleted(itemId)) {
                        listOf(
                            ExternalAgentEvent.ToolCallCompleted(
                                toolCallId = itemId,
                                ok = readToolSuccess(itemJson),
                                output = readToolOutput(itemJson),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                CodexMethods.ITEM_FAILED ->
                    if (tracker.markToolCompleted(itemId)) {
                        listOf(
                            ExternalAgentEvent.ToolCallCompleted(
                                toolCallId = itemId,
                                ok = false,
                                output = readToolError(itemJson),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                else -> emptyList()
            }

        return startEvents + completionEvents
    }

    private fun readToolSuccess(itemJson: JsonObject): Boolean {
        val explicit = itemJson["success"]?.jsonPrimitive?.content
        if (explicit == "true") return true
        if (explicit == "false") return false
        val status = itemJson["status"]?.jsonPrimitive?.content
        return status?.equals("error", ignoreCase = true) != true
    }

    private fun readToolOutput(itemJson: JsonObject): String {
        val direct = itemJson["output"]?.jsonPrimitive?.content
        if (!direct.isNullOrEmpty()) return truncate(direct)
        val result = itemJson["result"]?.jsonPrimitive?.content
        if (!result.isNullOrEmpty()) return truncate(result)
        val content = itemJson["content"] as? kotlinx.serialization.json.JsonArray
        if (content != null) {
            val builder = StringBuilder()
            for (part in content) {
                val partObj = part as? JsonObject ?: continue
                partObj["text"]?.jsonPrimitive?.content?.let { builder.append(it) }
            }
            if (builder.isNotEmpty()) return truncate(builder.toString())
        }
        return ""
    }

    private fun readToolError(itemJson: JsonObject): String {
        val explicit = itemJson["error"]?.let { el ->
            (el as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        }
        if (!explicit.isNullOrEmpty()) return truncate(explicit)
        return readToolOutput(itemJson).ifEmpty { "tool errored" }
    }

    private fun emitError(itemJson: JsonObject): List<ExternalAgentEvent> {
        val message =
            itemJson["message"]?.jsonPrimitive?.content
                ?: itemJson["error"]?.jsonPrimitive?.content
                ?: itemJson.toString().take(200)
        return listOf(ExternalAgentEvent.Error(reason = message, recoverable = false))
    }

    private fun extractArgs(itemJson: JsonObject): Map<String, Any?> {
        val args = itemJson["arguments"] ?: itemJson["input"] ?: return emptyMap()
        val obj = args as? JsonObject ?: return mapOf("input" to args.toString())
        return obj.mapValues { (_, value) -> value.toString().trim('"') }
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_TOOL_OUTPUT_CHARS) return value
        return value.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n[truncated]"
    }

    private const val MAX_TOOL_OUTPUT_CHARS = 100_000
}

/**
 * Per-turn tracker for text deltas and tool-call dedupe. Instantiated once per
 * `sendUserMessage` call so state never leaks across turns.
 */
class CodexItemTracker {
    private val lastSeenText = HashMap<String, String>()
    private val startedTools = HashSet<String>()
    private val completedTools = HashSet<String>()

    fun deltaFor(itemId: String, fullText: String): String? {
        val previous = lastSeenText[itemId] ?: ""
        if (fullText == previous) return null
        val delta = if (fullText.startsWith(previous)) fullText.substring(previous.length) else fullText
        lastSeenText[itemId] = fullText
        return delta.ifEmpty { null }
    }

    fun markToolStarted(itemId: String): Boolean = startedTools.add(itemId)

    fun markToolCompleted(itemId: String): Boolean = completedTools.add(itemId)
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.ifBlank { null }
