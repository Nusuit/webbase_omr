package com.gradesnap.omr

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/** Per-call stage breakdown exposed for benchmark runners. */
data class StageTimes(
    val bitmapToMatMs: Long,
    val normalizeMs:   Long,
    val jpegWriteMs:   Long,
    val threshMs:      Long,
    val mssvMs:        Long,
    val examKeyMs:     Long,
    val answersMs:     Long
)

/**
 * Orchestrator: nhận Bitmap → normalize → threshold → detect → trả về RawScanResult
 *
 * @param paperDetector  Stage 1 normalize strategy. Defaults to NormalizePaper (traditional OpenCV).
 *                       Pass YoloPaperDetector.getInstance(context) for the YOLO benchmark path.
 */
class OmrProcessor(
    private val context: Context,
    private val paperDetector: PaperDetector = PaperDetector { NormalizePaper.normalize(it) },
    // Stage 2 (MarkerCrop) re-detects markers on the warped image and tight-crops so the
    // grid aligns. Needed for the traditional-CV path (matches web/WASM). The corner-warp
    // path already maps markers onto the canvas corners, so Stage 2 is skipped there
    // (web: `if (!used_corner_warp)`); harmful on faint sheets otherwise.
    private val applyStage2: Boolean = true
) {

    private val config: OmrConfig by lazy { ConfigLoader.load(context) }

    /** Populated after every process() call; null before first call. */
    var lastStageTimes: StageTimes? = null
        private set

    /**
     * Xử lý ảnh học viên → RawScanResult đầy đủ (MSSV, mã đề, câu trả lời, suspicious).
     *
     * @param bitmap       ảnh gốc từ camera / gallery
     * @param sourceFile   tên file gốc (để đặt tên warped image)
     * @param isAnswerKey  true → không đánh suspicious trên bất kỳ câu nào
     * @param logCallback  callback nhận từng dòng log (chạy trong thread IO)
     */
    fun process(
        bitmap: Bitmap,
        sourceFile: String = "scan",
        isAnswerKey: Boolean = false,
        logCallback: ((String) -> Unit)? = null
    ): RawScanResult {
        log(logCallback, "Bắt đầu xử lý: $sourceFile")

        val startTime = android.os.SystemClock.elapsedRealtime()
        val startMem  = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        var t         = android.os.SystemClock.elapsedRealtime()

        // 1. Bitmap → BGR Mat (bitmapToMat gives BGRA; strip alpha for OpenCV stages)
        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)
        val img = Mat()
        Imgproc.cvtColor(raw, img, Imgproc.COLOR_BGRA2BGR)
        raw.release()
        val bitmapToMatMs = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        log(logCallback, "Đã load ảnh: ${img.cols()}×${img.rows()} px")

        // 2. Normalize (warp perspective) — via injected PaperDetector
        var warped = paperDetector.normalize(img)
        // 2b. Stage 2: tight crop by corner markers (CV path only; corner-warp skips it).
        if (applyStage2) {
            val refined = MarkerCrop.cropByMarkers(warped)
            if (refined !== warped) { warped.release(); warped = refined }
        }
        val normalizeMs = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        log(logCallback, "Normalize xong: ${warped.cols()}×${warped.rows()} px")

        // 3. Lưu warped image ra file để dùng trong preview
        val warpedPath = saveWarpedImage(warped, sourceFile)
        val jpegWriteMs = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        log(logCallback, "Đã lưu warped image: $warpedPath")

        // 4. Binary threshold
        val binary = BubbleDetector.preprocess(warped)
        val threshMs = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        log(logCallback, "Binary threshold xong")

        // 5-7. Density-based reading. Circular density sampling + per-sheet global
        //       density normalization + per-row z-score decision, ported from the
        //       C++ reference src/core/omr_core.cpp (Rule E). Replaces the old
        //       rect white-ratio + fixed 15% threshold, which over-detected on
        //       low-contrast photos. Two passes: (1) sample every cell-centre
        //       density to estimate the sheet's ink level; (2) decide.
        val options = config.questions.options

        // Pass 1 — sample densities.
        val mssvGrid = config.mssv?.let { densityGrid(binary, it.x1, it.y1, it.x2, it.y2, it.cols, it.rows) }
        val keyGrid  = config.key?.let  { densityGrid(binary, it.x1, it.y1, it.x2, it.y2, it.cols, it.rows) }
        val blockGrids = config.questions.blocks.map { b ->
            b to densityGrid(binary, b.x1, b.y1, b.x2, b.y2, b.cols, b.rows)
        }

        // global_avg_density = mean of confidently-filled bubbles (density > 0.35),
        // default 0.5 when none — matches the C++ reference.
        val confident = ArrayList<Double>()
        mssvGrid?.forEach { row -> row.forEach { if (it > 0.35) confident.add(it) } }
        keyGrid?.forEach  { row -> row.forEach { if (it > 0.35) confident.add(it) } }
        blockGrids.forEach { (_, g) -> g.forEach { row -> row.forEach { if (it > 0.35) confident.add(it) } } }
        val globalAvg = if (confident.isEmpty()) 0.5 else confident.average()

        // Pass 2 — decisions.
        val mssv     = mssvGrid?.let { readDigits(it, globalAvg) }
        val mssvMs   = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        log(logCallback, "MSSV: ${mssv ?: "không đọc được"}")

        val examCode = keyGrid?.let { readDigits(it, globalAvg) }
        val examKeyMs = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        log(logCallback, "Mã đề: ${examCode ?: "không đọc được"}")

        val answers    = mutableMapOf<Int, Set<String>>()
        val suspicious = mutableSetOf<Int>()
        val rawRatios  = mutableMapOf<Int, List<Double>>()
        for ((block, grid) in blockGrids) {
            for (row in 0 until block.rows) {
                val qNum = block.questionStart + row
                val ds   = grid[row]
                rawRatios[qNum] = ds.toList()
                val maxD = ds.maxOrNull() ?: 0.0
                if (maxD < 0.15) { answers[qNum] = emptySet(); continue }  // row empty
                val mean = ds.average()
                val std  = kotlin.math.sqrt(ds.sumOf { (it - mean) * (it - mean) } / ds.size)
                // Filled if the bubble exceeds the absolute floor AND stands out
                // from its row neighbours by more than 1σ (z-score gate).
                val filled = (ds.indices).filter { c -> ds[c] > 0.15 && ds[c] > mean + std }
                answers[qNum] = filled.mapNotNull { options.getOrNull(it) }.toSet()
                if (!isAnswerKey && filled.size > 1) suspicious.add(qNum)
            }
        }
        val answersMs = android.os.SystemClock.elapsedRealtime() - t

        log(logCallback, "Hoàn tất: ${answers.size} câu, ${suspicious.size} suspicious")
        img.release(); warped.release(); binary.release()

        lastStageTimes = StageTimes(bitmapToMatMs, normalizeMs, jpegWriteMs, threshMs, mssvMs, examKeyMs, answersMs)

        val endTime   = android.os.SystemClock.elapsedRealtime()
        val endMem    = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memUsedBytes = (endMem - startMem).coerceAtLeast(0)
        val memUsedMb    = memUsedBytes / (1024f * 1024f)

        PerformanceTelemetry.logAndExport(context, endTime - startTime, memUsedMb)

        return RawScanResult(
            mssv = mssv,
            examCode = examCode,
            answers = answers,
            suspicious = suspicious,
            rawRatios = rawRatios,
            warpedImagePath = warpedPath
        )
    }

    // ─── Density grid sampling (port of C++ omr_core.cpp) ───────────────────────
    /**
     * Tính density tại tâm từng cell của một region/block trên ảnh binary.
     * Tâm cell (row r, col c) = (x1 + (c+0.5)·cellW, y1 + (r+0.5)·cellH), lấy mẫu
     * trong hình tròn bán kính SAMPLE_R. Trả về grid[row][col].
     */
    private fun densityGrid(
        binary: Mat, x1: Int, y1: Int, x2: Int, y2: Int, cols: Int, rows: Int
    ): Array<DoubleArray> {
        val cellW = (x2 - x1).toDouble() / cols
        val cellH = (y2 - y1).toDouble() / rows
        return Array(rows) { r ->
            val cy = Math.round(y1 + (r + 0.5) * cellH).toInt()
            DoubleArray(cols) { c ->
                val cx = Math.round(x1 + (c + 0.5) * cellW).toInt()
                BubbleDetector.circleDensity(binary, cx, cy)
            }
        }
    }

    /**
     * Đọc số digit-per-column từ density grid (mỗi cột = 1 chữ số 0-9 = row có
     * density cao nhất). Khớp rule MSSV/KEY của C++: chọn nếu density tốt nhất
     * > 0.20 và ≥ globalAvg·0.40. Trả null nếu bất kỳ cột nào không xác định.
     */
    private fun readDigits(grid: Array<DoubleArray>, globalAvg: Double): String? {
        if (grid.isEmpty()) return null
        val rows = grid.size
        val cols = grid[0].size
        val sb = StringBuilder()
        for (c in 0 until cols) {
            var bestRow = -1; var bestD = -1.0
            for (r in 0 until rows) {
                if (grid[r][c] > bestD) { bestD = grid[r][c]; bestRow = r }
            }
            if (bestRow >= 0 && bestD > 0.20 && bestD >= globalAvg * 0.40) {
                sb.append(bestRow.toString())
            } else {
                return null
            }
        }
        return sb.toString()
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

    private fun log(callback: ((String) -> Unit)?, msg: String) {
        callback?.invoke(msg)
    }
}
