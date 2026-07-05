package dev.sweep.assistant.api.external.opencode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one `opencode serve` subprocess. Behaviour follows
 * `LocalAutocompleteServerManager.startServerProcess` (env from IntelliJ's
 * `EnvironmentUtil`, stderr drained on a pooled thread, health-poll loop) plus
 * two OpenCode-specific twists:
 *
 *  1. `opencode serve` has no `--port 0` mode, so we pre-allocate an ephemeral
 *     port with `ServerSocket(0)` and pass it explicitly.
 *  2. If we auto-start the server we generate a random `OPENCODE_SERVER_PASSWORD`
 *     so a stray process on the host can't drive it. That password is exported
 *     to the child env and applied as HTTP Basic auth by [OpencodeHttpClient].
 *
 * A single instance is shared per-IDE; if the user configured
 * `settings.opencodeBaseUrl` the provider skips this class entirely.
 */
class OpencodeProcess private constructor(
    private val process: Process,
    val baseUrl: String,
    val authHeader: String?,
    private val executablePath: String,
    private val portValue: Int,
) : AutoCloseable {
    private val closed = AtomicReference(false)

    val port: Int get() = portValue

    fun isAlive(): Boolean = process.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.warn("Error tearing down opencode serve ($executablePath): ${e.message}")
        }
    }

    companion object {
        private val logger = Logger.getInstance(OpencodeProcess::class.java)

        private const val START_TIMEOUT_MS = 30_000L
        private const val LISTEN_LINE_PREFIX = "opencode server listening on "

        /**
         * Spawn `opencode serve` on a private ephemeral port and wait until the
         * server announces the listen URL on stdout. Throws
         * [OpencodeStartupException] on any failure (executable missing, exit
         * before startup, no listen line within 30 s).
         */
        @Throws(OpencodeStartupException::class)
        fun start(
            command: String,
            extraArgs: List<String>,
        ): OpencodeProcess {
            val env = loadEnv().toMutableMap()
            val resolvedExecutable = resolveExecutableOnPath(command, env["PATH"])
                ?: throw OpencodeStartupException(
                    "opencode executable not found. Configure Settings → Sweep → Chat Provider → OpenCode → Executable.",
                )

            val port = allocateEphemeralPort()
            val password = generatePassword()
            env["OPENCODE_SERVER_PASSWORD"] = password
            val username = env["OPENCODE_SERVER_USERNAME"] ?: "opencode"

            val commandLine = buildList {
                add(resolvedExecutable)
                add("serve")
                add("--port")
                add(port.toString())
                add("--hostname")
                add("127.0.0.1")
                addAll(extraArgs)
            }

            val pb = ProcessBuilder(commandLine)
            pb.environment().apply {
                clear()
                putAll(env)
            }

            logger.info("Starting opencode serve: ${commandLine.joinToString(" ")} (port $port)")

            val process = try {
                pb.start()
            } catch (e: Exception) {
                throw OpencodeStartupException("Failed to launch $resolvedExecutable serve: ${e.message}", e)
            }

            val listenUrl = try {
                waitForListenUrl(process, port)
            } catch (e: Exception) {
                runCatching {
                    process.destroy()
                    process.destroyForcibly()
                }
                throw e
            }

            val authHeader = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

            return OpencodeProcess(
                process = process,
                baseUrl = listenUrl.trimEnd('/'),
                authHeader = authHeader,
                executablePath = resolvedExecutable,
                portValue = port,
            )
        }

        /**
         * Reads stdout until we see the "opencode server listening on ..." line
         * (`packages/opencode/src/cli/cmd/serve.ts`) or until the process exits
         * or [START_TIMEOUT_MS] elapses. The reader thread also acts as the
         * long-lived stdout drain — if stdout fills its pipe the child blocks,
         * so we keep consuming after startup and log every line at info level.
         *
         * Stderr is drained separately on a pooled thread.
         */
        private fun waitForListenUrl(process: Process, expectedPort: Int): String {
            val listenLatch = CountDownLatch(1)
            val urlHolder = AtomicReference<String?>(null)

            // stdout reader — captures listen URL, then keeps draining
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (urlHolder.get() == null && line.startsWith(LISTEN_LINE_PREFIX)) {
                                val url = line.substring(LISTEN_LINE_PREFIX.length).trim()
                                urlHolder.set(url)
                                listenLatch.countDown()
                            }
                            logger.info("opencode: $line")
                        }
                    }
                } catch (_: Exception) {
                    // Process ended or stream closed; latch may still be releasing.
                }
            }

            // stderr reader — log everything, don't parse
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            logger.info("opencode[stderr]: $line")
                        }
                    }
                } catch (_: Exception) {
                    // Process ended.
                }
            }

            // Poll: watch for the URL, an early exit, or the timeout.
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (listenLatch.await(200, TimeUnit.MILLISECONDS)) {
                    val url = urlHolder.get()
                    if (url != null) return url
                }
                if (!process.isAlive) {
                    val exit = process.exitValue()
                    throw OpencodeStartupException(
                        "opencode serve exited with code $exit before announcing a listen URL. Run `opencode serve --port $expectedPort` in a terminal to diagnose.",
                    )
                }
            }
            throw OpencodeStartupException(
                "opencode serve did not announce a listen URL within ${START_TIMEOUT_MS / 1000}s.",
            )
        }

        private fun allocateEphemeralPort(): Int =
            ServerSocket(0).use { it.localPort }

        private fun generatePassword(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * Load a PATH-rich environment. Prefer IntelliJ's `EnvironmentUtil`
         * (mirrors what the IDE was launched with, which on macOS already
         * includes the user's login PATH); fall back to a login+interactive
         * shell if unavailable, matching what `SweepMcpClient` does.
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
         * if the caller already supplied one). Kept local to avoid coupling
         * with `SweepMcpClient`'s private helper of the same name.
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

class OpencodeStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
