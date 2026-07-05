package dev.sweep.assistant.api.external

import dev.sweep.assistant.api.external.codex.CodexItemTracker
import dev.sweep.assistant.api.external.codex.CodexMethods
import dev.sweep.assistant.api.external.codex.CodexProtocolAdapter
import dev.sweep.assistant.api.external.opencode.OpencodeEventEnvelope
import dev.sweep.assistant.api.external.opencode.OpencodeEventTypes
import dev.sweep.assistant.api.external.opencode.OpencodeProtocolAdapter
import dev.sweep.assistant.api.external.opencode.PartTextTracker
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the plumbing added in Fase 2/3/4 of the
 * external-agent chat plan: OpenCode SSE envelope translation, Codex
 * JSON-RPC notification translation, and the provider registry.
 *
 * These tests only exercise units that don't reach into IntelliJ platform
 * services, so they can run under the standard JUnit runner without a
 * `LightPlatformTestCase` harness.
 */
class ExternalAgentProtocolTest {
    // ===== OpenCode =====

    @Test
    fun `opencode text delta translates and dedupes lastSeen text`() {
        val tracker = PartTextTracker()
        val e1 = envelope(
            OpencodeEventTypes.MESSAGE_PART_UPDATED,
            buildJsonObject {
                putJsonObject("part") {
                    put("id", "part-1")
                    put("sessionID", "sess-A")
                    put("type", "text")
                    put("text", "Hello")
                }
            },
        )
        val e2 = envelope(
            OpencodeEventTypes.MESSAGE_PART_UPDATED,
            buildJsonObject {
                putJsonObject("part") {
                    put("id", "part-1")
                    put("sessionID", "sess-A")
                    put("type", "text")
                    put("text", "Hello, world!")
                }
            },
        )

        val first = OpencodeProtocolAdapter.translate(e1, "sess-A", tracker)
        val second = OpencodeProtocolAdapter.translate(e2, "sess-A", tracker)
        val third = OpencodeProtocolAdapter.translate(e2, "sess-A", tracker) // repeat — no delta

        first shouldHaveSize 1
        (first[0] as ExternalAgentEvent.TextDelta).delta shouldBe "Hello"
        second shouldHaveSize 1
        (second[0] as ExternalAgentEvent.TextDelta).delta shouldBe ", world!"
        third.shouldBeEmpty()
    }

    @Test
    fun `opencode filters events for other sessions`() {
        val tracker = PartTextTracker()
        val e = envelope(
            OpencodeEventTypes.MESSAGE_PART_UPDATED,
            buildJsonObject {
                putJsonObject("part") {
                    put("id", "part-x")
                    put("sessionID", "sess-OTHER")
                    put("type", "text")
                    put("text", "hi")
                }
            },
        )
        OpencodeProtocolAdapter.translate(e, "sess-MINE", tracker).shouldBeEmpty()
    }

    @Test
    fun `opencode tool started+completed emit exactly once per callId`() {
        val tracker = PartTextTracker()
        val started = envelope(
            OpencodeEventTypes.MESSAGE_PART_UPDATED,
            buildJsonObject {
                putJsonObject("part") {
                    put("id", "p")
                    put("sessionID", "s")
                    put("type", "tool")
                    put("tool", "read_file")
                    put("callID", "call-1")
                    putJsonObject("state") {
                        put("status", "running")
                        putJsonObject("input") { put("path", "src/App.kt") }
                    }
                }
            },
        )
        val completed = envelope(
            OpencodeEventTypes.MESSAGE_PART_UPDATED,
            buildJsonObject {
                putJsonObject("part") {
                    put("id", "p")
                    put("sessionID", "s")
                    put("type", "tool")
                    put("tool", "read_file")
                    put("callID", "call-1")
                    putJsonObject("state") {
                        put("status", "completed")
                        put("output", "file contents")
                    }
                }
            },
        )

        val startEvents = OpencodeProtocolAdapter.translate(started, "s", tracker)
        val startAgain = OpencodeProtocolAdapter.translate(started, "s", tracker)
        val doneEvents = OpencodeProtocolAdapter.translate(completed, "s", tracker)

        startEvents shouldHaveSize 1
        (startEvents[0] as ExternalAgentEvent.ToolCallStarted).apply {
            toolCallId shouldBe "call-1"
            toolName shouldBe "read_file"
            args["path"] shouldBe "src/App.kt"
        }
        startAgain.shouldBeEmpty()
        doneEvents shouldHaveSize 1
        (doneEvents[0] as ExternalAgentEvent.ToolCallCompleted).apply {
            toolCallId shouldBe "call-1"
            ok shouldBe true
            output shouldBe "file contents"
        }
    }

    @Test
    fun `opencode session_idle terminates the turn`() {
        val tracker = PartTextTracker()
        val idle = envelope(
            OpencodeEventTypes.SESSION_IDLE,
            buildJsonObject { put("sessionID", "s") },
        )
        val events = OpencodeProtocolAdapter.translate(idle, "s", tracker)
        events shouldHaveSize 1
        val ev = events.first().shouldBeInstanceOf<ExternalAgentEvent.TurnCompleted>()
        ev.remoteSessionId shouldBe "s"
        OpencodeProtocolAdapter.isTerminal(ev) shouldBe true
    }

