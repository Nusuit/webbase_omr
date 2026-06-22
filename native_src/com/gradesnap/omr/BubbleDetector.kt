package com.gradesnap.omr

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Phát hiện bubble được tô trên phiếu trắc nghiệm.
 * Hỗ trợ: single fill, multi-fill (khi đáp án có nhiều ô), và suspicious detection.
 */
object BubbleDetector {

    // Ngưỡng để coi là "có tô" (% white pixel)
    const val FILL_THRESHOLD = 15.0
    // Khoảng cách tối thiểu giữa max và second-max để single fill rõ ràng
    const val MIN_GAP = 3.0
    // Nếu tô 1 ô nhưng ratio chỉ vừa vượt ngưỡng → suspicious (uncertain fill)
    const val UNCERTAIN_FILL_MAX = 20.0

    /**
     * Tiền xử lý ảnh: BGR → grayscale → GaussianBlur → Otsu threshold (INV)
     * Bubble được tô đen → WHITE trên binary.
     *
     * Dùng adaptiveThreshold(MEAN_C, block=31, C=5) khớp C++ reference
     * (src/core/omr_core.cpp:310). Phải đi kèm circular density sampling
     * ([circleDensity]) + per-row z-score decision + global density normalization
     * trong OmrProcessor — đây là một hệ tích hợp, KHÔNG dùng với rect white-ratio
     * + ngưỡng cố định 15% (đã kiểm chứng: thay riêng binarization làm vỡ toàn bộ).
     */
    fun preprocess(img: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            blur, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV,
            31, 5.0
        )
        gray.release(); blur.release()
        return binary
    }

    /** Bán kính lấy mẫu density tại tâm bubble, trong không gian warp 1700×2400. */
    const val SAMPLE_R = 18

    /**
     * Density của bubble = tỉ lệ pixel white trong hình tròn bán kính [r] quanh tâm
     * (cx, cy) trên ảnh binary. Khớp circular sampling của C++ reference
     * (src/core/omr_core.cpp countCircle). Thay cho rect white-ratio vì hình tròn
     * loại góc cell và ổn định hơn với lệch grid nhỏ.
     */
    fun circleDensity(binary: Mat, cx: Int, cy: Int, r: Int = SAMPLE_R): Double {
        val w = binary.cols(); val h = binary.rows()
        val x0 = (cx - r).coerceIn(0, w - 1)
        val x1 = (cx + r).coerceIn(0, w - 1)
        val y0 = (cy - r).coerceIn(0, h - 1)
        val y1 = (cy + r).coerceIn(0, h - 1)
        if (x1 < x0 || y1 < y0) return 0.0
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val roi = binary.submat(y0, y1 + 1, x0, x1 + 1)
        val buf = ByteArray(bw * bh)
        roi.get(0, 0, buf)            // single JNI read for the whole bbox
        roi.release()
        val r2 = r * r
        var count = 0
        for (j in 0 until bh) {
            val dy = (y0 + j) - cy
            val rowBase = j * bw
            for (i in 0 until bw) {
                val dx = (x0 + i) - cx
                if (dx * dx + dy * dy <= r2 && buf[rowBase + i].toInt() != 0) count++
            }
        }
        return count.toDouble() / (Math.PI * r2)
    }

    /**
     * Tính white ratio (%) của một cell trên ảnh binary.
     */
    fun detectFilledBubble(binary: Mat, x: Int, y: Int, w: Int, h: Int): Double {
        val x0 = x.coerceAtLeast(0)
        val y0 = y.coerceAtLeast(0)
        val x1 = (x + w).coerceAtMost(binary.cols())
        val y1 = (y + h).coerceAtMost(binary.rows())
        if (x1 <= x0 || y1 <= y0) return 0.0
        val cell = binary.submat(y0, y1, x0, x1)
        val total = cell.total()
        if (total == 0L) return 0.0
        val white = Core.countNonZero(cell).toLong()
        return (white.toDouble() / total) * 100.0
    }

    /**
     * Phân tích toàn bộ một row câu hỏi → BubbleResult.
     *
     * @param isAnswerKey  true → không đánh suspicious, multi-fill là hoàn toàn hợp lệ
     */
    fun analyzeRow(
        binary: Mat,
        rowCells: List<Rect>,
        isAnswerKey: Boolean = false
    ): BubbleResult {
        val ratios = rowCells.map { rect ->
            detectFilledBubble(binary, rect.x, rect.y, rect.width, rect.height)
        }
        if (ratios.isEmpty()) return BubbleResult(emptySet(), emptyList(), false)

        // Tất cả ô vượt FILL_THRESHOLD → detected filled
        val filledIndices = ratios.indices.filter { ratios[it] >= FILL_THRESHOLD }.toSet()

        val isSuspicious = when {
            isAnswerKey -> false   // đáp án không bao giờ suspicious
            filledIndices.isEmpty() -> false
            filledIndices.size == 1 -> {
                // Tô 1 ô nhưng ratio thấp (vừa vượt ngưỡng) → uncertain
                val r = ratios[filledIndices.first()]
                r in FILL_THRESHOLD..UNCERTAIN_FILL_MAX
            }
            else -> true   // tô nhiều ô → suspicious (có thể bỏ sau khi so đáp án)
        }

        return BubbleResult(filledIndices, ratios, isSuspicious)
    }

    /**
     * Best-guess cho numeric cells (mã đề / MSSV): mỗi cột chỉ có đúng 1 chữ số.
     *
     * Linh hoạt hơn analyzeRow để xử lý tô nhẹ và nhiễu nhỏ:
     *   - Ưu tiên bubble đạt FILL_THRESHOLD rõ ràng
     *   - Nếu không có bubble nào đạt nhưng một cái vượt trội (≥8% và cách xa ≥ MIN_GAP) → chấp nhận
     *   - Nếu nhiều bubble đạt threshold nhưng một cái vượt trội hẳn → chấp nhận
     *   - Trả null nếu không xác định được
     */
    fun bestGuessNumeric(binary: Mat, colCells: List<Rect>): Int? {
        val ratios = colCells.map { r -> detectFilledBubble(binary, r.x, r.y, r.width, r.height) }
        if (ratios.isEmpty()) return null

        val indexed = ratios.mapIndexed { i, r -> i to r }.sortedByDescending { it.second }
        val (topIdx, topRatio) = indexed[0]
        val secondRatio = indexed.getOrNull(1)?.second ?: 0.0
        val gap = topRatio - secondRatio

        return when {
            // Rõ ràng nhất: đúng 1 bubble trên threshold
            topRatio >= FILL_THRESHOLD && ratios.count { it >= FILL_THRESHOLD } == 1 -> topIdx
            // Nhiều bubble trên threshold nhưng một cái vượt trội hẳn
            topRatio >= FILL_THRESHOLD && gap >= MIN_GAP * 2 -> topIdx
            // Tô nhẹ (chưa đến threshold) nhưng có một cái rõ ràng nhất
            topRatio >= 8.0 && gap >= MIN_GAP -> topIdx
            else -> null
        }
    }

    /**
     * Legacy: tìm single option để giữ backward compat với OmrProcessor cũ.
     */
    fun findFilledOption(
        binary: Mat,
        rowCells: List<Rect>,
        threshold: Double = FILL_THRESHOLD,
        minGap: Double = MIN_GAP
    ): Int? {
        val result = analyzeRow(binary, rowCells, isAnswerKey = false)
        return if (result.filledSet.size == 1) result.filledSet.first() else null
    }
}
