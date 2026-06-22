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
 * Full-pipeline tri-provider benchmark runner.
 *
 * Accepts raw JPEG bytes per sheet; JPEG decode is inside the E2E timer so
 * that image-load cost is included in every measurement — consistent with the
 * Web benchmark that also loads images from file.
 *
 * Execution order:
 *   Warm-up (5 sheets × CPU-1T + CPU-4T + NNAPI, results discarded)
 *   CPU-1T pass — N sheets, logs [FULL-CPU1T] per sheet
 *   NNAPI  pass — N sheets, logs [FULL-NNAPI]  per sheet
 *   CPU-4T pass — N sheets, logs [FULL-CPU4T]  per sheet  ← LAST (device fully warm)
 *   Summary: [BENCHMARK-FULL-CPU1T/CPU4T/NNAPI]
 *
 * logcat filter:
 *   adb logcat -s "YoloBenchmark" -v raw
 */
class YoloBenchmarkRunner(private val context: Context) {

    companion object {
        private const val TAG    = "YoloBenchmark"
        private const val WARMUP = 5
        private val CHECKPOINTS  = listOf(1, 5, 10, 50, 100)
    }

    private val detector  = YoloPaperDetector.getInstance(context)
    private val processor = OmrProcessor(context, detector)  // must pass detector so YOLO is actually called

    /** Per-sheet captures keyed by provider name ("CPU","CPU4T","NNAPI"). Cleared each run(). */
    val captures: MutableMap<String, MutableList<SheetCapture>> = mutableMapOf()

    private var sourceLabels: List<String>? = null

    fun runWithLabels(
        jpegBytesList: List<ByteArray>,
        labels:        List<String>,
        onProgress:    ((String) -> Unit)? = null
    ): BenchmarkSummary {
        require(labels.size == jpegBytesList.size) { "labels/jpegBytesList size mismatch" }
        sourceLabels = labels
        return run(jpegBytesList, onProgress)
    }

