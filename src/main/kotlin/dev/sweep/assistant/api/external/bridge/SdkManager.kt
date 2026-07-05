package dev.sweep.assistant.api.external.bridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.EnvironmentUtil
import com.intellij.util.messages.Topic
import dev.sweep.assistant.settings.SweepSettings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Progress payload for a running `npm install`. */
sealed interface InstallProgress {
    data class Line(val stream: String, val text: String) : InstallProgress
    data class Done(val ok: Boolean, val summary: String) : InstallProgress
}

/** Broadcast when SDK versions or install state change. */
fun interface SdkStateNotifier {
    fun sdkStateChanged()
    companion object {
        @JvmField
        val TOPIC = Topic.create("Sweep SDK state changed", SdkStateNotifier::class.java)
    }
}

/**
 * Owns the npm-side lifecycle of the two SDK packages inside the ai-bridge
 * directory: reads installed versions from `node_modules/`, runs
 * `npm install <spec>` on demand, and reboots the bridge daemon so it picks up
 * the fresh code without waiting for an IDE restart.
 */
@Service(Service.Level.APP)
class SdkManager {
    private val logger = Logger.getInstance(SdkManager::class.java)

    private fun bridgeDir(): Path = NodeDaemonManager.getInstance().ensureExtracted()

    /** Reads the version from `<bridge>/node_modules/<pkg>/package.json`, or null when not installed. */
    fun getInstalledVersion(pkg: String): String? {
        val pkgDir = bridgeDir().resolve("node_modules").resolve(pkg).resolve("package.json")
        if (!pkgDir.exists()) return null
        val text = runCatching { pkgDir.readText() }.getOrNull() ?: return null
        // Very tiny JSON grep — pulls the first "version": "..." value. Good enough
        // to avoid pulling in a whole JSON dependency for two known files.
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)
        return match?.groupValues?.get(1)
    }

    /** Convenience — the two SDKs we manage. */
    fun getCodexSdkVersion(): String? = getInstalledVersion("@openai/codex-sdk")
    fun getOpencodeSdkVersion(): String? = getInstalledVersion("@opencode-ai/sdk")

    /**
     * Runs `npm install <spec…>` in the bridge dir, streaming stdout/stderr lines
     * via [onProgress]. Restarts the daemon on success so subsequent bridge
     * calls load the freshly-installed SDK versions.
     *
     * Set [restartBridge] to false when calling from inside NodeBridgeClient's
     * own ensureRunning() flow — restart there would recurse.
     */
    fun install(
        specs: List<String>,
        onProgress: (InstallProgress) -> Unit,
        restartBridge: Boolean = true,
    ) {
        val dir = bridgeDir()
        val npmPath = findNpm()
        if (npmPath == null) {
            onProgress(InstallProgress.Done(false, "npm executable not found on PATH"))
            return
        }
        val cmd = mutableListOf(npmPath, "install", "--omit=dev")
        cmd.addAll(specs)
        val pb = ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false)
        try {
            val env = EnvironmentUtil.getEnvironmentMap()
            env["PATH"]?.let { pb.environment()["PATH"] = it }
        } catch (_: Throwable) { /* fall back to inherited env */ }
        onProgress(InstallProgress.Line("stdout", "\$ npm install --omit=dev ${specs.joinToString(" ")}"))
        val process = try {
            pb.start()
        } catch (e: Throwable) {
            onProgress(InstallProgress.Done(false, "Failed to launch npm: ${e.message}"))
            return
        }

        // Drain stdout + stderr concurrently so back-pressure never deadlocks npm.
        val stdoutThread = Thread({
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { r ->
                while (true) { val l = r.readLine() ?: break; onProgress(InstallProgress.Line("stdout", l)) }
            }
        }, "sweep-npm-stdout").apply { isDaemon = true; start() }
        val stderrThread = Thread({
            BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use { r ->
                while (true) { val l = r.readLine() ?: break; onProgress(InstallProgress.Line("stderr", l)) }
            }
        }, "sweep-npm-stderr").apply { isDaemon = true; start() }
        val exit = process.waitFor()
        try { stdoutThread.join(2000) } catch (_: Throwable) {}
        try { stderrThread.join(2000) } catch (_: Throwable) {}
        val ok = exit == 0
        val summary = if (ok) "npm install succeeded (exit=$exit)" else "npm install failed (exit=$exit)"
        onProgress(InstallProgress.Done(ok, summary))
        if (ok) {
            markInstalled()
            SweepSettings.getInstance().bridgeSdksInstalled = true
            if (restartBridge) NodeBridgeClient.getInstance().restart()
            broadcast()
        }
    }

    /** Convenience: update both SDKs to the versions currently in [SweepSettings]. */
    fun updateAll(onProgress: (InstallProgress) -> Unit) {
        val s = SweepSettings.getInstance()
        val specs = listOf(
            "@openai/codex-sdk@${s.codexSdkVersion.ifBlank { "latest" }}",
            "@opencode-ai/sdk@${s.opencodeSdkVersion.ifBlank { "latest" }}",
        )
        install(specs, onProgress)
    }

    /** Delete `node_modules/` and the `.installed` marker, then reinstall. */
    fun reinstallAll(onProgress: (InstallProgress) -> Unit) {
        val dir = bridgeDir()
        val nodeModules = dir.resolve("node_modules").toFile()
        if (nodeModules.exists()) {
            onProgress(InstallProgress.Line("stdout", "Removing ${nodeModules.absolutePath}…"))
            nodeModules.deleteRecursively()
        }
        dir.resolve(".installed").toFile().delete()
        updateAll(onProgress)
    }

    /**
     * Runs `npm install --omit=dev` with no explicit specs so npm honors the
     * `dependencies` block in `package.json`. Used on first boot when
     * `node_modules/` is missing and the user hasn't picked custom versions.
     */
    fun ensureInstalledIfMissing(onProgress: (InstallProgress) -> Unit) {
        val dir = bridgeDir()
        if (dir.resolve("node_modules").resolve("@openai").resolve("codex-sdk").toFile().isDirectory
            && dir.resolve("node_modules").resolve("@opencode-ai").resolve("sdk").toFile().isDirectory
        ) {
            onProgress(InstallProgress.Done(true, "SDKs already installed."))
            return
        }
        install(emptyList(), onProgress)
    }

    /**
     * Same as [ensureInstalledIfMissing] but never triggers a bridge restart —
     * safe to call from inside NodeBridgeClient.ensureRunning() before spawning
     * the daemon. Accepts a simple log line callback so we don't leak
     * [InstallProgress] into low-level bridge code.
     */
    fun ensureInstalledIfMissingBlocking(logLine: (String) -> Unit) {
        val dir = bridgeDir()
        if (dir.resolve("node_modules").resolve("@openai").resolve("codex-sdk").toFile().isDirectory
            && dir.resolve("node_modules").resolve("@opencode-ai").resolve("sdk").toFile().isDirectory
        ) return
        install(
            specs = emptyList(),
            onProgress = { p ->
                when (p) {
                    is InstallProgress.Line -> logLine("[${p.stream}] ${p.text}")
                    is InstallProgress.Done -> logLine(if (p.ok) "✔ ${p.summary}" else "✘ ${p.summary}")
                }
            },
            restartBridge = false,
        )
    }

    private fun findNpm(): String? {
        val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"
        val settingsPath = SweepSettings.getInstance().aiBridgeNodePath.ifBlank { null }
        // npm usually lives next to node.
        settingsPath?.let {
            val cand = File(File(it).parentFile, exe)
            if (cand.isFile && cand.canExecute()) return cand.absolutePath
        }
        val path = try { EnvironmentUtil.getEnvironmentMap()["PATH"] } catch (_: Throwable) { System.getenv("PATH") }
        if (path != null) {
            for (dir in path.split(File.pathSeparatorChar)) {
                val f = File(dir, exe)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    private fun markInstalled() {
        val marker = bridgeDir().resolve(".installed").toFile()
        try {
            marker.writeText("codex=${getCodexSdkVersion()}\nopencode=${getOpencodeSdkVersion()}\n")
        } catch (e: Throwable) {
            logger.warn("Failed to write .installed marker", e)
        }
    }

    private fun broadcast() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SdkStateNotifier.TOPIC).sdkStateChanged()
    }

    companion object {
        fun getInstance(): SdkManager =
            ApplicationManager.getApplication().getService(SdkManager::class.java)
    }
}
