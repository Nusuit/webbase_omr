package com.gradesnap.omr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.max

/**
 * Orchestrator — ported from web C++ omr_core.cpp.
 *
 * Pipeline:
 *   1. Bitmap → BGR Mat
 *   2. Stage 1: Perspective normalize (via flavor-specific PaperDetector) → 1700×2400
 *   3. Stage 2: Tight crop by registration markers (re-detect 4 corners, re-warp) → 1700×2400
 *   4. Adaptive threshold (block=31, offset=5)
 *   5. Grid analysis: circular density sampling with adaptive thresholds
 */
class OmrProcessor(private val context: Context) {

    companion object {
        private const val TAG = "OmrProcessor"
        private const val TARGET_W = 1700
        private const val TARGET_H = 2400
    }

    private val config: OmrConfig by lazy { ConfigLoader.load(context) }
    private val detector: PaperDetector by lazy { PaperDetectorProvider.get(context) }

    fun process(
        bitmap: Bitmap,
        sourceFile: String = "scan",
        isAnswerKey: Boolean = false,
        logCallback: ((String) -> Unit)? = null
    ): RawScanResult {
        val t0 = System.currentTimeMillis()
        log(logCallback, "Bắt đầu xử lý: $sourceFile")

        // 1. Bitmap → BGR Mat
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)
        val img = Mat()
        Imgproc.cvtColor(raw, img, Imgproc.COLOR_BGRA2BGR)
        raw.release()
        log(logCallback, "Load ảnh: ${img.cols()}×${img.rows()} (${img.channels()}ch BGR)")

        // 2. Stage 1: Perspective normalize → 1700×2400
        val tNorm = System.currentTimeMillis()
        val warped1 = detector.normalize(img)
        val normMs = System.currentTimeMillis() - tNorm
        log(logCallback, "Stage1 normalize: ${warped1.cols()}×${warped1.rows()} (${normMs}ms)")

        // 3. Stage 2: Tight crop by registration markers (matches web C++ omr_core.cpp:115-187)
        val tCrop = System.currentTimeMillis()
        val warped = cropByMarkers(warped1)
        val cropMs = System.currentTimeMillis() - tCrop
        warped1.release()
        log(logCallback, "Stage2 tight crop: ${warped.cols()}×${warped.rows()} (${cropMs}ms)")

        // Save warped image for preview
        val warpedPath = saveWarpedImage(warped, sourceFile)

        // 4. Adaptive threshold (matches web C++ omr_core.cpp:196-197)
        val binary = BubbleDetector.preprocess(warped)
        log(logCallback, "Adaptive threshold done (${binary.cols()}×${binary.rows()})")

        // Save binary debug image
        saveBinaryDebug(binary, sourceFile)

        // 5. Grid analysis — collect ALL densities first, then compute global avg
        val allDensities = mutableListOf<Double>()
        val cellCenters = mutableListOf<Triple<String, Int, Pair<Int, Int>>>() // (region, index, cx/cy)

        // Collect densities for MSSV
        val mssvRegion = config.mssv
        val mssvDensities = mutableListOf<List<Double>>() // per-column
        if (mssvRegion != null) {
            val cellW = (mssvRegion.x2 - mssvRegion.x1) / mssvRegion.cols
            val cellH = (mssvRegion.y2 - mssvRegion.y1) / mssvRegion.rows
            for (col in 0 until mssvRegion.cols) {
                val colD = mutableListOf<Double>()
                for (row in 0 until mssvRegion.rows) {
                    val cx = mssvRegion.x1 + col * cellW + cellW / 2
                    val cy = mssvRegion.y1 + row * cellH + cellH / 2
                    val d = BubbleDetector.circularDensity(binary, cx, cy)
                    colD.add(d)
                    allDensities.add(d)
                }
                mssvDensities.add(colD)
            }
        }

        // Collect densities for KEY
        val keyRegion = config.key
        val keyDensities = mutableListOf<List<Double>>()
        if (keyRegion != null) {
            val cellW = (keyRegion.x2 - keyRegion.x1) / keyRegion.cols
            val cellH = (keyRegion.y2 - keyRegion.y1) / keyRegion.rows
            for (col in 0 until keyRegion.cols) {
                val colD = mutableListOf<Double>()
                for (row in 0 until keyRegion.rows) {
                    val cx = keyRegion.x1 + col * cellW + cellW / 2
                    val cy = keyRegion.y1 + row * cellH + cellH / 2
                    val d = BubbleDetector.circularDensity(binary, cx, cy)
                    colD.add(d)
                    allDensities.add(d)
                }
                keyDensities.add(colD)
            }
        }

