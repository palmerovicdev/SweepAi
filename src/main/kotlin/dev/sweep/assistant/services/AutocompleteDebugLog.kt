package dev.sweep.assistant.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object AutocompleteDebugLog {
    private const val MAX_QUEUE_SIZE = 10_000
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE_SIZE)

    init {
        Thread({
            while (true) {
                try {
                    val firstLine = queue.take()
                    val lines = mutableListOf(firstLine)
                    queue.drainTo(lines, 256)
                    writeLines(lines)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (_: Throwable) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(250)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }
        }, "Sweep Autocomplete Debug Log").apply {
            isDaemon = true
            start()
        }
    }

    fun path(): Path {
        val configured =
            System.getProperty("sweep.autocomplete.debug.log")?.takeIf { it.isNotBlank() }
                ?: System.getenv("SWEEP_AUTOCOMPLETE_DEBUG_LOG")?.takeIf { it.isNotBlank() }

        return if (configured != null) {
            Path.of(configured).toAbsolutePath()
        } else {
            Path.of(System.getProperty("user.home"), ".sweep", "autocomplete-debug.log")
        }
    }

    fun log(message: String) {
        try {
            val line = "${Instant.now()} [${Thread.currentThread().name}] $message"
            queue.offer(line)
        } catch (_: Throwable) {
            // Debug logging must never affect autocomplete.
        }
    }

    private fun writeLines(lines: List<String>) {
        try {
            val logPath = path()
            logPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                logPath,
                lines.joinToString(separator = "\n", postfix = "\n"),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (_: Throwable) {
            // Keep the worker alive even if the path is temporarily unavailable.
        }
    }
}
