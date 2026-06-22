package com.gradesnap.omr

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Polls CPU/GPU frequencies every [intervalMs] ms during benchmarks.
 *
 * Uses the same logcat tag ("YoloBenchmark") so the existing filter
 *   adb logcat -s "YoloBenchmark" -v raw
 * captures freq samples alongside sheet timings.
 *
 * CPU: reads /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq (kHz → MHz).
 * GPU: tries common sysfs paths in order — Qualcomm Adreno, then MediaTek Mali.
 *      Falls back gracefully if none are readable (no root required on most devices).
 */
object FreqMonitor {

    private const val TAG = "YoloBenchmark"

    private val GPU_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",                               // Qualcomm Adreno (Hz)
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",                     // Qualcomm alt (Hz)
        "/sys/kernel/gpu/gpu_freq",                                        // MediaTek (Hz)
        "/sys/devices/platform/mali.0/devfreq/mali.0/cur_freq",           // Mali generic (Hz)
        "/sys/class/misc/mali0/device/devfreq/mali/cur_freq",             // Dimensity Mali (Hz)
        "/sys/class/devfreq/mali/cur_freq"                                 // Mali devfreq (Hz)
    )

    /**
     * Launch the monitor as a child of [scope].  Cancel the returned [Job] to stop it.
     *
     * Usage:
     *   val freqJob = FreqMonitor.start(CoroutineScope(Dispatchers.IO))
     *   try { ... benchmark ... } finally { freqJob.cancel() }
     */
    fun start(scope: CoroutineScope, intervalMs: Long = 500L): Job =
        scope.launch(Dispatchers.IO) {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            while (isActive) {
                val ts = SystemClock.elapsedRealtime()

                // ── CPU frequencies ───────────────────────────────────────────
                val freqs = (0 until cpuCount).map { cpu ->
                    try {
                        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
                            .readText().trim().toLong() / 1_000L   // kHz → MHz
                    } catch (_: Exception) { -1L }
                }
                val valid  = freqs.filter { it > 0 }
                val maxMhz = valid.maxOrNull() ?: -1L
                val avgMhz = if (valid.isNotEmpty()) valid.average().toLong() else -1L
                Log.i(TAG, "[CPU_FREQ] t=${ts}ms  max=${maxMhz}MHz  avg=${avgMhz}MHz" +
                           "  cores=${freqs.joinToString(",")}")

                // ── GPU frequency ─────────────────────────────────────────────
                val gpuMhz = GPU_PATHS.firstNotNullOfOrNull { path ->
                    runCatching {
                        val raw = File(path).readText().trim().toLong()
                        // Normalise: Hz (>10M) → MHz, kHz (>10K) → MHz, else assume MHz
                        when {
                            raw > 10_000_000L -> raw / 1_000_000L
                            raw > 10_000L     -> raw / 1_000L
                            else              -> raw
                        }
                    }.getOrNull()
                } ?: -1L
                Log.i(TAG, "[GPU_FREQ] t=${ts}ms  ${if (gpuMhz > 0) "${gpuMhz}MHz" else "unavailable"}")

                delay(intervalMs)
            }
        }
}
