package com.gradesnap.omr

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Headless N=179 benchmark runner.
 *
 * Triggered by launching BenchmarkActivity with extras:
 *   --es headless true
 *   --es method   cv|yolo
 *   --es manifest /sdcard/Download/manifest_n179_native.json   (JSON array of absolute paths)
 *   --es outDir   /sdcard/Android/data/com.gradesnap.omr/files/Documents/   (optional)
 *   --es tag      redmi_note13_pro_plus_native                  (optional, used in output filename)
 *
 * Writes per-sheet JSON matching the Web schema produced by web/batch-detect.html:
 *   List< { url, status, examCode, mssv, mssvValid, keyValid, answered, suspicious, multi, cpp_ms } >
 *
 * For CV:    one file -> native_results_cv_n<N>.json
 * For YOLO:  three files -> native_results_yolo_{cpu1,cpu4,nnapi}_n<N>.json
 */
object HeadlessBenchmark {
    private const val TAG = "HeadlessBenchmark"
    const val EXTRA_HEADLESS = "headless"
    const val EXTRA_METHOD   = "method"
    const val EXTRA_MANIFEST = "manifest"
    const val EXTRA_OUT_DIR  = "outDir"
    const val EXTRA_TAG      = "tag"

    fun shouldHandle(intent: Intent?): Boolean {
        return intent?.getStringExtra(EXTRA_HEADLESS) == "true"
    }

