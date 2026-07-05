package dev.sweep.assistant.api.external

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.sweep.assistant.agent.SweepAgent
import dev.sweep.assistant.data.Annotations
import dev.sweep.assistant.data.CompletedToolCall
import dev.sweep.assistant.data.Message
import dev.sweep.assistant.data.MessageRole
import dev.sweep.assistant.data.ToolCall
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Fase 4 orchestrator. Called from `Stream.start()` when
 * `settings.chatProviderId` is `"opencode"` or `"codex"`.
 *
 * Flow (plan §7.1):
 *   1. Resolve the provider from the registry.
 *   2. `provider.ensureRunning(...)` → per-project handle.
 *   3. Consult the [ExternalAgentSessionStore] to resume an existing remote
 *      session; otherwise create a fresh one and persist the mapping.
 *   4. Extract the last user message text as the prompt.
 *   5. Consume `Flow<ExternalAgentEvent>`, translating deltas / tool calls
 *      / errors / completion into incremental [Message] updates fed to
 *      [onMessageUpdated] (the same callback the cloud path uses, so all UI
 *      merging, ChatHistory persistence and tool-call bookkeeping is reused).
 *
 * Cancellation goes through [cancel]; provider swaps are notified via
 * [onProviderChanged] which drops any in-flight turn.
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

    private val cancelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = ConcurrentHashMap<String, InFlight>()

    /**
     * Drive one user turn against the selected external provider. Runs on the
     * caller's coroutine (typically [dev.sweep.assistant.controllers.Stream]'s
     * streaming job) so that cancelling that job cancels the collect loop.
     */
    suspend fun stream(
        conversationId: String,
        finalMessages: List<Message>,
        currentFilePath: String?,
        systemPromptExtras: String,
        onMessageUpdated: (Message) -> Unit,
    ) {
        val settings = SweepSettings.getInstance()
        val providerId = settings.chatProviderId
        val provider = registry.resolve(providerId)
        if (provider == null) {
            emitTerminalError(
                "No external agent provider registered for id='$providerId'. Check Settings → Chat Provider.",
                onMessageUpdated,
            )
            return
        }

        val handle =
            try {
                provider.ensureRunning(project, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitTerminalError(
                    "Could not start ${provider.displayName}: ${e.message ?: e.javaClass.simpleName}. " +
                        "Check the executable path in Settings → Chat Provider.",
                    onMessageUpdated,
                )
                return
            }

        val cwd = project.basePath.orEmpty()
        val remoteSessionId =
            resolveRemoteSession(provider, handle, conversationId, cwd, settings, onMessageUpdated)
                ?: return

        val prompt = buildPrompt(finalMessages, systemPromptExtras, currentFilePath)
        if (prompt.isBlank()) {
            emitTerminalError("Empty prompt — nothing to send to ${provider.displayName}.", onMessageUpdated)
            return
        }

        var currentRemoteSessionId = remoteSessionId
        val inFlightRecord =
            InFlight(providerId = provider.id, provider = provider, handle = handle, remoteSessionId = currentRemoteSessionId)
        inFlight[conversationId] = inFlightRecord

        val contentBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        var latestAnnotations = Annotations()
        val toolCallsById = LinkedHashMap<String, ToolCall>()
        val agentSession = SweepAgent.getInstance(project).getSessionForConversation(conversationId)

        // Prime the assistant slot so downstream UI knows a turn is in flight.
        onMessageUpdated(Message(MessageRole.ASSISTANT, ""))

        try {
            val flow = provider.sendUserMessage(handle, currentRemoteSessionId, prompt)
            flow.collect { event ->
                when (event) {
                    is ExternalAgentEvent.RemoteSessionIdUpdated -> {
                        if (event.oldId == currentRemoteSessionId && event.newId != currentRemoteSessionId) {
                            logger.info(
                                "Remote session id for conv=$conversationId provider=${provider.id} " +
                                    "remapped ${event.oldId} → ${event.newId}",
                            )
                            currentRemoteSessionId = event.newId
                            sessionStore.put(
                                ExternalSessionRow(
                                    conversationId = conversationId,
                                    providerId = provider.id,
                                    remoteSessionId = event.newId,
                                    cwd = cwd,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                            inFlight[conversationId] = inFlightRecord.copy(remoteSessionId = event.newId)
                        }
                    }
                    is ExternalAgentEvent.TextDelta -> {
                        when (event.kind) {
                            TextKind.CONTENT -> contentBuilder.append(event.delta)
                            TextKind.THINKING -> {
                                thinkingBuilder.append(event.delta)
                                latestAnnotations =
                                    latestAnnotations.copy(thinking = thinkingBuilder.toString())
                            }
                        }
                        onMessageUpdated(renderAssistantMessage(contentBuilder, latestAnnotations, toolCallsById))
                    }
                    is ExternalAgentEvent.ToolCallStarted -> {
                        val synthetic =
                            ToolCall(
                                toolCallId = event.toolCallId,
                                toolName = event.toolName,
                                toolParameters = event.args.mapValues { (_, v) -> v?.toString().orEmpty() },
                                rawText = renderToolCallRaw(event.toolName, event.args),
                                fullyFormed = true,
                                mcpProperties = mapOf("executor" to provider.id),
                            )
                        toolCallsById[event.toolCallId] = synthetic
                        onMessageUpdated(renderAssistantMessage(contentBuilder, latestAnnotations, toolCallsById))
                    }
                    is ExternalAgentEvent.ToolCallCompleted -> {
                        val known = toolCallsById[event.toolCallId]
                        val completed =
                            CompletedToolCall(
                                toolCallId = event.toolCallId,
                                toolName = known?.toolName ?: event.toolCallId,
                                resultString = event.output,
                                status = event.ok,
                                isMcp = known?.isMcp ?: false,
                                mcpProperties =
                                    (known?.mcpProperties ?: emptyMap()) +
                                        mapOf("executor" to provider.id),
                            )
                        agentSession.recordExternalCompletion(completed)
                    }
                    is ExternalAgentEvent.PermissionRequested -> {
                        val allowed = promptPermission(provider.displayName, event.toolName, event.args)
                        runCatching {
                            provider.answerPermission(handle, currentRemoteSessionId, event.permissionId, allow = allowed)
                        }
                    }
                    is ExternalAgentEvent.Error -> {
                        contentBuilder.append(
                            (if (contentBuilder.isNotEmpty()) "\n\n" else "") +
                                "**${provider.displayName} error:** ${event.reason}",
                        )
                        latestAnnotations =
                            latestAnnotations.copy(
                                stopStreaming = "stop",
                                completionTime = System.currentTimeMillis(),
                            )
                        onMessageUpdated(renderAssistantMessage(contentBuilder, latestAnnotations, toolCallsById))
                        surfaceErrorToast(provider.displayName, event.reason)
                    }
                    is ExternalAgentEvent.TurnCompleted -> {
                        latestAnnotations =
                            latestAnnotations.copy(
                                stopStreaming = "stop",
                                completionTime = System.currentTimeMillis(),
                            )
                        onMessageUpdated(renderAssistantMessage(contentBuilder, latestAnnotations, toolCallsById))
                    }
                }
            }

            // Flow ended without an explicit TurnCompleted (e.g. provider closed
            // the channel after an Error). Make sure the message is marked as
            // stopped so the UI removes the streaming cursor.
            if (latestAnnotations.stopStreaming != "stop") {
                latestAnnotations =
                    latestAnnotations.copy(
                        stopStreaming = "stop",
                        completionTime = System.currentTimeMillis(),
                    )
                onMessageUpdated(renderAssistantMessage(contentBuilder, latestAnnotations, toolCallsById))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                latestAnnotations =
                    latestAnnotations.copy(
                        stopStreaming = "stop",
                        completionTime = System.currentTimeMillis(),
                    )
                onMessageUpdated(renderAssistantMessage(contentBuilder, latestAnnotations, toolCallsById))
            }
            throw e
        } catch (e: Throwable) {
            logger.warn(
                "External agent stream failed (provider=${provider.id}, conv=$conversationId): ${e.message}",
                e,
            )
            emitTerminalError(
                "${provider.displayName} stream failed: ${e.message ?: e.javaClass.simpleName}",
                onMessageUpdated,
                contentBuilder,
                latestAnnotations,
                toolCallsById,
            )
        } finally {
            inFlight.remove(conversationId, inFlightRecord)
        }
    }

    /**
     * Cancel the in-flight turn for [conversationId], if any. Fires
     * `provider.cancelCurrentTurn(...)` best-effort — the local collector is
     * cancelled by whoever cancelled the enclosing coroutine (typically
     * `Stream.stop()` → `streamingJob.cancel()`).
     */
    suspend fun cancel(conversationId: String) {
        val record = inFlight[conversationId] ?: return
        try {
            record.provider.cancelCurrentTurn(record.handle, record.remoteSessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "cancelCurrentTurn failed for provider=${record.providerId}, conv=$conversationId: ${e.message}",
            )
        }
    }

    /**
     * Fire-and-forget variant of [cancel] for call sites that don't have a
     * coroutine scope (e.g. `Stream.stop()` running on a pooled thread).
     */
    fun cancelBlocking(conversationId: String) {
        cancelScope.launch { cancel(conversationId) }
    }

    /**
     * Called from Settings when the user swaps chat providers. Cancels any
     * in-flight turn (the new provider has no context on the previous one) —
     * plan §8.2.
     */
    suspend fun onProviderChanged(newProviderId: String) {
        val snapshot = inFlight.toMap()
        snapshot.forEach { (conversationId, record) ->
            if (record.providerId != newProviderId) {
                logger.info(
                    "Chat provider changed to '$newProviderId' — cancelling in-flight turn for conv=$conversationId (was ${record.providerId})",
                )
                try {
                    record.provider.cancelCurrentTurn(record.handle, record.remoteSessionId)
                } catch (e: Exception) {
                    logger.warn("cancelCurrentTurn on provider swap failed: ${e.message}")
                }
                inFlight.remove(conversationId, record)
            }
        }
    }

    /**
     * Called from ChatHistory.deleteConversation() cleanup path (post-MVP —
     * best-effort forwarding to the remote provider). Fase 1: only removes the
     * local session row; the remote thread lives on until the provider prunes it.
     */
    fun forgetConversation(conversationId: String) {
        sessionStore.delete(conversationId)
    }

    /**
     * "Compact / new session" affordance (plan §18 risk row on long threads).
     * Drops the local mapping so the next user turn triggers `createSession`
     * against the provider. The old remote thread is left alone.
     */
    fun newRemoteSession(conversationId: String) {
        sessionStore.delete(conversationId)
    }

    // ===== Internals =====

    private suspend fun resolveRemoteSession(
        provider: ExternalAgentProvider,
        handle: ProviderHandle,
        conversationId: String,
        cwd: String,
        settings: SweepSettings,
        onMessageUpdated: (Message) -> Unit,
    ): String? {
        val existing = sessionStore.get(conversationId)
        if (existing != null && existing.providerId == provider.id) {
            when (val res = provider.resumeSession(handle, existing.remoteSessionId)) {
                is ResumeResult.Ok -> return res.restoredSessionId
                is ResumeResult.NotFound -> {
                    logger.info(
                        "Previous ${provider.displayName} session ${existing.remoteSessionId} not found — starting a new one",
                    )
                }
                is ResumeResult.Failed -> {
                    logger.warn(
                        "Resume failed for ${provider.displayName} session ${existing.remoteSessionId}: ${res.reason} — starting a new one",
                    )
                }
            }
        } else if (existing != null) {
            logger.info(
                "Existing session ${existing.remoteSessionId} was for provider=${existing.providerId}; current is ${provider.id}. Creating a fresh session.",
            )
        }

        val hints =
            SessionHints(
                agent = if (provider.id == "opencode") settings.opencodeAgent.takeIf { it.isNotBlank() } else null,
                model = when (provider.id) {
                    "codex" -> settings.codexModel.takeIf { it.isNotBlank() }
                    "opencode" -> settings.opencodeModel.takeIf { it.isNotBlank() }
                    else -> null
                },
            )

        val created =
            try {
                provider.createSession(handle, cwd, hints)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitTerminalError(
                    "Could not create a ${provider.displayName} session: ${e.message ?: e.javaClass.simpleName}.",
                    onMessageUpdated,
                )
                return null
            }

        sessionStore.put(
            ExternalSessionRow(
                conversationId = conversationId,
                providerId = provider.id,
                remoteSessionId = created,
                cwd = cwd,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return created
    }

    private fun buildPrompt(
        finalMessages: List<Message>,
        systemPromptExtras: String,
        currentFilePath: String?,
    ): String {
        val userText = finalMessages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val preambleParts = mutableListOf<String>()
        if (systemPromptExtras.isNotBlank()) preambleParts.add(systemPromptExtras.trim())
        if (!currentFilePath.isNullOrBlank()) preambleParts.add("Currently open file: $currentFilePath")
        return if (preambleParts.isEmpty()) {
            userText
        } else {
            preambleParts.joinToString("\n\n") + "\n\n" + userText
        }
    }

    private fun renderAssistantMessage(
        contentBuilder: StringBuilder,
        annotations: Annotations,
        toolCallsById: Map<String, ToolCall>,
    ): Message {
        val toolCalls = toolCallsById.values.toMutableList()
        val ann = annotations.copy(toolCalls = toolCalls)
        return Message(role = MessageRole.ASSISTANT, content = contentBuilder.toString(), annotations = ann)
    }

    private fun renderToolCallRaw(toolName: String, args: Map<String, Any?>): String {
        val body =
            args.entries.joinToString(", ") { (k, v) ->
                val rendered = v?.toString()?.take(200)?.replace('\n', ' ') ?: "null"
                "$k=$rendered"
            }
        return "$toolName($body)"
    }

    private fun promptPermission(
        providerName: String,
        toolName: String,
        args: Map<String, Any?>,
    ): Boolean {
        val summary =
            args.entries.take(6).joinToString(", ") { (k, v) ->
                val rendered = v?.toString()?.take(120)?.replace('\n', ' ') ?: "null"
                "$k=$rendered"
            }
        val body =
            buildString {
                append("$providerName wants to run:")
                append("\n\n")
                append(toolName)
                if (summary.isNotBlank()) append("($summary)")
                append("\n\nAllow this tool to run?")
            }
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        ApplicationManager.getApplication().invokeAndWait {
            val choice =
                Messages.showYesNoDialog(
                    project,
                    body,
                    "$providerName permission request",
                    "Allow",
                    "Deny",
                    Messages.getQuestionIcon(),
                )
            result.set(choice == Messages.YES)
        }
        return result.get()
    }

    private fun surfaceErrorToast(providerName: String, reason: String) {
        val (title, message, notificationType) =
            when {
                reason.contains("codex login", ignoreCase = true) ||
                    (providerName.equals("Codex", ignoreCase = true) &&
                        reason.contains("auth", ignoreCase = true)) ->
                    Triple(
                        "Codex authentication needed",
                        "Run `codex login` in a terminal, then retry.",
                        NotificationType.WARNING,
                    )
                reason.contains("opencode auth", ignoreCase = true) ||
                    (providerName.equals("OpenCode", ignoreCase = true) &&
                        reason.contains("401")) ->
                    Triple(
                        "OpenCode authentication needed",
                        "Run `opencode auth login` in a terminal, then retry.",
                        NotificationType.WARNING,
                    )
                reason.contains("Could not start", ignoreCase = true) ->
                    Triple(
                        "$providerName not found",
                        reason,
                        NotificationType.ERROR,
                    )
                else ->
                    Triple(
                        "$providerName error",
                        reason,
                        NotificationType.ERROR,
                    )
            }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Error Notifications")
                .createNotification(title, message, notificationType)
                .notify(project)
        }
    }

    private fun emitTerminalError(
        reason: String,
        onMessageUpdated: (Message) -> Unit,
        contentBuilder: StringBuilder = StringBuilder(),
        baseAnnotations: Annotations = Annotations(),
        toolCallsById: Map<String, ToolCall> = emptyMap(),
        providerName: String? = null,
    ) {
        if (contentBuilder.isNotEmpty()) contentBuilder.append("\n\n")
        contentBuilder.append("**Error:** ").append(reason)
        val ann =
            baseAnnotations.copy(
                stopStreaming = "stop",
                completionTime = System.currentTimeMillis(),
            )
        onMessageUpdated(renderAssistantMessage(contentBuilder, ann, toolCallsById))
        surfaceErrorToast(providerName ?: "Sweep external chat", reason)
    }

    private data class InFlight(
        val providerId: String,
        val provider: ExternalAgentProvider,
        val handle: ProviderHandle,
        val remoteSessionId: String,
    )

    companion object {
        fun getInstance(project: Project): ExternalAgentChatEngine =
            project.getService(ExternalAgentChatEngine::class.java)
    }
}
