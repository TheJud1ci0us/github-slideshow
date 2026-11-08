package com.ascolta.app.whisper

import android.content.Context
import android.os.Build
import android.os.Process
import com.ascolta.app.data.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed NPU log, for working out why the Qualcomm NPU refuses the model on a phone.
 * It only records information; it never changes how the NPU is used.
 *
 * Kept in its own file (Settings → Diagnostics → NPU log) so it doesn't push everything else
 * out of the main log. Each NPU attempt records:
 *  - the phone: chip, Android version, whether the NPU driver (FastRPC) is reachable,
 *    which Hexagon libraries the app ships and where the driver looks for them;
 *  - the model files and cached compiled graphs;
 *  - this app's own logcat lines from ONNX Runtime, the QNN runtime and the FastRPC driver
 *    during the attempt (with verbose ONNX Runtime / QNN logging when switched on).
 */
object NpuDebug {
    private const val MAX_BYTES = 600_000L
    private const val TAG = "NpuDebug"
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "npu_debug.log")
    }

    /** Verbose ONNX Runtime / QNN logging during NPU attempts (Settings → Diagnostics). */
    fun verbose(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("npu_verbose_log", true)

    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: "(NPU log is empty: it fills when the NPU model is tried)"

    fun clear() {
        file?.delete()
    }

    @Synchronized
    fun write(text: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            f.appendText(text.trimEnd() + "\n")
        }
    }

    fun section(title: String, body: String) {
        write("\n===== ${time.format(Date())} $title =====\n$body")
    }

    /** Everything about the phone and the app's files that matters for the NPU. */
    fun environmentReport(context: Context, modelDir: File?): String = buildString {
        appendLine("App: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.DISPLAY}  security patch ${if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "?"}")
        appendLine("Chip: ${NpuSupport.chipName()}  hardware=${Build.HARDWARE}  board=${Build.BOARD}")
        if (Build.VERSION.SDK_INT >= 31) appendLine("SoC: manufacturer=${Build.SOC_MANUFACTURER} model=${Build.SOC_MODEL}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("CPU: ${CpuTopology.current}")
        appendLine("NPU availability check: ${NpuSupport.unavailableReason(context) ?: "looks usable"}")
        appendLine("Verbose ONNX Runtime/QNN logging: ${verbose(context)}")
        appendLine("ADSP_LIBRARY_PATH: ${runCatching { android.system.Os.getenv("ADSP_LIBRARY_PATH") }.getOrNull() ?: "(not set yet)"}")
        appendLine("onnxruntime.native.onnxruntime.skip=${System.getProperty("onnxruntime.native.onnxruntime.skip")}")

        appendLine("-- NPU driver (FastRPC) files")
        for (path in listOf(
            "/vendor/lib64/libcdsprpc.so", "/system/vendor/lib64/libcdsprpc.so", "/vendor/lib64/libadsprpc.so",
            "/system/lib64/libcdsprpc.so", "/dev/fastrpc-cdsp", "/dev/fastrpc-cdsp-secure", "/dev/fastrpc-adsp",
            "/dev/ion", "/dev/dma_heap/system", "/dev/dma_heap/qcom,system",
            "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp", "/vendor/dsp",
        )) appendLine("  ${fileInfo(File(path))}")

        // Vendor libraries apps may load: libcdsprpc.so must be listed for the NPU to be usable.
        appendLine("-- Public vendor libraries (libcdsprpc.so must be listed)")
        for (path in listOf("/vendor/etc/public.libraries.txt", "/system/etc/public.libraries.txt")) {
            val f = File(path)
            val text = runCatching { f.readText() }
            appendLine(
                "  $path: " + text.fold(
                    { t -> t.lines().filter { it.contains("rpc", true) || it.contains("dsp", true) }.ifEmpty { listOf("no rpc/dsp entries") }.joinToString(", ") },
                    { e -> "unreadable (${e.javaClass.simpleName})" },
                ),
            )
        }

        appendLine("-- Hexagon / QNN libraries shipped with the app (${context.applicationInfo.nativeLibraryDir})")
        val libs = File(context.applicationInfo.nativeLibraryDir).listFiles().orEmpty()
            .filter { n -> listOf("qnn", "htp", "skel", "onnxruntim", "sherpa").any { n.name.contains(it, true) } }
            .sortedBy { it.name }
        if (libs.isEmpty()) appendLine("  (none found — the QNN runtime is missing from the APK)")
        for (lib in libs) appendLine("  ${lib.name} ${lib.length()} bytes")

        if (modelDir != null) {
            appendLine("-- Model files ($modelDir)")
            modelDir.walkTopDown().maxDepth(2).filter { it.isFile }.sortedBy { it.path }.forEach {
                appendLine("  ${it.relativeTo(modelDir)} ${it.length()} bytes")
            }
        }
    }

    private fun fileInfo(f: File): String {
        val exists = runCatching { f.exists() }.getOrDefault(false)
        if (!exists) return "${f.path}: missing (or hidden from apps)"
        val kind = if (f.isDirectory) "dir" else "file"
        return "${f.path}: $kind readable=${f.canRead()} writable=${f.canWrite()}" +
            if (f.isFile) " ${f.length()} bytes" else ""
    }

    /** Current time in logcat's format, to capture only what follows. */
    fun mark(): String = time.format(Date())

    /**
     * This app's own logcat lines since [since] (apps may read their own logs). With
     * [onlyNpu], keeps the lines about ONNX Runtime, QNN, FastRPC, the DSP and library loading.
     */
    fun captureLogcat(since: String, onlyNpu: Boolean = true, maxLines: Int = 1500): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-T", since, "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        val keys = listOf(
            "onnxruntime", "qnn", "htp", "hexagon", "rpc", "dsp", "skel", "npu", "linker", "dlopen",
            "ort", "ep context", "graph", "backend", "Npu", "fastrpc", "ion", "dma", "selinux", "avc:",
        )
        val kept = if (onlyNpu) lines.filter { l -> keys.any { l.contains(it, ignoreCase = true) } } else lines
        val shown = if (kept.size > maxLines) {
            listOf("… ${kept.size - maxLines} earlier lines left out …") + kept.takeLast(maxLines)
        } else kept
        "${lines.size} log lines, ${kept.size} about the NPU:\n" + shown.joinToString("\n").ifBlank { "(none)" }
    }.getOrElse { "logcat unavailable: ${it.javaClass.simpleName}: ${it.message}" }

    /** Full error chain, since ONNX Runtime puts the useful part in nested causes. */
    fun describe(error: Throwable): String = buildString {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 6) {
            if (depth > 0) append("caused by ")
            appendLine("${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(8).forEach { appendLine("    at $it") }
            e = e.cause
            depth++
        }
    }

    fun note(message: String) {
        AppLog.i(TAG, message)
        write("${time.format(Date())} $message")
    }
}
