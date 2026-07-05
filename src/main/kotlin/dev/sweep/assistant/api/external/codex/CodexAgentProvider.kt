package dev.sweep.assistant.api.external.codex

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sweep.assistant.api.external.ExternalAgentEvent
import dev.sweep.assistant.api.external.ExternalAgentImage
import dev.sweep.assistant.api.external.ExternalAgentProvider
import dev.sweep.assistant.api.external.ProcessScope
import dev.sweep.assistant.api.external.ProviderHandle
import dev.sweep.assistant.api.external.ResumeResult
import dev.sweep.assistant.api.external.SessionHints
import dev.sweep.assistant.api.external.TestResult
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Codex (`codex app-server --listen stdio://`) provider — plan §10.
 *
 * Lifecycle
 *   - Codex is [ProcessScope.PER_CONVERSATION]: every remote session (thread)
 *     gets its own subprocess. Killing the subprocess frees Codex's per-thread
 *     memory when the user closes the conversation (plan §8.3).
 *   - [ensureRunning] returns a project-scoped [CodexRuntime] that holds the
 *     `threadId → CodexSubprocessCtx` map. [createSession] and [resumeSession]
 *     spawn a fresh subprocess, run the `initialize` handshake, then either
 *     `thread/start` or `thread/resume`.
 *   - [sendUserMessage] fires `turn/start` and streams notifications from the
 *     subprocess's shared notification flow, terminating on `turn/completed`
 *     or an unrecoverable error.
 *   - [close] tears down every subprocess in the runtime map.
 *
 * Auth (plan §10.4): Codex owns its own OAuth / `OPENAI_API_KEY` flow via
 * `codex login`. Sweep does not attempt to authenticate on the user's behalf;
 * an auth failure surfaces as an [ExternalAgentEvent.Error] with a hint to run
 * `codex login` in a terminal.
 */
class CodexAgentProvider : ExternalAgentProvider {
    override val id: String = "codex"
    override val displayName: String = "Codex"
    override val processScope: ProcessScope = ProcessScope.PER_CONVERSATION

    private val runtimeMutex = Mutex()
    private val runtimes = ConcurrentHashMap<String, CodexRuntime>()

    override fun detectExecutable(): String? {
        val settings = SweepSettings.getInstance()
        val requested = settings.codexCommand.ifBlank { "codex" }
        if (requested.contains(File.separatorChar)) {
            val f = File(requested)
            return if (f.isFile && f.canExecute()) f.absolutePath else null
        }
        val path = loadPathEnv()
        return CodexProcess.resolveExecutableOnPath(requested, path)
            ?: FALLBACK_INSTALL_DIRS
                .map { File(File(it), if (isWindows) "codex.exe" else "codex") }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
    }

    override suspend fun ensureRunning(project: Project, settings: SweepSettings): ProviderHandle {
        val key = resolveProjectDirectory(project)
        val runtime =
            runtimeMutex.withLock {
                runtimes.getOrPut(key) { CodexRuntime(projectDirectory = key) }
            }
        return ProviderHandle(runtime)
    }

    override suspend fun close(handle: ProviderHandle) {
        val runtime = handle.opaque as? CodexRuntime ?: return
        runtimeMutex.withLock { runtimes.entries.removeAll { it.value === runtime } }
        runtime.close()
    }

    override suspend fun createSession(handle: ProviderHandle, cwd: String, hints: SessionHints): String {
        val runtime = handle.opaque as CodexRuntime
        val settings = SweepSettings.getInstance()
        val ctx = spawnAndInitialize(settings)
        return try {
            val params =
                CodexThreadStartParams(
                    cwd = cwd,
                    model = hints.model?.takeIf { it.isNotBlank() } ?: settings.codexModel.takeIf { it.isNotBlank() },
                    approvalPolicy = settings.codexApprovalPolicy.takeIf { it.isNotBlank() },
                    sandbox = settings.codexSandbox.takeIf { it.isNotBlank() },
                )
            val thread =
                ctx.client.sendTypedRequest(
                    method = CodexMethods.THREAD_START,
                    params = params,
                    paramsSerializer = CodexThreadStartParams.serializer(),
                    resultSerializer = CodexThread.serializer(),
                )
            ctx.threadId = thread.threadId
            runtime.attach(thread.threadId, ctx)
            thread.threadId
        } catch (e: Throwable) {
            ctx.close()
            throw e
        }
    }

