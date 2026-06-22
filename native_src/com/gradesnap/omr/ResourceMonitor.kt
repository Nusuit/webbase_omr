package com.gradesnap.omr

import android.content.Context
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Continuously samples CPU% (process-wide) and memory (JVM heap, native heap, RSS)
 * during benchmarks and writes a per-sample CSV alongside the existing
 * PerformanceTelemetry / FreqMonitor outputs.
 *
 * Output:
 *   ${externalFilesDir}/Documents/resources_<yyyyMMdd_HHmmss>.csv
 *
 * Columns (one row per sample):
 *   t_ms                — milliseconds since start()
 *   phase               — caller-set label (e.g. "warmup", "CPU1T", "CPU4T", "NNAPI", "CV")
 *   jvm_heap_used_mb    — Runtime.total − Runtime.free
 *   jvm_heap_max_mb     — Runtime.max
 *   native_heap_used_mb — Debug.getNativeHeapAllocatedSize
 *   native_heap_size_mb — Debug.getNativeHeapSize
 *   rss_mb              — VmRSS from /proc/self/status
 *   cpu_percent         — process CPU% (out of cores × 100, matching `adb shell top`)
 *
 * Pull from device after the benchmark finishes:
 *   adb pull /sdcard/Android/data/com.gradesnap.omr/files/Documents/ ./runs/resources/native/
 *
 * Logcat (tag "YoloBenchmark") emits a one-line summary at start and stop, including
 * the exact file path so the operator never has to hunt for it.
 *
 * Usage from a benchmark runner (e.g. YoloBenchmarkRunner.run()):
 *
 *   val resJob = ResourceMonitor.start(scope, context)
 *   try {
 *       ResourceMonitor.setPhase("warmup")
 *       …
 *       ResourceMonitor.setPhase("CPU1T")
 *       …
 *   } finally {
 *       ResourceMonitor.stop()
 *       resJob.cancel()
 *   }
 */
object ResourceMonitor {

    private const val TAG = "YoloBenchmark"

    // Sampling state — only one monitor active at a time.
    @Volatile private var currentPhase: String = "idle"
    @Volatile private var currentSheet: Int = 0
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var startElapsed: Long = 0L

    // Last /proc snapshots — used to compute deltas across samples.
    @Volatile private var lastProcJiffies: Long = -1L
    @Volatile private var lastWallNanos:   Long = -1L