        // Collect densities for question blocks
        data class BlockDensities(val block: QuestionBlock, val rowDensities: List<List<Double>>)
        val blockDensitiesList = mutableListOf<BlockDensities>()
        for (block in config.questions.blocks) {
            val cellW = (block.x2 - block.x1) / block.cols
            val cellH = (block.y2 - block.y1) / block.rows
            val rows = mutableListOf<List<Double>>()
            for (row in 0 until block.rows) {
                val rowD = mutableListOf<Double>()
                for (col in 0 until block.cols) {
                    val cx = block.x1 + col * cellW + cellW / 2
                    val cy = block.y1 + row * cellH + cellH / 2
                    val d = BubbleDetector.circularDensity(binary, cx, cy)
                    rowD.add(d)
                    allDensities.add(d)
                }
                rows.add(rowD)
            }
            blockDensitiesList.add(BlockDensities(block, rows))
        }

        // Compute global average density (matches web C++)
        val globalAvg = BubbleDetector.computeGlobalAvgDensity(allDensities)
        log(logCallback, "Global avg density: ${"%.3f".format(globalAvg)} (from ${allDensities.size} bubbles)")

        // 6. Read MSSV using adaptive thresholds
        var mssv: String? = null
        if (mssvRegion != null) {
            val digits = StringBuilder()
            for (col in mssvDensities.indices) {
                val digit = BubbleDetector.analyzeNumericColumn(mssvDensities[col], globalAvg)
                val ratiosStr = mssvDensities[col].mapIndexed { i, d -> "$i=${"%.2f".format(d)}" }.joinToString(",")
                log(logCallback, "  MSSV col$col: [$ratiosStr] → digit=$digit")
                digits.append(if (digit >= 0) digit.toString() else "?")
            }
            mssv = digits.toString().let { if (it.contains("?")) { log(logCallback, "⚠ MSSV không rõ: $it"); null } else it }
            log(logCallback, "MSSV: ${mssv ?: "không đọc được"}")
        }

        // 7. Read exam key code
        var examCode: String? = null
        if (keyRegion != null) {
            val digits = StringBuilder()
            for (col in keyDensities.indices) {
                val digit = BubbleDetector.analyzeNumericColumn(keyDensities[col], globalAvg)
                val ratiosStr = keyDensities[col].mapIndexed { i, d -> "$i=${"%.2f".format(d)}" }.joinToString(",")
                log(logCallback, "  KEY col$col: [$ratiosStr] → digit=$digit")
                digits.append(if (digit >= 0) digit.toString() else "?")
            }
            examCode = digits.toString().let { if (it.contains("?")) { log(logCallback, "⚠ Mã đề không rõ: $it"); null } else it }
            log(logCallback, "Mã đề: ${examCode ?: "không đọc được"}")
        }

        // 8. Read answers with adaptive thresholds + recent history
        val answers = mutableMapOf<Int, Set<String>>()
        val suspicious = mutableSetOf<Int>()
        val rawRatios = mutableMapOf<Int, List<Double>>()
        val options = config.questions.options
        val recentHistory = mutableListOf<Double>()

        for (bd in blockDensitiesList) {
            val block = bd.block
            log(logCallback, "Block ${block.label} (Q${block.questionStart}–Q${block.questionEnd})")
            for (row in bd.rowDensities.indices) {
                val qNum = block.questionStart + row
                val densities = bd.rowDensities[row]

                val result = BubbleDetector.analyzeAnswerRow(densities, globalAvg, recentHistory, isAnswerKey)
                val filledOptions = result.filledSet.mapNotNull { idx -> options.getOrNull(idx) }.toSet()
                answers[qNum] = filledOptions
                rawRatios[qNum] = result.rawRatios

                if (result.isSuspicious) {
                    suspicious.add(qNum)
                    log(logCallback, "  ⚠ Q$qNum suspicious: ${result.rawRatios.map { "%.2f".format(it) }}")
                }
            }
        }

        val e2eMs = System.currentTimeMillis() - t0
        log(logCallback, "Done: ${answers.size} Q, ${suspicious.size} suspicious (${e2eMs}ms)")

        // [PERF] structured log
        Log.d("GradeSnapPerf", "sheet=$sourceFile e2e=${e2eMs}ms stage1=${normMs}ms bubble=${cropMs}ms")
        Log.i("GradeSnapPerf", "[PERF] {" +
            "\"file\":\"$sourceFile\"," +
            "\"input_w\":${img.cols()}," +
            "\"input_h\":${img.rows()}," +
            "\"e2e_ms\":$e2eMs," +
            "\"normalize_ms\":$normMs," +
            "\"crop_ms\":$cropMs," +
            "\"omr_status\":0" +
        "}")

        img.release(); warped.release(); binary.release()

