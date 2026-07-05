package dev.sweep.assistant.api.external.codex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one `codex app-server --listen stdio://` subprocess. Codex is
 * `PER_CONVERSATION` (plan §8.3) — the caller spawns one instance per remote
 * thread, then hands the process to a [CodexJsonRpcClient] for framing.
 *
 * Twins the OpenCode process wrapper but does not parse stdout: everything on
 * stdout is JSON-RPC and belongs to the client. Stderr is drained on a pooled
 * thread and mirrored into IntelliJ's log so a broken auth surface leaves a
 * usable trail.
 */
class CodexProcess private constructor(
    val process: Process,
    private val executablePath: String,
) : AutoCloseable {
    private val closed = AtomicReference(false)

    fun isAlive(): Boolean = process.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.warn("Error tearing down codex app-server ($executablePath): ${e.message}")
        }
    }

    companion object {
        private val logger = Logger.getInstance(CodexProcess::class.java)

        /**
         * Spawn `codex app-server --listen stdio://` with an env inherited from
         * the IDE (login PATH, `OPENAI_API_KEY`, `CODEX_HOME`, …). Throws
         * [CodexStartupException] if the executable is missing; the caller is
         * expected to surface that as an actionable message (plan §15).
         */
        @Throws(CodexStartupException::class)
        fun start(
            command: String,
            extraArgs: List<String>,
        ): CodexProcess {
            val env = loadEnv().toMutableMap()
            val resolvedExecutable = resolveExecutableOnPath(command, env["PATH"])
                ?: throw CodexStartupException(
                    "codex executable not found. Configure Settings → Sweep → Chat Provider → Codex → Executable, " +
                        "or install it with `npm i -g @openai/codex`.",
                )

            val commandLine = buildList {
                add(resolvedExecutable)
                add("app-server")
                add("--listen")
                add("stdio://")
                addAll(extraArgs)
            }

            val pb = ProcessBuilder(commandLine)
            pb.environment().apply {
                clear()
                putAll(env)
            }

            logger.info("Starting codex app-server: ${commandLine.joinToString(" ")}")

            val process = try {
                pb.start()
            } catch (e: Exception) {
                throw CodexStartupException("Failed to launch $resolvedExecutable app-server: ${e.message}", e)
            }

            // stderr drain — never parsed, only logged so `codex login` hints
            // and rustc-style panics land in idea.log.
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            logger.info("codex[stderr]: $line")
                        }
                    }
                } catch (_: Exception) {
                    // Process ended.
                }
            }

            return CodexProcess(process = process, executablePath = resolvedExecutable)
        }

        /**
         * Load a PATH-rich environment. Mirrors [OpencodeProcess.loadEnv]:
         * prefer IntelliJ's `EnvironmentUtil` (already sourced from the user's
         * login shell on macOS/Linux) and fall back to a login+interactive
         * shell if unavailable.
         */
        private fun loadEnv(): Map<String, String> {
            try {
                val env = com.intellij.util.EnvironmentUtil.getEnvironmentMap()
                if (env.isNotEmpty()) return env
            } catch (_: Throwable) {
            }
            return loadLoginShellEnv()
        }

        private fun loadLoginShellEnv(): Map<String, String> {
            val shell = System.getenv("SHELL") ?: "/bin/zsh"
            val shellName = shell.substringAfterLast('/')
            val args = when (shellName) {
                "zsh", "bash" -> arrayOf(shell, "-l", "-i", "-c", "env -0")
                else -> arrayOf(shell, "-l", "-c", "env -0")
            }
            return try {
                val p = ProcessBuilder(*args).redirectErrorStream(true).start()
                val bytes = p.inputStream.readAllBytes()
                if (p.waitFor() != 0) return System.getenv()
                val text = bytes.toString(StandardCharsets.UTF_8)
                val out = LinkedHashMap<String, String>()
                text.split(Char(0)).forEach { entry ->
                    if (entry.isNotEmpty()) {
                        val idx = entry.indexOf('=')
                        if (idx > 0) out[entry.substring(0, idx)] = entry.substring(idx + 1)
                    }
                }
                if (out.isEmpty()) System.getenv() else out
            } catch (_: Exception) {
                System.getenv()
            }
        }

        /**
         * Resolve an executable name against the given PATH (or absolute path
         * if the caller already supplied one). Duplicated from OpencodeProcess
         * to keep the two providers decoupled.
         */
        fun resolveExecutableOnPath(exe: String, path: String?): String? {
            if (exe.contains(File.separatorChar)) {
                val f = File(exe)
                return if (f.isFile && f.canExecute()) f.absolutePath else null
            }
            if (path.isNullOrEmpty()) return null
            for (dir in path.split(File.pathSeparatorChar)) {
                if (dir.isEmpty()) continue
                val cand = File(dir, exe)
                if (cand.isFile && cand.canExecute()) return cand.absolutePath
            }
            return null
        }
    }
}

class CodexStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
