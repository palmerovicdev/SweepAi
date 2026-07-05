package dev.sweep.assistant.api.external.bridge

import com.intellij.util.EnvironmentUtil
import java.io.File

/**
 * Best-effort discovery of a `node` executable on the user machine. Reuses the
 * same PATH lookup pattern as [dev.sweep.assistant.api.external.opencode.OpencodeAgentProvider],
 * extended with paths where nvm / volta / fnm / asdf install their default
 * runtime so users who never edit their shell rc still work.
 */
object NodeDetector {
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private val fallbackDirs: List<String> by lazy { computeFallbackDirs() }

    /**
     * Try to resolve a node executable. Preference order:
     *   1. `override` (typically `SweepSettings.aiBridgeNodePath`) if non-blank.
     *   2. `node` (or `node.exe`) on the login-shell PATH.
     *   3. Common install dirs for nvm / volta / fnm / asdf / homebrew.
     */
    fun detect(override: String? = null): String? {
        if (!override.isNullOrBlank()) {
            val f = File(override)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        val exe = if (isWindows) "node.exe" else "node"
        loadPath()?.let { path ->
            resolveOnPath(exe, path)?.let { return it }
        }
        for (dir in fallbackDirs) {
            val cand = File(dir, exe)
            if (cand.isFile && cand.canExecute()) return cand.absolutePath
            // Some managers (nvm) put binaries under `<dir>/<version>/bin/node`; try one level down.
            val glob = File(dir).listFiles { f -> f.isDirectory } ?: continue
            for (child in glob) {
                val nested = File(File(child, "bin"), exe)
                if (nested.isFile && nested.canExecute()) return nested.absolutePath
            }
        }
        return null
    }

    private fun loadPath(): String? =
        try {
            EnvironmentUtil.getEnvironmentMap()["PATH"]
        } catch (_: Throwable) {
            System.getenv("PATH")
        }

    private fun resolveOnPath(exe: String, path: String): String? {
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isEmpty()) continue
            val cand = File(dir, exe)
            if (cand.isFile && cand.canExecute()) return cand.absolutePath
        }
        return null
    }

    private fun computeFallbackDirs(): List<String> {
        val home = System.getProperty("user.home") ?: ""
        return if (isWindows) {
            listOfNotNull(
                System.getenv("LOCALAPPDATA")?.let { "$it\\nodejs" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\nodejs" },
                System.getenv("APPDATA")?.let { "$it\\npm" },
                System.getenv("USERPROFILE")?.let { "$it\\.volta\\bin" },
                System.getenv("USERPROFILE")?.let { "$it\\.fnm\\aliases\\default\\bin" },
            )
        } else {
            listOf(
                "$home/.local/bin",
                "$home/.volta/bin",
                "$home/.fnm/aliases/default/bin",
                "$home/.asdf/shims",
                "$home/.nvm/current/bin",
                "$home/.nvm/versions/node", // scanned via listFiles glob above
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/usr/bin",
            )
        }
    }
}
