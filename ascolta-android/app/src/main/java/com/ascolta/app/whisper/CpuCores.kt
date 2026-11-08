package com.ascolta.app.whisper

import com.ascolta.app.data.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * Phones mix fast "performance" cores with slow "efficiency" cores (Nord 4: 1×X4 + 4×A720
 * fast, 3×A520 efficiency). Speech recognition splits work evenly between its threads and
 * waits for the slowest, so it should run on the fast cores only, while light jobs
 * (decoding audio, finding speech, saving) use the efficiency cores.
 */
class CpuTopology(val fast: List<Int>, val efficiency: List<Int>) {
    val all: List<Int> get() = (fast + efficiency).sorted()

    /** Cores for recognition with [threads] threads: the fast ones, unless more threads are requested. */
    fun recognitionCores(threads: Int): List<Int> = if (threads <= fast.size) fast else all

    /** Cores for light background jobs; falls back to all cores on phones without efficiency cores. */
    fun lightCores(): List<Int> = efficiency.ifEmpty { all }

    override fun toString() = "fast=$fast efficiency=$efficiency"

    companion object {
        /**
         * Reads each core's capacity (or, if unavailable, its maximum frequency) from sysfs.
         * Cores with at least 60% of the strongest core's capacity count as fast.
         */
        fun read(root: File = File("/sys/devices/system/cpu")): CpuTopology {
            val cpus = root.listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                .map { it.name.removePrefix("cpu").toInt() to it }
                .sortedBy { it.first }
            val score = cpus.associate { (id, dir) ->
                id to (readLong(File(dir, "cpu_capacity")) ?: readLong(File(dir, "cpufreq/cpuinfo_max_freq")) ?: 0L)
            }
            val max = score.values.maxOrNull() ?: 0L
            if (cpus.isEmpty() || max <= 0L) {
                val n = Runtime.getRuntime().availableProcessors()
                return CpuTopology((0 until n).toList(), emptyList())
            }
            val fast = score.filter { it.value >= max * 0.6 }.keys.sorted()
            val slow = score.filter { it.value < max * 0.6 }.keys.sorted()
            return CpuTopology(fast, slow)
        }

        private fun readLong(f: File): Long? = runCatching { f.readText().trim().toLong() }.getOrNull()

        val current: CpuTopology by lazy { read().also { AppLog.i("Cpu", "Cores: $it") } }
    }
}

/**
 * Two dedicated threads: one for recognition, pinned to the fast cores (the threads
 * whisper.cpp / ONNX Runtime start from it inherit that), and one for light jobs pinned
 * to the efficiency cores.
 */
object CoreDispatchers {
    @Volatile private var recognitionCores: List<Int> = emptyList()

    private val recognitionExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ascolta-recognition").apply { priority = Thread.MAX_PRIORITY }
    }
    private val lightExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            pin(CpuTopology.current.lightCores(), "light")
            r.run()
        }, "ascolta-light")
    }

    /** Recognition: call [prepareRecognition] first so the thread uses the right cores. */
    val recognition: CoroutineDispatcher = recognitionExecutor.asCoroutineDispatcher()

    /** Audio decoding, speech detection and saving. */
    val light: CoroutineDispatcher = lightExecutor.asCoroutineDispatcher()

    /** Pins the recognition thread to the cores suited to [threads] threads (no-op if unchanged). */
    fun prepareRecognition(threads: Int) {
        val cores = CpuTopology.current.recognitionCores(threads)
        if (cores == recognitionCores) return
        recognitionExecutor.submit { pin(cores, "recognition") }.get()
        recognitionCores = cores
    }

    /** Recommended thread count: the number of fast cores (at most 8). */
    fun defaultThreads(): Int = CpuTopology.current.fast.size.coerceIn(1, 8)

    private fun pin(cores: List<Int>, name: String) {
        val ok = runCatching { WhisperLib.nativeSetThreadAffinity(cores.toIntArray()) }.getOrDefault(false)
        AppLog.i("Cpu", "Pinned $name thread to cores $cores: ${if (ok) "ok" else "not allowed"}")
    }
}
