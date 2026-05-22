package com.gradesnap.omr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tri-provider benchmark runner.
 *
 * Execution order per sheet:
 *   Warm-up (5 sheets, CPU-1T + CPU-4T + NNAPI, discarded)
 *   CPU-1T pass — N sheets, logs [PERF-CPU]    per sheet
 *   NNAPI pass  — N sheets, logs [PERF-NNAPI]  per sheet
 *   CPU-4T pass — N sheets, logs [PERF-CPU4T]  per sheet
 *   Final summary: [BENCHMARK-CPU-1T] / [BENCHMARK-CPU-4T] / [BENCHMARK-NNAPI]
 *
 * logcat filter:
 *   adb logcat -s "YoloBenchmark" -v raw
 *
 * Design notes:
 *   • CPU-4T runs LAST (device already fully warm) — consistent with the
 *     "optimised Web" vs "optimised Native CPU" comparison goal.
 *   • Each sheet is wrapped in try-catch. A failed sheet is logged and
 *     excluded from stats rather than aborting the whole benchmark.
 *   • E2E includes OmrProcessor's debug-image I/O (~30–60 ms/sheet),
 *     same cost for all three providers, so relative comparison is valid.
 *
 * Usage:
 *   lifecycleScope.launch(Dispatchers.Default) {
 *       val summary = YoloBenchmarkRunner(applicationContext)
 *           .run(bitmapList) { msg -> runOnUiThread { logView.append(msg) } }
 *       Log.i("Bench", summary.paperReadyText)
 *   }
 */
class YoloBenchmarkRunner(private val context: Context) {

    companion object {
        private const val TAG     = "YoloBenchmark"
        private const val WARMUP  = 5
        private val CHECKPOINTS   = listOf(1, 5, 10, 50, 100)
    }

    private val detector  = YoloPaperDetector.getInstance(context)
    private val processor = OmrProcessor(context)

    // ── Public result types ───────────────────────────────────────────────────
    data class SheetTiming(
        val sheet:    Int,     // 1-based
        val provider: String,
        val ortMs:    Long,    // pure OrtSession.run() — "YOLO inference" in the paper
        val stageMs:  Long,    // full YOLO stage (preprocess + infer + NMS + crop)
        val e2eMs:    Long,    // full OMR pipeline (Stage1 + Stage2 + threshold + bubbles + I/O)
        val failed:   Boolean = false
    )

    data class BenchmarkSummary(
        val n:              Int,
        val nFailed:        Int,
        val cpuOrtMean:     Double,  val cpuOrtSd:    Double,
        val cpuE2eMean:     Double,  val cpuE2eSd:    Double,
        val cpu4tOrtMean:   Double,  val cpu4tOrtSd:  Double,
        val cpu4tE2eMean:   Double,  val cpu4tE2eSd:  Double,
        val nnapiOrtMean:   Double?, val nnapiOrtSd:  Double?,
        val nnapiE2eMean:   Double?, val nnapiE2eSd:  Double?,
        val nnapiAvailable: Boolean,
        val nnapiUseFp16:   Boolean,
        val paperReadyText: String
    )

