package com.gradesnap.omr

import android.graphics.*

/**
 * Vẽ overlay đáp án lên ảnh warped của học viên:
 *   - ✅ Khoanh XANH LÁ: bubble đúng (học viên tô đúng đáp án)
 *   - ❌ Khoanh ĐỎ: bubble sai (học viên tô nhưng không đúng)
 *   - ⭕ Khoanh XANH LÁ ĐỨNG (outline only): đáp án đúng mà học viên không tô
 *   - ⚠ Chấm CAM: câu suspicious (sau khi đã so với đáp án)
 *
 * Tọa độ bubble được tính từ OmrConfig (giống OmrProcessor).
 */
object OverlayRenderer {

    private val paintCorrect = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val paintCorrectFill = Paint().apply {
        color = Color.parseColor("#3300CC44")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintWrong = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val paintMissed = Paint().apply {    // đáp án đúng mà học viên bỏ qua
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        isAntiAlias = true
    }

    private val paintSuspicious = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintText = Paint().apply {
        color = Color.parseColor("#FF5722")
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    /**
     * Tạo bản copy của warped bitmap với overlay được vẽ lên.
     *
     * @param warped   ảnh warped gốc (không bị modify)
     * @param config   OmrConfig để tính tọa độ cell
     * @param results  danh sách QuestionResult để biết đúng/sai/suspicious
     */
    fun render(warped: Bitmap, config: OmrConfig, results: List<QuestionResult>): Bitmap {
        val out = warped.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val options = config.questions.options
        val resultMap = results.associateBy { it.questionNum }

        for (block in config.questions.blocks) {
            val blockW = block.x2 - block.x1
            val blockH = block.y2 - block.y1
            val cellW  = (blockW / block.cols).toFloat()
            val cellH  = (blockH / block.rows).toFloat()

            for (row in 0 until block.rows) {
                val qNum = block.questionStart + row
                val qResult = resultMap[qNum] ?: continue

                for (col in 0 until block.cols) {
                    val opt = options.getOrNull(col) ?: continue
                    val cx = block.x1 + col * cellW + cellW / 2
                    val cy = block.y1 + row * cellH + cellH / 2
                    val r  = (minOf(cellW, cellH) * 0.38f)

                    val studentFilled = opt in qResult.studentAnswer
                    val isCorrectOpt  = opt in qResult.correctAnswer

                    when {
                        studentFilled && isCorrectOpt -> {
                            // Tô đúng → xanh lá filled + outline
                            canvas.drawCircle(cx, cy, r, paintCorrectFill)
                            canvas.drawCircle(cx, cy, r, paintCorrect)
                        }
                        studentFilled && !isCorrectOpt -> {
                            // Tô sai → đỏ
                            canvas.drawCircle(cx, cy, r, paintWrong)
                            drawCross(canvas, cx, cy, r * 0.6f)
                        }
                        !studentFilled && isCorrectOpt -> {
                            // Bỏ ô đúng → xanh dashed
                            canvas.drawCircle(cx, cy, r, paintMissed)
                        }
                    }
                }

                // Suspicious badge
                if (qResult.isSuspicious) {
                    val badgeCx = (block.x1 - 20).toFloat()
                    val badgeCy = block.y1 + row * cellH + cellH / 2
                    canvas.drawCircle(badgeCx, badgeCy, 10f, paintSuspicious)
                    canvas.drawText("?", badgeCx - 6f, badgeCy + 8f, paintText)
                }
            }
        }
        return out
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, arm: Float) {
        val p = Paint().apply {
            color = Color.parseColor("#F44336")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, p)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, p)
    }
}
