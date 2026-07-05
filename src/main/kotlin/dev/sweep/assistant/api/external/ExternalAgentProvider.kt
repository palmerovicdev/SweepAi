package dev.sweep.assistant.api.external

import com.intellij.openapi.project.Project
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.flow.Flow

/**
 * Contract every external agent CLI (OpenCode, Codex, future Claude Code /
 * Cursor / ACP-based agents) must implement so the chat engine can drive them
 * uniformly.
 *
 * Providers are stateless singletons; per-project handles and per-conversation
 * remote sessions are threaded through the API.
 */
interface ExternalAgentProvider {
    /** Stable identifier persisted in settings and in the session store. */
    val id: String

    /** Human-friendly name shown in the settings dropdown. */
    val displayName: String

    /** Whether a single process is shared across the IDE or spawned per conversation. */
    val processScope: ProcessScope

    /** Best-effort absolute path lookup (PATH + common install dirs); null when not found. */
    fun detectExecutable(): String?

    // ===== Process lifecycle =====

    suspend fun ensureRunning(project: Project, settings: SweepSettings): ProviderHandle

    suspend fun close(handle: ProviderHandle)

    // ===== Remote session lifecycle =====

    suspend fun createSession(handle: ProviderHandle, cwd: String, hints: SessionHints): String

    suspend fun resumeSession(handle: ProviderHandle, remoteSessionId: String): ResumeResult

    // ===== Interaction =====

    suspend fun sendUserMessage(
        handle: ProviderHandle,
        remoteSessionId: String,
        prompt: String,
        images: List<ExternalAgentImage> = emptyList(),
    ): Flow<ExternalAgentEvent>

    suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String)

    suspend fun answerPermission(
        handle: ProviderHandle,
        remoteSessionId: String,
        permissionId: String,
        allow: Boolean,
    )

    // ===== Diagnostics =====

    suspend fun testConnection(handle: ProviderHandle): TestResult
}

/** Opaque per-project state a provider keeps between calls (process, HTTP client, JSON-RPC channel, …). */
data class ProviderHandle(val opaque: Any)

/** Provider-agnostic hints for session creation (agent profile, model override, …). */
data class SessionHints(
    val agent: String? = null,
    val model: String? = null,
)

/** Attached image payload for multimodal prompts (post-MVP). */
data class ExternalAgentImage(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExternalAgentImage) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

sealed interface ResumeResult {
    data class Ok(val restoredSessionId: String) : ResumeResult

    data object NotFound : ResumeResult

    data class Failed(val reason: String) : ResumeResult
}

sealed interface TestResult {
    data class Ok(val details: String) : TestResult

    data class Fail(val reason: String) : TestResult
}

/**
 * Codex spawns one process per conversation (simpler thread-lifecycle);
 * OpenCode runs a single `opencode serve` shared across the IDE with distinct
 * sessions per conversation.
 */
enum class ProcessScope {
    PER_CONVERSATION,
    PER_IDE,
}