    // ── Public result types ───────────────────────────────────────────────────
    data class SheetTiming(
        val sheet:    Int,      // 1-based
        val provider: String,
        val ortMs:    Long,     // OrtSession.run() + output tensor materialisation
        val stageMs:  Long,     // full YOLO stage (preprocess + infer + NMS + crop)
        val omrMs:    Long,     // post-YOLO pipeline (e2eMs − stageMs)
        val e2eMs:    Long,     // wall-clock: JPEG decode → final answers
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
        jpegBytesList: List<ByteArray>,
        onProgress: ((String) -> Unit)? = null
    ): BenchmarkSummary {
        require(jpegBytesList.isNotEmpty()) { "jpegBytesList must not be empty" }

        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        emit(onProgress, "")
        emit(onProgress, "===== FULL-PIPELINE BENCHMARK START =====")
        emit(onProgress, "Device      : $device")
        emit(onProgress, "ORT version : 1.18.0")
        emit(onProgress, "NNAPI avail : ${detector.nnapiAvailable}  FP16: ${detector.nnapiUseFp16}")
        emit(onProgress, "Sheets      : ${jpegBytesList.size}  Warmup: $WARMUP")
        emit(onProgress, "")
        if (!detector.nnapiAvailable) {
            emit(onProgress, "⚠ NNAPI not available — only CPU baseline will be measured.")
            emit(onProgress, "  Check logcat tag 'YoloPaperDetector' for rejection reason.")
        }

        // ── CPU/GPU frequency monitor + Resource monitor (full benchmark duration) ───
        val monitorScope = CoroutineScope(Dispatchers.IO)
        val freqJob = FreqMonitor.start(monitorScope)
        val resJob  = ResourceMonitor.start(monitorScope, context)
        emit(onProgress, "Resource monitor → ${ResourceMonitor.outputPath()}")

        // ── Warm-up (results discarded) ───────────────────────────────────────
        ResourceMonitor.setPhase("warmup")
        emit(onProgress, "Warming up (${minOf(WARMUP, jpegBytesList.size)} sheets × 3 providers)…")
        for (i in 0 until minOf(WARMUP, jpegBytesList.size)) {
            processSheet(jpegBytesList[i], i, YoloPaperDetector.Provider.CPU,   warmup = true)
            processSheet(jpegBytesList[i], i, YoloPaperDetector.Provider.CPU4T, warmup = true)
            if (detector.nnapiAvailable)
                processSheet(jpegBytesList[i], i, YoloPaperDetector.Provider.NNAPI, warmup = true)
        }
        emit(onProgress, "Warm-up done.")
        emit(onProgress, "")

        // ── CPU-1T pass ───────────────────────────────────────────────────────
        ResourceMonitor.setPhase("CPU1T")
        captures.clear()
        emit(onProgress, "── CPU-1T full pipeline (${jpegBytesList.size} sheets) ──")
        val cpuTimings = mutableListOf<SheetTiming>()
        for ((idx, bytes) in jpegBytesList.withIndex()) {
            ResourceMonitor.setSheet(idx + 1)
            val t = processSheet(bytes, idx, YoloPaperDetector.Provider.CPU)
            cpuTimings += t
            val line = if (t.failed)
                "[FULL-CPU1T] sheet=${t.sheet}/${jpegBytesList.size}  FAILED"
            else
                "[FULL-CPU1T] sheet=${t.sheet}/${jpegBytesList.size}  ORT=${t.ortMs}ms  OMR=${t.omrMs}ms  E2E=${t.e2eMs}ms"
            Log.i(TAG, line)
            emit(onProgress, line)
        }

        // ── NNAPI pass ────────────────────────────────────────────────────────
        val nnapiTimings = mutableListOf<SheetTiming>()
        if (detector.nnapiAvailable) {
            ResourceMonitor.setPhase("NNAPI")
            emit(onProgress, "")
            emit(onProgress, "── NNAPI full pipeline (${jpegBytesList.size} sheets) ──")
            for ((idx, bytes) in jpegBytesList.withIndex()) {
                ResourceMonitor.setSheet(idx + 1)
                val t = processSheet(bytes, idx, YoloPaperDetector.Provider.NNAPI)
                nnapiTimings += t
                val line = if (t.failed)
                    "[FULL-NNAPI] sheet=${t.sheet}/${jpegBytesList.size}  FAILED"
                else
                    "[FULL-NNAPI] sheet=${t.sheet}/${jpegBytesList.size}  ORT=${t.ortMs}ms  OMR=${t.omrMs}ms  E2E=${t.e2eMs}ms"
                Log.i(TAG, line)
                emit(onProgress, line)
            }
        } else {
            Log.w(TAG, "[FULL-NNAPI] skipped — not available")
        }

        // ── CPU-4T pass (LAST — device fully warm for thermal consistency) ────
        ResourceMonitor.setPhase("CPU4T")
        val cpu4tTimings = mutableListOf<SheetTiming>()
        emit(onProgress, "")
        emit(onProgress, "── CPU-4T full pipeline (${jpegBytesList.size} sheets) ──")
        for ((idx, bytes) in jpegBytesList.withIndex()) {
            ResourceMonitor.setSheet(idx + 1)
            val t = processSheet(bytes, idx, YoloPaperDetector.Provider.CPU4T)
            cpu4tTimings += t
            val line = if (t.failed)
                "[FULL-CPU4T] sheet=${t.sheet}/${jpegBytesList.size}  FAILED"
            else
                "[FULL-CPU4T] sheet=${t.sheet}/${jpegBytesList.size}  ORT=${t.ortMs}ms  OMR=${t.omrMs}ms  E2E=${t.e2eMs}ms"
            Log.i(TAG, line)
            emit(onProgress, line)
        }

        ResourceMonitor.setPhase("done")
        return buildSummary(cpuTimings, nnapiTimings, cpu4tTimings, device).also { s ->
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
        provider:  YoloPaperDetector.Provider,
        warmup:    Boolean = false
    ): SheetTiming {
        detector.activeProvider = provider
        val label = sourceLabels?.getOrNull(idx)
            ?: if (warmup) "warmup${idx}" else "bench_${provider.name.lowercase()}_${idx}"
        return try {
            // E2E timer starts before JPEG decode, matching Web benchmark file-load cost
            val t0          = SystemClock.elapsedRealtime()
            val bmp         = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: error("BitmapFactory returned null — corrupted JPEG?")
            val jpegDecodeMs = SystemClock.elapsedRealtime() - t0

            val result = processor.process(bmp, sourceFile = label)
            val e2eMs = SystemClock.elapsedRealtime() - t0
            val st = processor.lastStageTimes
            val jpegWriteMs = st?.jpegWriteMs ?: 0L

            // Stage breakdown (logcat only, not emitted to UI to keep output clean)
            if (!warmup) {
                if (st != null) {
                    val yoloPostMs = (detector.lastStageMs - detector.lastPrepMs - detector.lastOrtMs)
                        .coerceAtLeast(0L)
                    val omrDetectMs = st.mssvMs + st.examKeyMs + st.answersMs
                    Log.i(TAG, "[STAGES-${provider.name}] sheet=${idx + 1}" +
                        "  jpeg_decode=${jpegDecodeMs}ms" +
                        " | bitmap_to_mat=${st.bitmapToMatMs}ms" +
                        " | yolo_prep=${detector.lastPrepMs}ms" +
                        " | yolo_ort=${detector.lastOrtMs}ms" +
                        " | yolo_post=${yoloPostMs}ms" +
                        " | omr_thresh=${st.threshMs}ms" +
                        " | omr_detect=${omrDetectMs}ms" +
                        " | jpeg_write=${st.jpegWriteMs}ms" +
                        " | E2E=${e2eMs}ms")
                }
                val answered = result.answers.count { it.value.isNotEmpty() }
                val multi    = result.answers.count { it.value.size >= 2 }
                val yoloPostMs = (detector.lastStageMs - detector.lastPrepMs - detector.lastOrtMs)
                    .coerceAtLeast(0L)
                val list = captures.getOrPut(provider.name) { mutableListOf() }
                list += SheetCapture(
                    sheet        = idx + 1,
                    provider     = provider.name,
                    cppMs        = (e2eMs - jpegDecodeMs - jpegWriteMs).coerceAtLeast(0L),
                    e2eMs        = e2eMs,
                    ortMs        = detector.lastOrtMs,
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
                    sourceFile   = sourceLabels?.getOrNull(idx),
                    answers      = result.answers,
                    bitmapToMatMs = st?.bitmapToMatMs ?: 0L,
                    normalizeMs   = st?.normalizeMs ?: 0L,
                    threshMs      = st?.threshMs ?: 0L,
                    omrDetectMs   = st?.let { it.mssvMs + it.examKeyMs + it.answersMs } ?: 0L,
                    yoloPrepMs    = detector.lastPrepMs,
                    yoloPostMs    = yoloPostMs
                )
            }

            val omrMs = (e2eMs - detector.lastStageMs).coerceAtLeast(0L)
            SheetTiming(
                sheet    = idx + 1,
                provider = provider.name,
                ortMs    = detector.lastOrtMs,
                stageMs  = detector.lastStageMs,
                omrMs    = omrMs,
                e2eMs    = e2eMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "[${provider.name}] sheet=${idx + 1} EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            if (!warmup) {
                val list = captures.getOrPut(provider.name) { mutableListOf() }
                list += SheetCapture(
                    sheet = idx + 1, provider = provider.name,
                    cppMs = 0, e2eMs = 0, ortMs = 0, jpegDecodeMs = 0, jpegWriteMs = 0,
                    failed = true, examCode = null, mssv = null,
                    mssvValid = false, keyValid = false,
                    answered = 0, suspicious = 0, multi = 0,
                    sourceFile = sourceLabels?.getOrNull(idx)
                )
            }
            SheetTiming(sheet = idx + 1, provider = provider.name,
                        ortMs = 0, stageMs = 0, omrMs = 0, e2eMs = 0, failed = true)
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
        val cOmr  = stats(cpuOk.map   { it.omrMs })
        val c4Ort = stats(cpu4tOk.map { it.ortMs })
        val c4E2e = stats(cpu4tOk.map { it.e2eMs })
        val c4Omr = stats(cpu4tOk.map { it.omrMs })
        val nOrt  = if (nnapiOk.isNotEmpty()) stats(nnapiOk.map { it.ortMs }) else null
        val nE2e  = if (nnapiOk.isNotEmpty()) stats(nnapiOk.map { it.e2eMs }) else null
        val nOmr  = if (nnapiOk.isNotEmpty()) stats(nnapiOk.map { it.omrMs }) else null

        val sb = StringBuilder()

        // ── Thermal throttle table (E2E, not ORT) ─────────────────────────────
        sb.appendLine()
        sb.appendLine("===== THERMAL THROTTLE =====")
        sb.appendLine("Frame | CPU1T_E2E | CPU4T_E2E | NNAPI_E2E")
        for (cp in CHECKPOINTS) {
            val c  = cpuOk.getOrNull(cp - 1)
            val c4 = cpu4tOk.getOrNull(cp - 1)
            val n  = nnapiOk.getOrNull(cp - 1)
            fun ms(t: SheetTiming?) = if (t != null) "${t.e2eMs.toString().padStart(6)}ms" else "      —"
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

        sb.append("[BENCHMARK-FULL-CPU1T]  ")
        sb.append("ORT mean=${cOrt.mean.toInt()}ms SD=${cOrt.sd.toInt()}ms  ")
        sb.append("OMR mean=${cOmr.mean.toInt()}ms  ")
        sb.appendLine("E2E mean=${cE2e.mean.toInt()}ms SD=${cE2e.sd.toInt()}ms")

        sb.append("[BENCHMARK-FULL-CPU4T]  ")
        sb.append("ORT mean=${c4Ort.mean.toInt()}ms SD=${c4Ort.sd.toInt()}ms  ")
        sb.append("OMR mean=${c4Omr.mean.toInt()}ms  ")
        sb.appendLine("E2E mean=${c4E2e.mean.toInt()}ms SD=${c4E2e.sd.toInt()}ms")

        if (nOrt != null && nE2e != null && nOmr != null) {
            sb.append("[BENCHMARK-FULL-NNAPI]  ")
            sb.append("ORT mean=${nOrt.mean.toInt()}ms SD=${nOrt.sd.toInt()}ms  ")
            sb.append("OMR mean=${nOmr.mean.toInt()}ms  ")
            sb.appendLine("E2E mean=${nE2e.mean.toInt()}ms SD=${nE2e.sd.toInt()}ms")
        } else {
            sb.appendLine("[BENCHMARK-FULL-NNAPI]  NOT available on this device")
        }

        sb.appendLine()
        val e2e4vs1 = if (c4E2e.mean > 0) cE2e.mean / c4E2e.mean else 0.0
        sb.appendLine("CPU-4T vs CPU-1T  E2E speedup: ${"%.2f".format(e2e4vs1)}×")
        if (nE2e != null) {
            val e2eNvsN  = if (nE2e.mean > 0) cE2e.mean / nE2e.mean else 0.0
            val e2e4vsNn = if (nE2e.mean > 0) c4E2e.mean / nE2e.mean else 0.0
            sb.appendLine("NNAPI  vs CPU-1T  E2E speedup: ${"%.2f".format(e2eNvsN)}×")
            sb.appendLine("CPU-4T vs NNAPI   E2E ratio:   ${"%.2f".format(e2e4vsNn)}×")
        }

        sb.appendLine()
        sb.appendLine("Note: E2E = JPEG decode + bitmapToMat + YOLO stage + OMR pipeline + debug JPEG I/O.")
        sb.appendLine("Note: OMR = E2E − YOLO-stage (includes bitmapToMat + debug JPEG write ~30–60 ms/sheet).")
        sb.appendLine("Note: ORT = OrtSession.run() + output tensor materialisation (forced eager).")
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
