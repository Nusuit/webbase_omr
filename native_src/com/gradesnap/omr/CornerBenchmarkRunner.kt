package com.gradesnap.omr

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.sqrt

/**
 * Full-pipeline tri-provider benchmark for the corner-keypoint path.
 * Mirrors YoloBenchmarkRunner but drives CornerKeypointDetector (single-class
 * corner model → 4 corners → homography), so native recognition matches the
 * Web hybrid pipeline instead of the old bbox-crop path.
 *
 * logcat: adb logcat -s "CornerBenchmark" -v raw
 */
class CornerBenchmarkRunner(private val context: Context) {

    companion object {
        private const val TAG    = "CornerBenchmark"
        private const val WARMUP = 5
    }

    private val detector  = CornerKeypointDetector.getInstance(context)
    private val processor = OmrProcessor(context, detector)

    val captures: MutableMap<String, MutableList<SheetCapture>> = mutableMapOf()
    private var sourceLabels: List<String>? = null

    fun runWithLabels(
        jpegBytesList: List<ByteArray>,
        labels:        List<String>,
        onProgress:    ((String) -> Unit)? = null
    ) {
        require(labels.size == jpegBytesList.size) { "labels/jpegBytesList size mismatch" }
        sourceLabels = labels
        run(jpegBytesList, onProgress)
    }

    fun run(jpegBytesList: List<ByteArray>, onProgress: ((String) -> Unit)? = null) {
        require(jpegBytesList.isNotEmpty()) { "jpegBytesList must not be empty" }
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
        emit(onProgress, "===== CORNER-KEYPOINT BENCHMARK START =====")
        emit(onProgress, "Device: $device  NNAPI: ${detector.nnapiAvailable}  Sheets: ${jpegBytesList.size}")

        val monitorScope = CoroutineScope(Dispatchers.IO)
        val freqJob = FreqMonitor.start(monitorScope)
        val resJob  = ResourceMonitor.start(monitorScope, context)

        ResourceMonitor.setPhase("warmup")
        for (i in 0 until minOf(WARMUP, jpegBytesList.size)) {
            processSheet(jpegBytesList[i], i, CornerKeypointDetector.Provider.CPU,   warmup = true)
            processSheet(jpegBytesList[i], i, CornerKeypointDetector.Provider.CPU4T, warmup = true)
            if (detector.nnapiAvailable)
                processSheet(jpegBytesList[i], i, CornerKeypointDetector.Provider.NNAPI, warmup = true)
        }

        ResourceMonitor.setPhase("CPU1T")
        captures.clear()
        emit(onProgress, "── CPU-1T (${jpegBytesList.size}) ──")
        for ((idx, bytes) in jpegBytesList.withIndex()) {
            ResourceMonitor.setSheet(idx + 1)
            processSheet(bytes, idx, CornerKeypointDetector.Provider.CPU)
        }

        if (detector.nnapiAvailable) {
            ResourceMonitor.setPhase("NNAPI")
            emit(onProgress, "── NNAPI (${jpegBytesList.size}) ──")
            for ((idx, bytes) in jpegBytesList.withIndex()) {
                ResourceMonitor.setSheet(idx + 1)
                processSheet(bytes, idx, CornerKeypointDetector.Provider.NNAPI)
            }
        }

        ResourceMonitor.setPhase("CPU4T")
        emit(onProgress, "── CPU-4T (${jpegBytesList.size}) ──")
        for ((idx, bytes) in jpegBytesList.withIndex()) {
            ResourceMonitor.setSheet(idx + 1)
            processSheet(bytes, idx, CornerKeypointDetector.Provider.CPU4T)
        }

        ResourceMonitor.setPhase("done")
        freqJob.cancel(); resJob.cancel(); ResourceMonitor.stop()
        emit(onProgress, "===== CORNER BENCHMARK DONE =====")
    }

    private fun processSheet(
        jpegBytes: ByteArray,
        idx:       Int,
        provider:  CornerKeypointDetector.Provider,
        warmup:    Boolean = false
    ) {
        detector.activeProvider = provider
        val label = sourceLabels?.getOrNull(idx)
            ?: if (warmup) "warmup${idx}" else "bench_${provider.name.lowercase()}_${idx}"
        try {
            val t0  = SystemClock.elapsedRealtime()
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: error("BitmapFactory returned null")
            val jpegDecodeMs = SystemClock.elapsedRealtime() - t0
            val result = processor.process(bmp, sourceFile = label)
            val e2eMs = SystemClock.elapsedRealtime() - t0
            if (warmup) return
            val st = processor.lastStageTimes
            val jpegWriteMs = st?.jpegWriteMs ?: 0L
            val answered = result.answers.count { it.value.isNotEmpty() }
            val multi    = result.answers.count { it.value.size >= 2 }
            val yoloPostMs = (detector.lastStageMs - detector.lastPrepMs - detector.lastOrtMs).coerceAtLeast(0L)
            val list = captures.getOrPut(provider.name) { mutableListOf() }
            list += SheetCapture(
                sheet = idx + 1, provider = provider.name,
                cppMs = (e2eMs - jpegDecodeMs - jpegWriteMs).coerceAtLeast(0L),
                e2eMs = e2eMs, ortMs = detector.lastOrtMs,
                jpegDecodeMs = jpegDecodeMs, jpegWriteMs = jpegWriteMs, failed = false,
                examCode = result.examCode, mssv = result.mssv,
                mssvValid = result.mssv != null, keyValid = result.examCode != null,
                answered = answered, suspicious = result.suspicious.size, multi = multi,
                sourceFile = sourceLabels?.getOrNull(idx), answers = result.answers,
                bitmapToMatMs = st?.bitmapToMatMs ?: 0L, normalizeMs = st?.normalizeMs ?: 0L,
                threshMs = st?.threshMs ?: 0L,
                omrDetectMs = st?.let { it.mssvMs + it.examKeyMs + it.answersMs } ?: 0L,
                yoloPrepMs = detector.lastPrepMs, yoloPostMs = yoloPostMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "[${provider.name}] sheet=${idx + 1} EXCEPTION: ${e.message}")
            if (!warmup) {
                captures.getOrPut(provider.name) { mutableListOf() } += SheetCapture(
                    sheet = idx + 1, provider = provider.name,
                    cppMs = 0, e2eMs = 0, ortMs = 0, jpegDecodeMs = 0, jpegWriteMs = 0,
                    failed = true, examCode = null, mssv = null, mssvValid = false, keyValid = false,
                    answered = 0, suspicious = 0, multi = 0, sourceFile = sourceLabels?.getOrNull(idx)
                )
            }
        }
    }

    private fun emit(cb: ((String) -> Unit)?, msg: String) {
        Log.i(TAG, msg); cb?.invoke(msg)
    }
}