    @Test
    fun `opencode session_error becomes non-recoverable Error`() {
        val tracker = PartTextTracker()
        val err = envelope(
            OpencodeEventTypes.SESSION_ERROR,
            buildJsonObject {
                put("sessionID", "s")
                put("message", "boom")
            },
        )
        val events = OpencodeProtocolAdapter.translate(err, "s", tracker)
        events shouldHaveSize 1
        val ev = events.first().shouldBeInstanceOf<ExternalAgentEvent.Error>()
        ev.recoverable shouldBe false
        OpencodeProtocolAdapter.isTerminal(ev) shouldBe true
    }

    // ===== Codex =====

    @Test
    fun `codex agent_message emits incremental delta`() {
        val tracker = CodexItemTracker()
        val first = Json.parseToJsonElement(
            """
            {
              "threadId": "thr-1",
              "item": {
                "id": "msg-1",
                "type": "agent_message",
                "text": "Hello"
              }
            }
            """.trimIndent(),
        )
        val second = Json.parseToJsonElement(
            """
            {
              "threadId": "thr-1",
              "item": {
                "id": "msg-1",
                "type": "agent_message",
                "text": "Hello, world"
              }
            }
            """.trimIndent(),
        )

        val e1 = CodexProtocolAdapter.translate(CodexMethods.ITEM_UPDATED, first, "thr-1", tracker)
        val e2 = CodexProtocolAdapter.translate(CodexMethods.ITEM_UPDATED, second, "thr-1", tracker)

        e1 shouldHaveSize 1
        (e1[0] as ExternalAgentEvent.TextDelta).delta shouldBe "Hello"
        e2 shouldHaveSize 1
        (e2[0] as ExternalAgentEvent.TextDelta).delta shouldBe ", world"
    }

    @Test
    fun `codex turn_completed is terminal and carries usage`() {
        val tracker = CodexItemTracker()
        val payload = Json.parseToJsonElement(
            """
            {
              "threadId": "thr-1",
              "usage": {
                "inputTokens": 10,
                "outputTokens": 20,
                "totalTokens": 30
              }
            }
            """.trimIndent(),
        )
        val events = CodexProtocolAdapter.translate(CodexMethods.TURN_COMPLETED, payload, "thr-1", tracker)
        events shouldHaveSize 1
        val ev = events.first().shouldBeInstanceOf<ExternalAgentEvent.TurnCompleted>()
        ev.usage["inputTokens"] shouldBe 10L
        ev.usage["outputTokens"] shouldBe 20L
        ev.usage["totalTokens"] shouldBe 30L
        CodexProtocolAdapter.isTerminal(ev) shouldBe true
    }

    @Test
    fun `codex ignores notifications for other threads`() {
        val tracker = CodexItemTracker()
        val payload = Json.parseToJsonElement(
            """
            {
              "threadId": "thr-OTHER",
              "item": { "id": "m", "type": "agent_message", "text": "hi" }
            }
            """.trimIndent(),
        )
        CodexProtocolAdapter.translate(CodexMethods.ITEM_UPDATED, payload, "thr-MINE", tracker).shouldBeEmpty()
    }

    // ===== Registry =====

    @Test
    fun `registry allows registering a synthetic provider without touching builtins`() {
        val registry = ExternalAgentProviderRegistry()
        val fake = FakeExternalProvider("fake-x")
        registry.register(fake)
        registry.resolve("fake-x") shouldBe fake
    }

    private fun envelope(type: String, properties: JsonObject) =
        OpencodeEventEnvelope(id = null, type = type, properties = properties)

    private class FakeExternalProvider(override val id: String) : ExternalAgentProvider {
        override val displayName: String = id
        override val processScope: ProcessScope = ProcessScope.PER_IDE

        override fun detectExecutable(): String? = null

        override suspend fun ensureRunning(
            project: com.intellij.openapi.project.Project,
            settings: dev.sweep.assistant.settings.SweepSettings,
        ): ProviderHandle = ProviderHandle(Unit)

        override suspend fun close(handle: ProviderHandle) {}

        override suspend fun createSession(
            handle: ProviderHandle,
            cwd: String,
            hints: SessionHints,
        ): String = "sess-fake"

        override suspend fun resumeSession(
            handle: ProviderHandle,
            remoteSessionId: String,
        ): ResumeResult = ResumeResult.NotFound

        override suspend fun sendUserMessage(
            handle: ProviderHandle,
            remoteSessionId: String,
            prompt: String,
            images: List<ExternalAgentImage>,
        ) = kotlinx.coroutines.flow.emptyFlow<ExternalAgentEvent>()

        override suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String) {}

        override suspend fun answerPermission(
            handle: ProviderHandle,
            remoteSessionId: String,
            permissionId: String,
            allow: Boolean,
        ) {}

        override suspend fun testConnection(handle: ProviderHandle): TestResult = TestResult.Ok("fake")
    }
}
