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
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "ascolta.log")
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
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
    private fun write(level: String, tag: String, message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep)
            }
            f.appendText("${time.format(Date())} $level/$tag: $message\n")
        }
    }
}