    // ── Entry point ───────────────────────────────────────────────────────────
    fun run(
        bitmaps:    List<Bitmap>,
        onProgress: ((String) -> Unit)? = null
    ): BenchmarkSummary {
        require(bitmaps.isNotEmpty()) { "bitmaps must not be empty" }

        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        emit(onProgress, "")
        emit(onProgress, "===== YoloBenchmarkRunner START =====")
        emit(onProgress, "Device      : $device")
        emit(onProgress, "ORT version : ${ai.onnxruntime.OrtEnvironment.getVersion()}")
        emit(onProgress, "NNAPI avail : ${detector.nnapiAvailable}  FP16: ${detector.nnapiUseFp16}")
        emit(onProgress, "Sheets      : ${bitmaps.size}  Warmup: $WARMUP")
        emit(onProgress, "")
        if (!detector.nnapiAvailable) {
            emit(onProgress, "⚠ NNAPI not available — only CPU baseline will be measured.")
            emit(onProgress, "  Check logcat tag 'YoloPaperDetector' for rejection reason.")
        }

        // ── Warm-up (results discarded) ───────────────────────────────────────
        emit(onProgress, "Warming up (${minOf(WARMUP, bitmaps.size)} sheets × 3 providers)…")
        for (i in 0 until minOf(WARMUP, bitmaps.size)) {
            processSheet(bitmaps[i], i, YoloPaperDetector.Provider.CPU,   warmup = true)
            processSheet(bitmaps[i], i, YoloPaperDetector.Provider.CPU4T, warmup = true)
            if (detector.nnapiAvailable)
                processSheet(bitmaps[i], i, YoloPaperDetector.Provider.NNAPI, warmup = true)
        }
        emit(onProgress, "Warm-up done.")
        emit(onProgress, "")

        // ── CPU pass ──────────────────────────────────────────────────────────
        emit(onProgress, "── CPU pass (${bitmaps.size} sheets) ──")
        val cpuTimings = mutableListOf<SheetTiming>()
        for ((idx, bmp) in bitmaps.withIndex()) {
            val t = processSheet(bmp, idx, YoloPaperDetector.Provider.CPU)
            cpuTimings += t
            val line = if (t.failed)
                "[PERF-CPU]   sheet=${t.sheet}/${bitmaps.size}  FAILED"
            else
                "[PERF-CPU]   sheet=${t.sheet}/${bitmaps.size}  ORT=${t.ortMs}ms  stage=${t.stageMs}ms  E2E=${t.e2eMs}ms"
            Log.i(TAG, line)
            emit(onProgress, line)
        }

        // ── NNAPI pass ────────────────────────────────────────────────────────
        val nnapiTimings = mutableListOf<SheetTiming>()
        if (detector.nnapiAvailable) {
            emit(onProgress, "")
            emit(onProgress, "── NNAPI pass (${bitmaps.size} sheets) ──")
            for ((idx, bmp) in bitmaps.withIndex()) {
                val t = processSheet(bmp, idx, YoloPaperDetector.Provider.NNAPI)
                nnapiTimings += t
                val line = if (t.failed)
                    "[PERF-NNAPI] sheet=${t.sheet}/${bitmaps.size}  FAILED"
                else
                    "[PERF-NNAPI] sheet=${t.sheet}/${bitmaps.size}  ORT=${t.ortMs}ms  stage=${t.stageMs}ms  E2E=${t.e2eMs}ms"
                Log.i(TAG, line)
                emit(onProgress, line)
            }
        } else {
            Log.w(TAG, "[PERF-NNAPI] skipped — not available")
        }

        // ── CPU-4T pass ───────────────────────────────────────────────────────
        val cpu4tTimings = mutableListOf<SheetTiming>()
        emit(onProgress, "")
        emit(onProgress, "── CPU-4T pass (${bitmaps.size} sheets) ──")
        for ((idx, bmp) in bitmaps.withIndex()) {
            val t = processSheet(bmp, idx, YoloPaperDetector.Provider.CPU4T)
            cpu4tTimings += t
            val line = if (t.failed)
                "[PERF-CPU4T] sheet=${t.sheet}/${bitmaps.size}  FAILED"
            else
                "[PERF-CPU4T] sheet=${t.sheet}/${bitmaps.size}  ORT=${t.ortMs}ms  E2E=${t.e2eMs}ms"
            Log.i(TAG, line)
            emit(onProgress, line)
        }

        return buildSummary(cpuTimings, nnapiTimings, cpu4tTimings, device).also { s ->
            Log.i(TAG, s.paperReadyText)
            emit(onProgress, s.paperReadyText)
        }
    }

