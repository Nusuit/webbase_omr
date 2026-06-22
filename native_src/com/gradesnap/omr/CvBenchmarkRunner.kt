package com.gradesnap.omr

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * File-based benchmark for the pure-OpenCV CV pipeline (no YOLO).
 *
 * Measures: JPEG decode + bitmapToMat + NormalizePaper (warp) + threshold + bubble detect.
 * JPEG decode is inside the E2E timer — consistent with YoloBenchmarkRunner and the Web benchmark.
 *
 * Execution order:
 *   Warm-up  (5 sheets, results discarded)
 *   CV pass  — N sheets, logs [FULL-CV] per sheet
 *   Summary: [BENCHMARK-FULL-CV]
 *
 * logcat filter (same tag as YOLO benchmark):
 *   adb logcat -s "YoloBenchmark" -v raw
 */
class CvBenchmarkRunner(private val context: Context) {

    companion object {
        private const val TAG    = "YoloBenchmark"
        private const val WARMUP = 5
        private val CHECKPOINTS  = listOf(1, 5, 10, 50, 100)
    }

    // OmrProcessor with default NormalizePaper — no YOLO involved
    private val processor = OmrProcessor(context)

    /** Per-sheet captures populated during run() (measurement pass only, warmup excluded). */
    val captures: MutableList<SheetCapture> = mutableListOf()

    data class SheetTiming(
        val sheet:  Int,
        val e2eMs:  Long,
        val failed: Boolean = false
    )

    data class BenchmarkSummary(
        val n:          Int,
        val nFailed:    Int,
        val e2eMean:    Double,
        val e2eSd:      Double,
        val e2eMin:     Long,
        val e2eMax:     Long,
        val paperReadyText: String
    )

    fun run(
        jpegBytesList: List<ByteArray>,
        onProgress: ((String) -> Unit)? = null
    ): BenchmarkSummary {
        require(jpegBytesList.isNotEmpty()) { "jpegBytesList must not be empty" }

        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        emit(onProgress, "")
        emit(onProgress, "===== FULL-CV BENCHMARK START =====")
        emit(onProgress, "Device  : $device")
        emit(onProgress, "Sheets  : ${jpegBytesList.size}  Warmup: $WARMUP")
        emit(onProgress, "Pipeline: JPEG decode + NormalizePaper (warp) + threshold + bubble detect")
        emit(onProgress, "")

        // ── CPU/GPU frequency monitor + Resource monitor ──────────────────────
        val monitorScope = CoroutineScope(Dispatchers.IO)
        val freqJob = FreqMonitor.start(monitorScope)
        val resJob  = ResourceMonitor.start(monitorScope, context)
        emit(onProgress, "Resource monitor → ${ResourceMonitor.outputPath()}")

        // ── Warm-up ───────────────────────────────────────────────────────────
        ResourceMonitor.setPhase("warmup_CV")
        emit(onProgress, "Warming up (${minOf(WARMUP, jpegBytesList.size)} sheets)…")
        for (i in 0 until minOf(WARMUP, jpegBytesList.size)) {
            processSheet(jpegBytesList[i], i, warmup = true)
        }
        emit(onProgress, "Warm-up done.")
        emit(onProgress, "")

        // ── CV measurement pass ───────────────────────────────────────────────
        ResourceMonitor.setPhase("CV")
        emit(onProgress, "── CV pipeline (${jpegBytesList.size} sheets) ──")
        captures.clear()
        val timings = mutableListOf<SheetTiming>()
        for ((idx, bytes) in jpegBytesList.withIndex()) {
            ResourceMonitor.setSheet(idx + 1)
            val lbl = sourceLabels?.getOrNull(idx)
            val t = processSheet(bytes, idx, sourceFile = lbl)
            timings += t
            val line = if (t.failed)
                "[FULL-CV] sheet=${t.sheet}/${jpegBytesList.size}  FAILED"
            else
                "[FULL-CV] sheet=${t.sheet}/${jpegBytesList.size}  E2E=${t.e2eMs}ms"
            Log.i(TAG, line)
            emit(onProgress, line)
        }

        ResourceMonitor.setPhase("done")
        return buildSummary(timings, device).also { s ->
            Log.i(TAG, s.paperReadyText)
            emit(onProgress, s.paperReadyText)
            freqJob.cancel()
            resJob.cancel()
            ResourceMonitor.stop()
        }
    }

