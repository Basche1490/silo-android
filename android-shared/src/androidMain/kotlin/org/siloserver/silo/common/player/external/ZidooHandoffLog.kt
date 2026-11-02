package org.siloserver.silo.common.player.external

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short log of the latest Zidoo hand-off, kept in a file so it survives Silo
 * being closed or crashing. Settings → External Player → Hand-off Log shows
 * it, so what happened can be read off the TV on a box without adb.
 *
 * [startRun] clears it, so it holds one hand-off and whatever followed it:
 * a crash ([recordCrashes]) or Silo starting again. It keeps at most
 * [MAX_LINES] lines, plus room for one crash.
 */
class ZidooHandoffLog(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private var count = -1

    /** Clears the log for a new hand-off and writes [message] as its first line. */
    @Synchronized
    fun startRun(message: String) {
        file.delete()
        count = 0
        add(message)
    }

    @Synchronized
    fun add(message: String) {
        write(listOf(message.replace('\n', ' ')), limit = MAX_LINES)
    }

    /** [error], its causes and the top of each stack, past the usual line limit. */
    @Synchronized
    fun addCrash(threadName: String, error: Throwable) {
        val lines = mutableListOf("CRASH on thread $threadName: $error")
        error.stackTrace.take(CRASH_FRAMES).mapTo(lines) { "    at $it" }
        var cause = error.cause
        var depth = 0
        while (cause != null && cause !== error && depth < MAX_CAUSES) {
            lines += "  Caused by: $cause"
            cause.stackTrace.take(CAUSE_FRAMES).mapTo(lines) { "    at $it" }
            cause = cause.cause
            depth++
        }
        write(lines, limit = MAX_LINES + lines.size)
    }

    @Synchronized
    fun lines(): List<String> =
        try {
            if (file.exists()) file.readLines() else emptyList()
        } catch (_: IOException) {
            emptyList()
        }

    /**
     * Records any uncaught exception in this log, then hands it on to the
     * handler that was installed before, so Android and Silo's own crash
     * reports still see it.
     */
    fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                addCrash(thread.name, error)
            } catch (_: Throwable) {
                // Recording must never stand in the way of the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(messages: List<String>, limit: Int) {
        try {
            if (count < 0) count = if (file.exists()) file.readLines().size else 0
            if (count >= limit) return
            val stamp = time.format(Date(now()))
            val kept = messages.take(limit - count)
            file.parentFile?.mkdirs()
            file.appendText(kept.joinToString(separator = "") { "$stamp  $it\n" })
            count += kept.size
        } catch (_: IOException) {
            // A log that cannot be written is not worth failing a hand-off over.
        }
    }

    companion object {
        const val MAX_LINES = 300
        private const val CRASH_FRAMES = 25
        private const val CAUSE_FRAMES = 8
        private const val MAX_CAUSES = 3
    }
}
