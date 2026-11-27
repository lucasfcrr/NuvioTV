package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.domain.model.Stream
import java.io.File

/**
 * Fork: persistent trace of every automatic next-episode choice, so "marathon mode" (the same
 * kind of link from the first episode to the last) can be judged from real use instead of a
 * staged test. One line per decision in the app's external files dir
 * (`/sdcard/Android/data/com.nuvio.tv/files/fork_marathon.log`), kept after the TV is turned off
 * and rotated to `.1` at 1 MB. It records which rule decided (binge group + file name, file name
 * only, or the fork's same-source fallback), what was playing and what was picked, and whether
 * the addon and binge group stayed the same. Nothing here changes what gets played.
 */
object MarathonTrace {
    private const val TRACE_FILE = "fork_marathon.log"
    private const val TRACE_MAX_BYTES = 1L * 1024L * 1024L
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "marathon-trace").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var traceDir: File? = null
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    fun configure(context: Context) {
        traceDir = runCatching { context.getExternalFilesDir(null) }.getOrNull()
    }

    /** Short, single-line description of [stream]: addon, label, binge group and file. */
    fun describe(stream: Stream?): String {
        if (stream == null) return "none"
        val label = (stream.name ?: stream.title).orEmpty().lineSequence().joinToString(" ").trim().take(40)
        return "addon='${stream.addonName}' label='$label' bg='${stream.behaviorHints?.bingeGroup.orEmpty()}' " +
            "file='${stream.behaviorHints?.filename.orEmpty().take(90)}'"
    }

    fun log(line: String) {
        val dir = traceDir ?: return
        val time = System.currentTimeMillis()
        executor.execute {
            runCatching {
                val f = File(dir, TRACE_FILE)
                if (f.length() > TRACE_MAX_BYTES) {
                    val old = File(dir, "$TRACE_FILE.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText("${stamp.format(java.util.Date(time))} $line\n")
            }
        }
    }
}