    private val clkTck: Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1L)
    } catch (_: Throwable) {
        100L
    }
    private val numCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun setPhase(phase: String) {
        currentPhase = phase
        currentSheet = 0  // reset on phase change
    }

    /** Called once per sheet right before processing starts. 1-based index. */
    fun setSheet(idx: Int) {
        currentSheet = idx
    }

    fun outputPath(): String? = outputFile?.absolutePath

    fun start(
        scope: CoroutineScope,
        context: Context,
        intervalMs: Long = 100L
    ): Job {
        // Open CSV up front so the operator can see the path immediately.
        val docs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        docs.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(docs, "resources_$ts.csv")
        val w = BufferedWriter(FileWriter(file))
        w.write(
            "t_ms,phase,sheet_idx,jvm_heap_used_mb,jvm_heap_max_mb," +
            "native_heap_used_mb,native_heap_size_mb,rss_mb,cpu_percent"
        )
        w.newLine()
        w.flush()
        outputFile = file
        writer = w
        startElapsed = SystemClock.elapsedRealtime()
        lastProcJiffies = -1L
        lastWallNanos = -1L
        currentPhase = "init"
        currentSheet = 0

        Log.i(TAG, "[RES_MON] START interval=${intervalMs}ms cores=$numCores hz=$clkTck")
        Log.i(TAG, "[RES_MON] FILE  ${file.absolutePath}")

        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    sampleOnce()
                    delay(intervalMs)
                }
            } finally {
                stop()
            }
        }
    }

    @Synchronized
    fun stop() {
        val w = writer ?: return
        try { w.flush() } catch (_: Throwable) {}
        try { w.close() } catch (_: Throwable) {}
        writer = null
        Log.i(TAG, "[RES_MON] STOP  ${outputFile?.absolutePath}")
    }

    private fun sampleOnce() {
        val w = writer ?: return
        val tNow = SystemClock.elapsedRealtime()
        val wallNanos = System.nanoTime()

        // ── JVM heap ──────────────────────────────────────────────────────────
        val rt = Runtime.getRuntime()
        val jvmUsed = (rt.totalMemory() - rt.freeMemory()).toDouble() / (1024.0 * 1024.0)
        val jvmMax  = rt.maxMemory().toDouble() / (1024.0 * 1024.0)

        // ── Native heap ───────────────────────────────────────────────────────
        val nativeUsed = Debug.getNativeHeapAllocatedSize().toDouble() / (1024.0 * 1024.0)
        val nativeSize = Debug.getNativeHeapSize().toDouble() / (1024.0 * 1024.0)

        // ── RSS (resident set size, in MB) — /proc/self/status:VmRSS ──────────
        val rssMb = readVmRssMb()

        // ── CPU% — delta of (utime+stime) over delta of wall-clock ────────────
        val procJiffies = readProcSelfJiffies()
        var cpuPct = 0.0
        if (lastProcJiffies >= 0L && procJiffies >= 0L && lastWallNanos > 0L) {
            val deltaJiffies = (procJiffies - lastProcJiffies).coerceAtLeast(0L)
            val deltaSec = (wallNanos - lastWallNanos).toDouble() / 1_000_000_000.0
            if (deltaSec > 0.0) {
                // jiffies → CPU-seconds: jiffies / HZ
                // % of one core: (cpu_sec / wall_sec) * 100
                // top shows this directly (can exceed 100% on multi-core)
                cpuPct = (deltaJiffies.toDouble() / clkTck) / deltaSec * 100.0
            }
        }
        lastProcJiffies = procJiffies
        lastWallNanos = wallNanos

        val tRel = tNow - startElapsed
        val line = String.format(
            Locale.US,
            "%d,%s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
            tRel, sanitizePhase(currentPhase), currentSheet,
            jvmUsed, jvmMax,
            nativeUsed, nativeSize,
            rssMb, cpuPct
        )
        try {
            w.write(line)
            w.newLine()
            // Flush every ~1 s of samples so a crash still leaves usable data.
            if (tRel % 1000L < 100L) w.flush()
        } catch (_: Throwable) {
            // Ignore — caller may have stopped us mid-write.
        }
    }

    private fun sanitizePhase(p: String): String =
        if (p.contains(',') || p.contains('\n')) p.replace(',', ';').replace('\n', ' ') else p

    /** Parse `utime` (field 14) + `stime` (field 15) from /proc/self/stat. */
    private fun readProcSelfJiffies(): Long {
        return try {
            val raw = File("/proc/self/stat").readText()
            // The comm field (2) is wrapped in parens and may contain spaces.
            val closeParen = raw.lastIndexOf(')')
            if (closeParen < 0) return -1L
            val rest = raw.substring(closeParen + 1).trim().split(Regex("\\s+"))
            // After comm we have: state(0), ppid(1), pgrp(2), session(3), tty_nr(4),
            // tpgid(5), flags(6), minflt(7), cminflt(8), majflt(9), cmajflt(10),
            // utime(11), stime(12), …
            if (rest.size < 13) return -1L
            val utime = rest[11].toLongOrNull() ?: return -1L
            val stime = rest[12].toLongOrNull() ?: return -1L
            utime + stime
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readVmRssMb(): Double {
        return try {
            File("/proc/self/status").useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("VmRSS:")) {
                        // e.g. "VmRSS:    156788 kB"
                        val kb = line.substringAfter("VmRSS:")
                            .trim()
                            .split(Regex("\\s+"))
                            .firstOrNull()
                            ?.toLongOrNull()
                            ?: return@useLines -1.0
                        return@useLines kb.toDouble() / 1024.0
                    }
                }
                -1.0
            }
        } catch (_: Throwable) {
            -1.0
        }
    }
}