    // ── Single sheet ──────────────────────────────────────────────────────────
    private fun processSheet(
        jpegBytes: ByteArray,
        idx:       Int,
        warmup:    Boolean = false,
        sourceFile: String? = null
    ): SheetTiming {
        val label = sourceFile ?: if (warmup) "warmup_cv_$idx" else "bench_cv_$idx"
        return try {
            val t0           = SystemClock.elapsedRealtime()
            val bmp          = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: error("BitmapFactory returned null — corrupted JPEG?")
            val jpegDecodeMs = SystemClock.elapsedRealtime() - t0

            val result = processor.process(bmp, sourceFile = label)
            val e2eMs = SystemClock.elapsedRealtime() - t0
            val st = processor.lastStageTimes
            val jpegWriteMs = st?.jpegWriteMs ?: 0L

            if (!warmup) {
                if (st != null) {
                    val omrDetectMs = st.mssvMs + st.examKeyMs + st.answersMs
                    Log.i(TAG, "[STAGES-CV] sheet=${idx + 1}" +
                        "  jpeg_decode=${jpegDecodeMs}ms" +
                        " | bitmap_to_mat=${st.bitmapToMatMs}ms" +
                        " | normalize=${st.normalizeMs}ms" +
                        " | omr_thresh=${st.threshMs}ms" +
                        " | omr_detect=${omrDetectMs}ms" +
                        " | jpeg_write=${st.jpegWriteMs}ms" +
                        " | E2E=${e2eMs}ms")
                }
                val answered = result.answers.count { it.value.isNotEmpty() }
                val multi    = result.answers.count { it.value.size >= 2 }
                captures += SheetCapture(
                    sheet        = idx + 1,
                    provider     = "CV",
                    cppMs        = (e2eMs - jpegDecodeMs - jpegWriteMs).coerceAtLeast(0L),
                    e2eMs        = e2eMs,
                    ortMs        = 0L,
                    jpegDecodeMs = jpegDecodeMs,
                    jpegWriteMs  = jpegWriteMs,
                    failed       = false,
                    examCode     = result.examCode,
                    mssv         = result.mssv,
                    mssvValid    = result.mssv != null,
                    keyValid     = result.examCode != null,
                    answered     = answered,
                    suspicious   = result.suspicious.size,
                    multi        = multi,
                    sourceFile   = sourceFile,
                    answers      = result.answers,
                    bitmapToMatMs = st?.bitmapToMatMs ?: 0L,
                    normalizeMs   = st?.normalizeMs ?: 0L,
                    threshMs      = st?.threshMs ?: 0L,
                    omrDetectMs   = st?.let { it.mssvMs + it.examKeyMs + it.answersMs } ?: 0L
                )
            }

            SheetTiming(sheet = idx + 1, e2eMs = e2eMs)
        } catch (e: Exception) {
            Log.e(TAG, "[CV] sheet=${idx + 1} EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            if (!warmup) {
                captures += SheetCapture(
                    sheet = idx + 1, provider = "CV",
                    cppMs = 0, e2eMs = 0, ortMs = 0, jpegDecodeMs = 0, jpegWriteMs = 0,
                    failed = true, examCode = null, mssv = null,
                    mssvValid = false, keyValid = false,
                    answered = 0, suspicious = 0, multi = 0, sourceFile = sourceFile
                )
            }
            SheetTiming(sheet = idx + 1, e2eMs = 0, failed = true)
        }
    }

    /** Headless entry: caller provides per-sheet labels (sourceFile) to be embedded in captures. */
    fun runWithLabels(
        jpegBytesList: List<ByteArray>,
        labels:        List<String>,
        onProgress:    ((String) -> Unit)? = null
    ): BenchmarkSummary {
        require(labels.size == jpegBytesList.size) { "labels/jpegBytesList size mismatch" }
        sourceLabels = labels
        return run(jpegBytesList, onProgress)
    }

    private var sourceLabels: List<String>? = null

    // ── Stats ─────────────────────────────────────────────────────────────────
    private data class Stats(val mean: Double, val sd: Double, val min: Long, val max: Long)

    private fun stats(values: List<Long>): Stats {
        if (values.isEmpty()) return Stats(0.0, 0.0, 0, 0)
        val mean = values.average()
        val sd   = if (values.size > 1)
            sqrt(values.sumOf { (it.toDouble() - mean).let { d -> d * d } } / (values.size - 1))
        else 0.0
        return Stats(mean, sd, values.min(), values.max())
    }

    // ── Summary ───────────────────────────────────────────────────────────────
    private fun buildSummary(timings: List<SheetTiming>, device: String): BenchmarkSummary {
        val ok      = timings.filter { !it.failed }
        val nFailed = timings.count { it.failed }
        val st      = stats(ok.map { it.e2eMs })

        val sb = StringBuilder()

        // ── Thermal table ─────────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("===== THERMAL THROTTLE =====")
        sb.appendLine("Frame | CV_E2E")
        for (cp in CHECKPOINTS) {
            val t = ok.getOrNull(cp - 1)
            val ms = if (t != null) "${t.e2eMs.toString().padStart(6)}ms" else "      —"
            sb.appendLine("  ${cp.toString().padStart(3)} |  $ms")
        }
        if (ok.size >= 2) {
            val delta = ok.last().e2eMs - ok.first().e2eMs
            val flag  = if (abs(delta) > 150) " ⚠ THROTTLE" else " (nominal)"
            sb.appendLine("CV last−first E2E delta: ${if (delta >= 0) "+" else ""}${delta}ms$flag")
        }

        // ── Paper-ready block ─────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("===== PAPER-READY SUMMARY =====")
        sb.appendLine("Device         : $device")
        sb.appendLine("CPU cores      : ${Runtime.getRuntime().availableProcessors()} available")
        sb.appendLine("N (valid/total): ${ok.size}/${timings.size}  failed: $nFailed")
        sb.appendLine()
        sb.append("[BENCHMARK-FULL-CV]  ")
        sb.append("E2E mean=${st.mean.toInt()}ms SD=${st.sd.toInt()}ms  ")
        sb.appendLine("min=${st.min}ms  max=${st.max}ms")
        sb.appendLine()
        sb.appendLine("Note: E2E = JPEG decode + bitmapToMat + NormalizePaper (perspective warp)")
        sb.appendLine("      + Otsu threshold + bubble/digit detection + debug JPEG write.")
        sb.appendLine("Note: No YOLO inference — pure OpenCV OMR pipeline only.")
        sb.appendLine("=================================")

        return BenchmarkSummary(
            n              = ok.size,
            nFailed        = nFailed,
            e2eMean        = st.mean,
            e2eSd          = st.sd,
            e2eMin         = st.min,
            e2eMax         = st.max,
            paperReadyText = sb.toString()
        )
    }

    private fun emit(cb: ((String) -> Unit)?, msg: String) {
        Log.i(TAG, msg)
        cb?.invoke(msg)
    }
}