    // ── Single sheet ──────────────────────────────────────────────────────────
    private fun processSheet(
        bmp:      Bitmap,
        idx:      Int,
        provider: YoloPaperDetector.Provider,
        warmup:   Boolean = false
    ): SheetTiming {
        detector.activeProvider = provider
        val label = if (warmup) "warmup${idx}" else "bench_${provider.name.lowercase()}_${idx}"
        return try {
            val t0 = SystemClock.elapsedRealtime()
            processor.process(bmp, sourceFile = label)
            val e2eMs = SystemClock.elapsedRealtime() - t0
            SheetTiming(
                sheet    = idx + 1,
                provider = provider.name,
                ortMs    = detector.lastOrtMs,
                stageMs  = detector.lastStageMs,
                e2eMs    = e2eMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "[${provider.name}] sheet=${idx + 1} EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            SheetTiming(sheet = idx + 1, provider = provider.name,
                        ortMs = 0, stageMs = 0, e2eMs = 0, failed = true)
        }
    }

    // ── Stats (excludes failed sheets) ────────────────────────────────────────
    private data class Stats(val mean: Double, val sd: Double, val min: Long, val max: Long, val n: Int)

    private fun stats(values: List<Long>): Stats {
        if (values.isEmpty()) return Stats(0.0, 0.0, 0, 0, 0)
        val mean = values.average()
        val sd   = if (values.size > 1)
            sqrt(values.sumOf { (it.toDouble() - mean).let { d -> d * d } } / (values.size - 1))
        else 0.0
        return Stats(mean, sd, values.minOrNull() ?: 0L, values.maxOrNull() ?: 0L, values.size)
    }