    override suspend fun resumeSession(handle: ProviderHandle, remoteSessionId: String): ResumeResult {
        val runtime = handle.opaque as CodexRuntime
        // Fast path: a live subprocess already owns this thread.
        runtime.get(remoteSessionId)?.let { return ResumeResult.Ok(remoteSessionId) }

        val settings = SweepSettings.getInstance()
        val ctx =
            try {
                spawnAndInitialize(settings)
            } catch (e: Exception) {
                return ResumeResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        return try {
            ctx.client.sendTypedRequest(
                method = CodexMethods.THREAD_RESUME,
                params = CodexThreadResumeParams(threadId = remoteSessionId, excludeTurns = true),
                paramsSerializer = CodexThreadResumeParams.serializer(),
                resultSerializer = CodexThread.serializer(),
            )
            ctx.threadId = remoteSessionId
            runtime.attach(remoteSessionId, ctx)
            ResumeResult.Ok(remoteSessionId)
        } catch (e: CodexRpcException) {
            ctx.close()
            // Codex commonly returns "thread not found" as -32602 or a message
            // hinting at a missing thread; treat both as NotFound so the engine
            // creates a fresh session rather than failing the whole turn.
            val msg = e.message.orEmpty().lowercase()
            if (e.code == -32602 || msg.contains("not found") || msg.contains("unknown thread")) {
                ResumeResult.NotFound
            } else {
                ResumeResult.Failed("Codex RPC ${e.code}: ${e.message}")
            }
        } catch (e: Exception) {
            ctx.close()
            ResumeResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun sendUserMessage(
        handle: ProviderHandle,
        remoteSessionId: String,
        prompt: String,
        images: List<ExternalAgentImage>,
    ): Flow<ExternalAgentEvent> {
        val runtime = handle.opaque as CodexRuntime
        val ctx =
            runtime.get(remoteSessionId)
                ?: error("Codex has no active subprocess for thread=$remoteSessionId; call resumeSession first")
        val tracker = CodexItemTracker()

        return channelFlow {
            val turnJob = launch {
                try {
                    ctx.client.sendTypedRequest(
                        method = CodexMethods.TURN_START,
                        params =
                            CodexTurnStartParams(
                                threadId = remoteSessionId,
                                input = listOf(CodexTurnInputPart(type = "text", text = prompt)),
                            ),
                        paramsSerializer = CodexTurnStartParams.serializer(),
                        resultSerializer = kotlinx.serialization.json.JsonObject.serializer(),
                    )
                } catch (e: CodexRpcException) {
                    trySend(
                        ExternalAgentEvent.Error(
                            reason = mapAuthOrRpcError(e),
                            recoverable = false,
                        ),
                    )
                    close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trySend(
                        ExternalAgentEvent.Error(
                            reason = "Failed to send prompt to Codex: ${e.message ?: e.javaClass.simpleName}",
                            recoverable = false,
                        ),
                    )
                    close()
                }
            }

            try {
                ctx.notifications
                    .transformWhile { (method, params) ->
                        val events = CodexProtocolAdapter.translate(method, params, remoteSessionId, tracker)
                        var terminal = false
                        for (e in events) {
                            emit(e)
                            if (CodexProtocolAdapter.isTerminal(e)) terminal = true
                        }
                        !terminal
                    }
                    .collect { send(it) }
            } finally {
                turnJob.cancel()
            }

            awaitClose { }
        }
    }

    override suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String) {
        val runtime = handle.opaque as CodexRuntime
        val ctx = runtime.get(remoteSessionId) ?: return
        try {
            ctx.client.sendRequest(
                method = CodexMethods.TURN_INTERRUPT,
                params =
                    defaultJson.encodeToJsonElement(
                        CodexTurnInterruptParams.serializer(),
                        CodexTurnInterruptParams(threadId = remoteSessionId),
                    ),
                timeoutMs = 5_000L,
            )
        } catch (e: Exception) {
            // Fallback (plan §7.2): tear the subprocess down. The engine will
            // resume the thread lazily on the next user message.
            logger.warn("Codex turn/interrupt failed (${e.message}); tearing down subprocess for thread=$remoteSessionId")
            runtime.detach(remoteSessionId)?.close()
        }
    }

    override suspend fun answerPermission(
        handle: ProviderHandle,
        remoteSessionId: String,
        permissionId: String,
        allow: Boolean,
    ) {
        // MVP: Codex answers permissions via its own approval UI stack; the
        // JSON-RPC channel's server-initiated permission requests are auto
        // rejected by [CodexJsonRpcClient.autoRejectServerRequest]. Once the
        // engine surfaces those in Sweep's UI we route the answer through a
        // client→server response instead. Keeping the interface no-op keeps the
        // plumbing in place without leaking a half-wired path.
        logger.info(
            "answerPermission is a no-op in Codex MVP (thread=$remoteSessionId, permission=$permissionId, allow=$allow)",
        )
    }

    override suspend fun testConnection(handle: ProviderHandle): TestResult {
        val settings = SweepSettings.getInstance()
        val ctx =
            try {
                spawnAndInitialize(settings)
            } catch (e: CodexStartupException) {
                return TestResult.Fail(e.message ?: "codex failed to start")
            } catch (e: CodexRpcException) {
                return TestResult.Fail(mapAuthOrRpcError(e))
            } catch (e: Exception) {
                return TestResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        return try {
            TestResult.Ok("codex app-server responded to initialize")
        } finally {
            ctx.close()
        }
    }

    // ===== Internals =====

    private suspend fun spawnAndInitialize(settings: SweepSettings): CodexSubprocessCtx {
        val command = settings.codexCommand.ifBlank { "codex" }
        val userExtraArgs = settings.codexExtraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        val configOverrides = buildCodexConfigOverrides(settings)
        val process =
            withContext(Dispatchers.IO) {
                CodexProcess.start(
                    command = command,
                    extraArgs = userExtraArgs,
                    preSubcommandArgs = configOverrides,
                )
            }

        val notifications = MutableSharedFlow<Pair<String, kotlinx.serialization.json.JsonElement?>>(
            replay = 0,
            extraBufferCapacity = 256,
        )
        val client =
            CodexJsonRpcClient(
                process = process.process,
                onNotification = { method, params ->
                    if (!notifications.tryEmit(method to params)) {
                        // Buffer overflow — extremely unlikely for a single
                        // consumer. Warn and drop.
                        logger.warn("Dropped Codex notification $method: notifications buffer full")
                    }
                },
            )
        client.start()

        try {
            val params =
                CodexInitializeParams(
                    clientCapabilities =
                        buildJsonObject {
                            // Sweep does not host tools in MVP (plan §7.5, §17).
                            put("dynamicTools", JsonPrimitive(false))
                        },
                )
            client.sendTypedRequest(
                method = CodexMethods.INITIALIZE,
                params = params,
                paramsSerializer = CodexInitializeParams.serializer(),
                resultSerializer = CodexInitializeResult.serializer(),
                timeoutMs = INITIALIZE_TIMEOUT_MS,
            )
        } catch (e: Throwable) {
            client.close()
            process.close()
            throw e
        }
        return CodexSubprocessCtx(process = process, client = client, notifications = notifications)
    }

    /**
     * Build the `-c key=value` config overrides fed to Codex before the
     * `app-server` subcommand. These are how the CLI accepts ad-hoc TOML
     * overrides at spawn time; using them avoids requiring users to hand-edit
     * `~/.codex/config.toml` just to change reasoning effort per project.
     */
    private fun buildCodexConfigOverrides(settings: SweepSettings): List<String> {
        val overrides = mutableListOf<String>()
        val effort = settings.codexReasoningEffort.trim()
        if (effort.isNotEmpty()) {
            overrides += "-c"
            overrides += "model_reasoning_effort=\"$effort\""
        }
        when (settings.codexThinking.trim()) {
            "hidden" -> {
                overrides += "-c"
                overrides += "hide_agent_reasoning=true"
            }
            "shown" -> {
                overrides += "-c"
                overrides += "hide_agent_reasoning=false"
            }
        }
        return overrides
    }

    private fun mapAuthOrRpcError(e: CodexRpcException): String {
        val msg = e.message.orEmpty().lowercase()
        return if (
            msg.contains("auth") ||
            msg.contains("openai_api_key") ||
            msg.contains("unauthorized") ||
            msg.contains("login")
        ) {
            "Codex rejected the request — auth is missing or expired. Run `codex login` in a terminal."
        } else {
            "Codex RPC error ${e.code}: ${e.message}"
        }
    }

    private fun resolveProjectDirectory(project: Project): String {
        val base = project.basePath ?: error("Project has no basePath — cannot route Codex")
        return try {
            Paths.get(base).toRealPath().toString()
        } catch (_: Exception) {
            File(base).absolutePath
        }
    }

    private fun loadPathEnv(): String? =
        try {
            com.intellij.util.EnvironmentUtil.getEnvironmentMap()["PATH"]
        } catch (_: Throwable) {
            System.getenv("PATH")
        }

    companion object {
        private val logger = Logger.getInstance(CodexAgentProvider::class.java)

        private const val INITIALIZE_TIMEOUT_MS = 30_000L

        private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        private val FALLBACK_INSTALL_DIRS: List<String> =
            if (isWindows) {
                listOfNotNull(
                    System.getenv("LOCALAPPDATA")?.let { "$it\\codex\\bin" },
                    System.getenv("APPDATA")?.let { "$it\\npm" },
                    System.getenv("USERPROFILE")?.let { "$it\\.codex\\bin" },
                )
            } else {
                val home = System.getProperty("user.home") ?: ""
                listOf(
                    "$home/.local/bin",
                    "$home/.codex/bin",
                    "$home/.npm-global/bin",
                    "$home/.bun/bin",
                    "/opt/homebrew/bin",
                    "/usr/local/bin",
                )
            }
    }
}

/**
 * Per-project state held under [ProviderHandle]. Owns the map of live Codex
 * subprocesses keyed by threadId.
 */
class CodexRuntime(
    val projectDirectory: String,
) : AutoCloseable {
    private val subprocesses = ConcurrentHashMap<String, CodexSubprocessCtx>()

    fun attach(threadId: String, ctx: CodexSubprocessCtx) {
        val existing = subprocesses.put(threadId, ctx)
        existing?.close()
    }

    fun detach(threadId: String): CodexSubprocessCtx? = subprocesses.remove(threadId)

    fun get(threadId: String): CodexSubprocessCtx? {
        val ctx = subprocesses[threadId] ?: return null
        if (!ctx.process.isAlive()) {
            subprocesses.remove(threadId)
            ctx.close()
            return null
        }
        return ctx
    }

    override fun close() {
        val snapshot = subprocesses.values.toList()
        subprocesses.clear()
        snapshot.forEach { runCatching { it.close() } }
    }
}

class CodexSubprocessCtx(
    val process: CodexProcess,
    val client: CodexJsonRpcClient,
    val notifications: MutableSharedFlow<Pair<String, kotlinx.serialization.json.JsonElement?>>,
) : AutoCloseable {
    @Volatile
    var threadId: String? = null

    override fun close() {
        runCatching { client.close() }
        runCatching { process.close() }
    }
}
