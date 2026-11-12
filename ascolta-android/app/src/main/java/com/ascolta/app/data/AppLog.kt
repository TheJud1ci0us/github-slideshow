package com.ascolta.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent log, viewable in Settings → Diagnostics, so problems on a phone
 * can be reported without a computer.
 */
object AppLog {
    /** "Detailed log" (Diagnostics): every sentence, why it was flagged, what was redone. */
    @Volatile var detailed = false
    private val maxBytes get() = if (detailed) 1_000_000L else 200_000L
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "ascolta.log")
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
    }

    /** Only written with the detailed log on; the message is built only then. */
    inline fun d(tag: String, message: () -> String) {
        if (detailed) detail(tag, message())
    }

    fun detail(tag: String, message: String) {
        Log.d(tag, message)
        write("D", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        write("W", tag, message + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: "(log is empty)"

    fun clear() {
        file?.delete()
    }

    @Synchronized
    fun write(level: String, tag: String, message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > maxBytes) {
                val keep = f.readText().takeLast((maxBytes / 2).toInt())
                f.writeText(keep)
            }
            f.appendText("${time.format(Date())} $level/$tag: $message\n")
        }
    }
}