        return RawScanResult(
            mssv = mssv,
            examCode = examCode,
            answers = answers,
            suspicious = suspicious,
            rawRatios = rawRatios,
            warpedImagePath = warpedPath
        )
    }

    // ─── Stage 2: Tight crop by registration markers ────────────────────────────
    // Matches web C++ omr_core.cpp lines 115-187.
    // After Stage 1 warp to 1700×2400, re-detect 4 corner markers and re-warp
    // to correct residual perspective error.
    private fun cropByMarkers(warped: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_BGR2GRAY)
        val th = Mat()
        Imgproc.threshold(gray, th, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_OPEN, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(th, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // Filter: area 500-20000, aspect 0.6-1.5, fill > 0.5, in outer 25% zone
        // Wider than web C++ Stage 2 to handle imperfect Stage 1 warps
        val zoneW = TARGET_W / 4   // 425 (25% — wider than web's 20%)
        val zoneH = TARGET_H / 4   // 600
        val markers = mutableListOf<Point>()
        var debugCount = 0

        for (c in contours) {
            val bound = Imgproc.boundingRect(c)
            val area = Imgproc.contourArea(c)
            if (area < 500 || area > 20000) continue
            val ar = bound.width.toDouble() / bound.height
            if (ar < 0.6 || ar > 1.5) continue
            val fill = area / (bound.width * bound.height).toDouble()
            if (fill < 0.5) continue
            val cx = bound.x + bound.width / 2.0
            val cy = bound.y + bound.height / 2.0

            // Check mean gray — must be dark
            val roi = gray.submat(bound)
            val mean = Core.mean(roi).`val`[0]
            if (mean > 120) continue

            // Must be in one of the 4 corner zones
            val inLeft = cx < zoneW
            val inRight = cx > TARGET_W - zoneW
            val inTop = cy < zoneH
            val inBottom = cy > TARGET_H - zoneH
            if ((inLeft || inRight) && (inTop || inBottom)) {
                markers.add(Point(cx, cy))
                if (debugCount < 8) {
                    Log.d(TAG, "Stage2 candidate: area=${area.toInt()} pos=(${cx.toInt()},${cy.toInt()}) ar=${"%.2f".format(ar)} fill=${"%.2f".format(fill)} gray=${mean.toInt()}")
                    debugCount++
                }
            }
        }
        gray.release(); th.release()

        if (markers.size < 4) {
            Log.d(TAG, "Stage2: only ${markers.size} markers found (of ${contours.size} contours), skipping crop")
            return warped.clone()
        }

        // Sort by y, then split into top/bottom rows, sort by x
        markers.sortBy { it.y }
        val topRow = markers.take(2).sortedBy { it.x }
        val botRow = markers.takeLast(2).sortedBy { it.x }
        val tl = topRow[0]; val tr = topRow[1]
        val bl = botRow[0]; val br = botRow[1]

        // Compute max width/height (same as web C++)
        val widthA  = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val widthB  = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
        val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
        val maxW = max(widthA.toInt(), widthB.toInt())
        val maxH = max(heightA.toInt(), heightB.toInt())

        if (maxW <= 100 || maxH <= 100) {
            Log.d(TAG, "Stage2: degenerate size ${maxW}x${maxH}, skipping")
            return warped.clone()
        }

        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((maxW - 1).toDouble(), 0.0),
            Point((maxW - 1).toDouble(), (maxH - 1).toDouble()),
            Point(0.0, (maxH - 1).toDouble())
        )
        val M = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val cropped = Mat()
        Imgproc.warpPerspective(warped, cropped, M, Size(maxW.toDouble(), maxH.toDouble()))

        // Resize back to 1700×2400
        val result = Mat()
        Imgproc.resize(cropped, result, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
        cropped.release()

        Log.i(TAG, "Stage2: markers TL=(${tl.x.toInt()},${tl.y.toInt()}) TR=(${tr.x.toInt()},${tr.y.toInt()}) " +
            "BR=(${br.x.toInt()},${br.y.toInt()}) BL=(${bl.x.toInt()},${bl.y.toInt()}) → ${maxW}x${maxH} → 1700×2400")
        return result
    }

    // ─── Save warped image ──────────────────────────────────────────────────────
    private fun saveWarpedImage(warped: Mat, sourceFile: String): String? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "warped")
            dir.mkdirs()
            val name = sourceFile.substringBeforeLast(".") + "_warped.jpg"
            val outFile = File(dir, name)
            val bmp = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, bmp)
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            bmp.recycle()
            outFile.absolutePath
        } catch (e: Exception) { null }
    }

    // ─── Save binary debug image ────────────────────────────────────────────────
    private fun saveBinaryDebug(binary: Mat, sourceFile: String) {
        try {
            val dir = File(context.getExternalFilesDir(null), "warped")
            dir.mkdirs()
            val name = sourceFile.substringBeforeLast(".") + "_binary.jpg"
            val outFile = File(dir, name)
            val bmp = Bitmap.createBitmap(binary.cols(), binary.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(binary, bmp)
            FileOutputStream(outFile).use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos) }
            bmp.recycle()
            Log.i("GradeSnapDebug", "Binary saved: ${outFile.absolutePath}")
        } catch (e: Exception) { Log.w("GradeSnapDebug", "saveBinaryDebug failed: ${e.message}") }
    }

    private fun log(callback: ((String) -> Unit)?, msg: String) {
        callback?.invoke(msg)
    }
}