    // ── Summary ───────────────────────────────────────────────────────────────
    private fun buildSummary(
        cpu:    List<SheetTiming>,
        nnapi:  List<SheetTiming>,
        cpu4t:  List<SheetTiming>,
        device: String
    ): BenchmarkSummary {
        val cpuOk   = cpu.filter   { !it.failed }
        val nnapiOk = nnapi.filter { !it.failed }
        val cpu4tOk = cpu4t.filter { !it.failed }
        val nFailed = cpu.count { it.failed } + nnapi.count { it.failed } + cpu4t.count { it.failed }

        val cOrt  = stats(cpuOk.map   { it.ortMs })
        val cE2e  = stats(cpuOk.map   { it.e2eMs })
        val c4Ort = stats(cpu4tOk.map { it.ortMs })
        val c4E2e = stats(cpu4tOk.map { it.e2eMs })
        val nOrt  = if (nnapiOk.isNotEmpty()) stats(nnapiOk.map { it.ortMs }) else null
        val nE2e  = if (nnapiOk.isNotEmpty()) stats(nnapiOk.map { it.e2eMs }) else null

        val sb = StringBuilder()

        // ── Per-sheet table ───────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("===== PER-SHEET TIMING TABLE =====")
        sb.appendLine("sheet | CPU1T_ORT | CPU1T_E2E | CPU4T_ORT | CPU4T_E2E | NNAPI_ORT | NNAPI_E2E")
        for (i in cpuOk.indices) {
            val c  = cpuOk.getOrNull(i)
            val c4 = cpu4tOk.getOrNull(i)
            val n  = nnapiOk.getOrNull(i)
            fun fmt(t: SheetTiming?) = if (t != null) "${t.ortMs}ms / ${t.e2eMs}ms" else "N/A"
            sb.appendLine("  ${(i+1).toString().padStart(3)}  |  ${fmt(c)}  |  ${fmt(c4)}  |  ${fmt(n)}")
        }

        // ── Thermal throttle ──────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("===== THERMAL THROTTLE =====")
        sb.appendLine("Frame | CPU1T_ORT | CPU4T_ORT | NNAPI_ORT")
        for (cp in CHECKPOINTS) {
            val c  = cpuOk.getOrNull(cp - 1)
            val c4 = cpu4tOk.getOrNull(cp - 1)
            val n  = nnapiOk.getOrNull(cp - 1)
            fun ms(t: SheetTiming?) = if (t != null) "${t.ortMs.toString().padStart(4)}ms" else "   —  "
            sb.appendLine("  ${cp.toString().padStart(3)} |  ${ms(c)}  |  ${ms(c4)}  |  ${ms(n)}")
        }
        listOf(cpuOk to "CPU-1T", cpu4tOk to "CPU-4T", nnapiOk to "NNAPI").forEach { (ok, lbl) ->
            if (ok.size >= 2) {
                val delta = ok.last().e2eMs - ok.first().e2eMs
                val flag  = if (abs(delta) > 150) " ⚠ THROTTLE" else " (nominal)"
                sb.appendLine("$lbl last−first E2E delta: ${if (delta >= 0) "+" else ""}${delta}ms$flag")
            }
        }

        // ── Paper-ready block ─────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("===== PAPER-READY SUMMARY =====")
        sb.appendLine("Device        : $device")
        sb.appendLine("CPU cores     : ${Runtime.getRuntime().availableProcessors()} available")
        sb.appendLine("N (valid/total): ${cpuOk.size}/${cpu.size}  failed: $nFailed")
        sb.appendLine("NNAPI FP16    : ${detector.nnapiUseFp16}")
        sb.appendLine()

        sb.append("[BENCHMARK-CPU-1T]  ")
        sb.append("YOLO infer mean=${cOrt.mean.toInt()}ms SD=${cOrt.sd.toInt()}ms  ")
        sb.appendLine("E2E mean=${cE2e.mean.toInt()}ms SD=${cE2e.sd.toInt()}ms min=${cE2e.min}ms max=${cE2e.max}ms")

        sb.append("[BENCHMARK-CPU-4T]  ")
        sb.append("YOLO infer mean=${c4Ort.mean.toInt()}ms SD=${c4Ort.sd.toInt()}ms  ")
        sb.appendLine("E2E mean=${c4E2e.mean.toInt()}ms SD=${c4E2e.sd.toInt()}ms min=${c4E2e.min}ms max=${c4E2e.max}ms")

        if (nOrt != null && nE2e != null) {
            sb.append("[BENCHMARK-NNAPI]   ")
            sb.append("YOLO infer mean=${nOrt.mean.toInt()}ms SD=${nOrt.sd.toInt()}ms  ")
            sb.appendLine("E2E mean=${nE2e.mean.toInt()}ms SD=${nE2e.sd.toInt()}ms min=${nE2e.min}ms max=${nE2e.max}ms")
        } else {
            sb.appendLine("[BENCHMARK-NNAPI]   NOT available on this device")
        }

        val mt1v4 = if (c4Ort.mean > 0) cOrt.mean / c4Ort.mean else 0.0
        sb.appendLine("CPU-1T vs CPU-4T speedup: ${"%.2f".format(mt1v4)}×")
        if (nOrt != null) {
            val mtNn = if (nOrt.mean > 0) cOrt.mean / nOrt.mean else 0.0
            sb.appendLine("CPU-1T vs NNAPI   speedup: ${"%.2f".format(mtNn)}×")
        }

        sb.appendLine()
        sb.appendLine("Note: E2E includes JPEG debug I/O (~30–60 ms/sheet, equal across all providers).")
        sb.appendLine("Note: YOLO infer = OrtSession.run() only; letterbox+CHW (~20–40 ms) not included.")
        sb.appendLine("Note: CPU-4T pass ran last (device fully warm) for fair thermal comparison.")
        sb.appendLine("=================================")

        return BenchmarkSummary(
            n              = cpuOk.size,
            nFailed        = nFailed,
            cpuOrtMean     = cOrt.mean,   cpuOrtSd   = cOrt.sd,
            cpuE2eMean     = cE2e.mean,   cpuE2eSd   = cE2e.sd,
            cpu4tOrtMean   = c4Ort.mean,  cpu4tOrtSd = c4Ort.sd,
            cpu4tE2eMean   = c4E2e.mean,  cpu4tE2eSd = c4E2e.sd,
            nnapiOrtMean   = nOrt?.mean,  nnapiOrtSd = nOrt?.sd,
            nnapiE2eMean   = nE2e?.mean,  nnapiE2eSd = nE2e?.sd,
            nnapiAvailable = detector.nnapiAvailable,
            nnapiUseFp16   = detector.nnapiUseFp16,
            paperReadyText = sb.toString()
        )
    }

    private fun emit(cb: ((String) -> Unit)?, msg: String) {
        Log.i(TAG, msg)
        cb?.invoke(msg)
    }
}