    /** Returns a status string for logcat. */
    fun run(context: Context, intent: Intent, onProgress: ((String) -> Unit)? = null): String {
        val method   = intent.getStringExtra(EXTRA_METHOD)?.lowercase() ?: "cv"
        val manifest = intent.getStringExtra(EXTRA_MANIFEST)
            ?: return "ERROR: missing --es manifest <path>"
        val outDirPath = intent.getStringExtra(EXTRA_OUT_DIR)
            ?: context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?.absolutePath
            ?: return "ERROR: outDir unresolved"
        val tag = intent.getStringExtra(EXTRA_TAG) ?: "native"

        emit(onProgress, "[HEADLESS] method=$method manifest=$manifest outDir=$outDirPath tag=$tag")

        val manifestFile = File(manifest)
        if (!manifestFile.exists()) return "ERROR: manifest not found at $manifest"
        val paths: List<String> = try {
            val arr = JSONArray(manifestFile.readText())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            return "ERROR: cannot parse manifest: ${e.message}"
        }
        emit(onProgress, "[HEADLESS] manifest entries: ${paths.size}")

        val jpegBytes = mutableListOf<ByteArray>()
        val labels    = mutableListOf<String>()
        for ((i, p) in paths.withIndex()) {
            val f = File(p)
            if (!f.exists()) {
                emit(onProgress, "[HEADLESS] WARN missing image[$i] $p — skipping")
                continue
            }
            try {
                jpegBytes += f.readBytes()
                labels    += p
            } catch (e: Exception) {
                emit(onProgress, "[HEADLESS] WARN read fail[$i] $p — ${e.message}")
            }
        }
        if (jpegBytes.isEmpty()) return "ERROR: no images loaded"
        emit(onProgress, "[HEADLESS] loaded ${jpegBytes.size}/${paths.size} images")

        val outDir = File(outDirPath).apply { mkdirs() }
        val n = jpegBytes.size

        return try {
            when (method) {
                "cv" -> {
                    val runner = CvBenchmarkRunner(context)
                    runner.runWithLabels(jpegBytes, labels, onProgress)
                    val out = File(outDir, "native_results_cv_n${n}.json")
                    writeJson(out, runner.captures)
                    emit(onProgress, "[HEADLESS] wrote ${out.absolutePath}")
                    "OK: cv N=$n -> ${out.absolutePath}"
                }
                "yolo" -> {
                    val runner = YoloBenchmarkRunner(context)
                    runner.runWithLabels(jpegBytes, labels, onProgress)
                    val files = mutableListOf<String>()
                    val mapping = mapOf(
                        "CPU"   to "native_results_yolo_cpu1_n${n}.json",
                        "CPU4T" to "native_results_yolo_cpu4_n${n}.json",
                        "NNAPI" to "native_results_yolo_nnapi_n${n}.json"
                    )
                    for ((provKey, fname) in mapping) {
                        val caps = runner.captures[provKey] ?: continue
                        if (caps.isEmpty()) continue
                        val out = File(outDir, fname)
                        writeJson(out, caps)
                        files += out.absolutePath
                        emit(onProgress, "[HEADLESS] wrote ${out.absolutePath} (${caps.size} sheets)")
                    }
                    "OK: yolo N=$n -> ${files.joinToString(", ")}"
                }
                "corner" -> {
                    val runner = CornerBenchmarkRunner(context)
                    runner.runWithLabels(jpegBytes, labels, onProgress)
                    val files = mutableListOf<String>()
                    val mapping = mapOf(
                        "CPU"   to "native_results_corner_cpu1_n${n}.json",
                        "CPU4T" to "native_results_corner_cpu4_n${n}.json",
                        "NNAPI" to "native_results_corner_nnapi_n${n}.json"
                    )
                    for ((provKey, fname) in mapping) {
                        val caps = runner.captures[provKey] ?: continue
                        if (caps.isEmpty()) continue
                        val out = File(outDir, fname)
                        writeJson(out, caps)
                        files += out.absolutePath
                        emit(onProgress, "[HEADLESS] wrote ${out.absolutePath} (${caps.size} sheets)")
                    }
                    "OK: corner N=$n -> ${files.joinToString(", ")}"
                }
                else -> "ERROR: unknown method=$method (use cv|yolo|corner)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "headless run failed", e)
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun writeJson(out: File, caps: List<SheetCapture>) {
        val arr = JSONArray()
        for (c in caps) {
            val obj = JSONObject()
            obj.put("url",        c.sourceFile ?: "")
            obj.put("sheet",      c.sheet)
            obj.put("provider",   c.provider)
            obj.put("status",     if (c.failed) 1 else 0)
            obj.put("examCode",   c.examCode ?: JSONObject.NULL)
            obj.put("mssv",       c.mssv ?: JSONObject.NULL)
            obj.put("mssvValid",  c.mssvValid)
            obj.put("keyValid",   c.keyValid)
            obj.put("answered",   c.answered)
            obj.put("suspicious", c.suspicious)
            obj.put("multi",      c.multi)
            obj.put("cpp_ms",     c.cppMs)
            obj.put("e2e_ms",     c.e2eMs)
            obj.put("ort_ms",     c.ortMs)
            obj.put("jpeg_decode_ms", c.jpegDecodeMs)
            obj.put("jpeg_write_ms",  c.jpegWriteMs)
            // Same-boundary stage timers for cross-platform decomposition (A3/A4)
            obj.put("bitmap_to_mat_ms", c.bitmapToMatMs)
            obj.put("normalize_ms",     c.normalizeMs)
            obj.put("thresh_ms",        c.threshMs)
            obj.put("omr_detect_ms",    c.omrDetectMs)
            obj.put("yolo_prep_ms",     c.yoloPrepMs)
            obj.put("yolo_post_ms",     c.yoloPostMs)
            // Per-question predictions auto_q01..auto_q60, matching the Web review/eval schema:
            // single option "A", multi-mark "A+B" (alphabetical, joined by '+'), blank "".
            for (q in 1..60) {
                val opts = c.answers[q]
                val v = if (opts.isNullOrEmpty()) "" else opts.sorted().joinToString("+")
                obj.put("auto_q%02d".format(q), v)
            }
            arr.put(obj)
        }
        out.writeText(arr.toString(2))
    }

    private fun emit(cb: ((String) -> Unit)?, msg: String) {
        Log.i(TAG, msg)
        cb?.invoke(msg)
    }
}
