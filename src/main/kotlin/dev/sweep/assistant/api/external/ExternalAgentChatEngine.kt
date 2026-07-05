package dev.sweep.assistant.api.external

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.data.Message

/**
 * Project-level entry point that Stream.start() calls when the user has
 * selected an external agent provider (OpenCode / Codex). Fase 1 ships the
 * skeleton only — the real orchestration (process boot, remote-session
 * resolution, event → Message translation) arrives in Fase 4 once the
 * OpenCode and Codex providers exist.
 */
@Service(Service.Level.PROJECT)
class ExternalAgentChatEngine(
    private val project: Project,
) {
    private val logger = Logger.getInstance(ExternalAgentChatEngine::class.java)

    private val sessionStore: ExternalAgentSessionStore
        get() = ExternalAgentSessionStoreImpl.getInstance(project)

    private val registry: ExternalAgentProviderRegistry
        get() = ExternalAgentProviderRegistry.getInstance()

    /**
     * Drive one user turn against the selected external provider.
     *
     * Fase 1 stub: no provider is registered yet, so this simply logs and
     * returns. Fase 4 will (§7.1):
     *   1. Resolve provider from settings.chatProviderId.
     *   2. sessionStore.get(conversationId) → create or resume the remote session.
     *   3. provider.ensureRunning / provider.sendUserMessage(...)
     *   4. Consume Flow<ExternalAgentEvent>, translate to Message updates,
     *      and forward via onMessageUpdated.
     */
    suspend fun stream(
        conversationId: String,
        @Suppress("UNUSED_PARAMETER") finalMessages: List<Message>,
        @Suppress("UNUSED_PARAMETER") currentFilePath: String?,
        @Suppress("UNUSED_PARAMETER") systemPromptExtras: String,
        @Suppress("UNUSED_PARAMETER") onMessageUpdated: (Message) -> Unit,
    ) {
        logger.info("ExternalAgentChatEngine.stream() called for conversation=$conversationId (skeleton — no-op)")
    }

    /**
     * Fase 1 stub. Fase 4: look up the in-flight [ExternalAgentProvider] for
     * this conversation and call `provider.cancelCurrentTurn(...)`.
     */
    suspend fun cancel(conversationId: String) {
        logger.info("ExternalAgentChatEngine.cancel($conversationId) — skeleton, nothing to cancel")
    }

    /**
     * Called from Settings when the user swaps chat providers. Fase 4: cancel
     * in-flight turns and warn the UI that context won't cross over (plan §8.2).
     */
    suspend fun onProviderChanged(newProviderId: String) {
        logger.info("ExternalAgentChatEngine.onProviderChanged($newProviderId) — skeleton")
    }

    /**
     * Called from ChatHistory.deleteConversation() cleanup path (post-MVP —
     * best-effort forwarding to the remote provider). Fase 1: only removes the
     * local session row; the remote thread lives on until the provider prunes it.
     */
    fun forgetConversation(conversationId: String) {
        sessionStore.delete(conversationId)
    }

    companion object {
        fun getInstance(project: Project): ExternalAgentChatEngine =
            project.getService(ExternalAgentChatEngine::class.java)
    }
}
