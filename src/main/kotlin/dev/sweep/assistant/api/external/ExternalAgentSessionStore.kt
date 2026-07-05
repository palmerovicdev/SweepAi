package dev.sweep.assistant.api.external

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.sweep.assistant.services.ChatHistory

/**
 * Persistence contract for the `conversationId → (providerId, remoteSessionId)`
 * mapping. Backed by SQLite via [ChatHistory] so it shares the plugin's chat
 * history DB — see the `external_agent_session` table.
 */
interface ExternalAgentSessionStore {
    fun get(conversationId: String): ExternalSessionRow?

    fun put(row: ExternalSessionRow)

    fun delete(conversationId: String)
}

data class ExternalSessionRow(
    val conversationId: String,
    val providerId: String,
    val remoteSessionId: String,
    val cwd: String,
    val createdAt: Long,
)

@Service(Service.Level.PROJECT)
class ExternalAgentSessionStoreImpl(
    private val project: Project,
) : ExternalAgentSessionStore {
    private val history get() = ChatHistory.getInstance(project)

    override fun get(conversationId: String): ExternalSessionRow? =
        history.getExternalAgentSession(conversationId)?.toExternal()

    override fun put(row: ExternalSessionRow) {
        history.putExternalAgentSession(
            ChatHistory.ExternalAgentSessionRow(
                conversationId = row.conversationId,
                providerId = row.providerId,
                remoteSessionId = row.remoteSessionId,
                cwd = row.cwd,
                createdAt = row.createdAt,
            ),
        )
    }

    override fun delete(conversationId: String) {
        history.deleteExternalAgentSession(conversationId)
    }

    companion object {
        fun getInstance(project: Project): ExternalAgentSessionStore =
            project.getService(ExternalAgentSessionStoreImpl::class.java)
    }
}

private fun ChatHistory.ExternalAgentSessionRow.toExternal(): ExternalSessionRow =
    ExternalSessionRow(
        conversationId = conversationId,
        providerId = providerId,
        remoteSessionId = remoteSessionId,
        cwd = cwd,
        createdAt = createdAt,
    )
