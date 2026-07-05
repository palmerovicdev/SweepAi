package dev.sweep.assistant.api.external.bridge

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Owns the on-disk copy of the `ai-bridge/` sidecar.
 *
 * On first use per plugin version, [ensureExtracted] unpacks the daemon files
 * from `/ai-bridge.zip` inside the plugin JAR to
 * `PathManager.getPluginsPath()/sweep-ai-bridge/<pluginVersion>/` and writes a
 * `.extracted` marker with the version string. Subsequent calls short-circuit
 * unless the marker is missing or its content differs — that way a plugin
 * upgrade automatically refreshes the daemon without user intervention.
 *
 * The sibling `node_modules/` and the `.installed` marker live under the same
 * directory but are managed by [SdkManager] (Fase F). This class is only
 * concerned with the JS sources.
 */
@Service(Service.Level.APP)
class NodeDaemonManager {
    private val logger = Logger.getInstance(NodeDaemonManager::class.java)

    private val pluginVersion: String by lazy {
        PluginManagerCore.getPlugin(PluginId.getId("dev.sweep.assistant"))?.version ?: "unknown"
    }

    private val extractionLock = Any()

    /** Absolute path to the extracted bridge directory (always the same per plugin version). */
    fun bridgeDir(): Path {
        val root = PathManager.getPluginsPath()
        return Path.of(root).resolve("sweep-ai-bridge").resolve(pluginVersion)
    }

    /** Absolute path to `daemon.js` under [bridgeDir]. */
    fun daemonScript(): Path = bridgeDir().resolve("daemon.js")

    /**
     * Guarantees the sidecar files are on disk at [bridgeDir]. Idempotent; safe
     * to call from multiple threads. Returns the extracted [bridgeDir] on
     * success or throws on I/O failure.
     */
    fun ensureExtracted(): Path {
        val target = bridgeDir()
        val marker = target.resolve(".extracted")
        if (marker.exists() && runCatching { marker.readText() }.getOrNull() == pluginVersion) {
            return target
        }

        synchronized(extractionLock) {
            if (marker.exists() && runCatching { marker.readText() }.getOrNull() == pluginVersion) {
                return target
            }
            extractZip(target)
            marker.writeText(pluginVersion)
            logger.info("sweep-ai-bridge extracted to $target for plugin version $pluginVersion")
        }
        return target
    }

    private fun extractZip(target: Path) {
        Files.createDirectories(target)
        val stream = javaClass.getResourceAsStream("/ai-bridge.zip")
            ?: throw IOException("ai-bridge.zip resource missing from plugin JAR — Gradle packAiBridge did not run")

        ZipInputStream(stream.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (name.isBlank() || name.contains("..")) continue
                val out = target.resolve(name).normalize()
                if (!out.startsWith(target)) {
                    logger.warn("skipping zip-slip candidate: $name")
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
            }
        }
    }

    companion object {
        fun getInstance(): NodeDaemonManager =
            ApplicationManager.getApplication().getService(NodeDaemonManager::class.java)
    }
}
