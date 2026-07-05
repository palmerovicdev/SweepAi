package dev.sweep.assistant.api.external.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// NDJSON wire format between Kotlin and ai-bridge/daemon.js.
//
// Kotlin → Node: `{"id":<n>,"method":"<ns>.<op>","params":{...}}`
// Node → Kotlin: either
//   `{"id":<n>,"event":"<name>","data":{...}}`   — streaming event
//   `{"id":<n>,"error":{"message":"...",code?}}` — terminal error
//   `{"id":<n>,"done":true}`                     — terminal success
//
// Every response carries the same `id` as the originating request so a single
// stdout stream can multiplex many concurrent calls.

/** Terminal reason for a bridge call. */
sealed interface BridgeCallResult {
    data object Done : BridgeCallResult

    data class Failed(val message: String, val code: Int? = null) : BridgeCallResult
}

/** One streaming payload emitted by the daemon for an in-flight request. */
sealed interface BridgeEvent {
    data class Payload(val eventName: String, val data: JsonElement?) : BridgeEvent

    data class Terminal(val result: BridgeCallResult) : BridgeEvent
}

/** Parse an incoming JSON line into a normalized event. */
internal fun classifyBridgeLine(line: JsonObject): BridgeEvent {
    (line["done"] as? JsonPrimitive)?.booleanOrNull?.let { done ->
        if (done) return BridgeEvent.Terminal(BridgeCallResult.Done)
    }
    (line["error"] as? JsonObject)?.let { err ->
        val message = (err["message"] as? JsonPrimitive)?.content ?: "unknown error"
        val code = (err["code"] as? JsonPrimitive)?.intOrNull
        return BridgeEvent.Terminal(BridgeCallResult.Failed(message, code))
    }
    val eventName = (line["event"] as? JsonPrimitive)?.content
        ?: return BridgeEvent.Terminal(BridgeCallResult.Failed("malformed bridge line: missing event/done/error"))
    return BridgeEvent.Payload(eventName, line["data"])
}
