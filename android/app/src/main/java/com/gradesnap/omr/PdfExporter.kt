package com.gradesnap.omr

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Xuất kết quả chấm bài ra PDF.
 * Hỗ trợ:
 *   - PDF tổng hợp cả lớp (1 trang / học viên — bảng kết quả)
 *   - PDF chi tiết từng học viên (kèm ảnh warped + overlay nếu muốn)
 */
object PdfExporter {

    private val pageWidth  = 595   // A4 72dpi
    private val pageHeight = 842

    private val titlePaint = Paint().apply {
        color = Color.parseColor("#1976D2")
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val headerPaint = Paint().apply {
        color = Color.parseColor("#37474F")
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val bodyPaint = Paint().apply {
        color = Color.parseColor("#212121")
        textSize = 12f
        isAntiAlias = true
    }
    private val greenPaint = Paint().apply {
        color = Color.parseColor("#388E3C")
        textSize = 12f
        isAntiAlias = true
    }
    private val redPaint = Paint().apply {
        color = Color.parseColor("#D32F2F")
        textSize = 12f
        isAntiAlias = true
    }
    private val orangePaint = Paint().apply {
        color = Color.parseColor("#F57C00")
        textSize = 12f
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // ─── Class summary PDF ─────────────────────────────────────────────────────

    fun exportClassSummary(
        context: Context,
        projectName: String,
        results: List<StudentResult>,
        studentMap: Map<String, String> = emptyMap()
    ): File {
        val doc = PdfDocument()
        val sorted = ScoringEngine.sortedByScore(results)

        var page = startPage(doc, 1)
        var canvas = page.canvas
        var y = 40f

        // Title
        canvas.drawText("GradeSnap — Kết quả: $projectName", 40f, y, titlePaint); y += 28f
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText("Xuất lúc: $now", 40f, y, bodyPaint); y += 16f
        canvas.drawLine(40f, y, pageWidth - 40f, y, linePaint); y += 16f

        // Stats
        canvas.drawText(
            "Tổng: ${results.size}  |  TB: ${"%.2f".format(ScoringEngine.average(results))}  " +
            "|  Điểm 10: ${ScoringEngine.countPerfect(results)}  " +
            "|  Điểm 0: ${ScoringEngine.countZero(results)}  " +
            "|  Suspicious: ${ScoringEngine.countWithSuspicious(results)}",
            40f, y, headerPaint
        ); y += 20f
        canvas.drawLine(40f, y, pageWidth - 40f, y, linePaint); y += 12f

        // Table header
        drawRow(canvas, y, "#", "MSSV", "Tên", "Mã đề", "Điểm", "Đúng/Tổng", "Nghi", isHeader = true)
        y += 18f
        canvas.drawLine(40f, y, pageWidth - 40f, y, linePaint); y += 6f

        sorted.forEachIndexed { idx, r ->
            if (y > pageHeight - 60) {
                doc.finishPage(page)
                val nextPage = startPage(doc, doc.pages.size + 1)
                page = nextPage
                canvas = page.canvas
                y = 40f
            }
            val suspFlag = if (r.hasSuspicious) "⚠" else ""
            val studentName = r.mssv?.let { studentMap[it] } ?: ""
            drawRow(
                canvas, y,
                "${idx + 1}",
                r.mssv ?: r.id,
                studentName,
                r.examCode ?: "?",
                "%.2f".format(r.score),
                "${r.rawScore}/${r.totalQuestions}",
                suspFlag
            )
            y += 18f
        }

        doc.finishPage(page)
        return saveDoc(context, doc, "${projectName}_summary")
    }

    // ─── Individual student PDF ────────────────────────────────────────────────

    fun exportStudentResult(context: Context, result: StudentResult, config: OmrConfig?): File {
        val doc = PdfDocument()
        val page = startPage(doc, 1)
        val canvas = page.canvas
        var y = 40f

        canvas.drawText("GradeSnap — Chi tiết bài làm", 40f, y, titlePaint); y += 24f
        canvas.drawText("MSSV: ${result.mssv ?: "N/A"}   Mã đề: ${result.examCode ?: "N/A"}", 40f, y, headerPaint); y += 18f
        canvas.drawText("Điểm: ${"%.2f".format(result.score)} (${result.rawScore}/${result.totalQuestions} câu đúng)", 40f, y, headerPaint); y += 18f
        if (result.hasSuspicious) {
            canvas.drawText("⚠ Có câu suspicious – cần kiểm tra!", 40f, y, orangePaint); y += 18f
        }
        canvas.drawLine(40f, y, pageWidth - 40f, y, linePaint); y += 12f

        // Answer table (3 columns)
        val cols = 3
        val colW = (pageWidth - 80f) / cols
        val chunks = result.questions.chunked((result.questions.size + cols - 1) / cols)

        val startY = y
        var maxY = startY

        chunks.forEachIndexed { colIdx, chunk ->
            var cy = startY
            val cx = 40f + colIdx * colW
            chunk.forEach { q ->
                val studentStr = if (q.studentAnswer.isEmpty()) "—" else q.studentAnswer.sorted().joinToString("")
                val correctStr = q.correctAnswer.sorted().joinToString("")
                val text = "Q${q.questionNum}: $studentStr / $correctStr"
                val paint = when {
                    q.isSuspicious -> orangePaint
                    q.isCorrect    -> greenPaint
                    else           -> redPaint
                }
                canvas.drawText(text, cx, cy, paint)
                cy += 15f
                if (cy > maxY) maxY = cy
            }
        }
        y = maxY + 12f

        // Warped image preview (if available + fits)
        if (result.warpedImagePath != null && y < pageHeight - 200) {
            try {
                val bmp = BitmapFactory.decodeFile(result.warpedImagePath)
                if (bmp != null) {
                    val availH = (pageHeight - y - 20).toInt()
                    val availW = pageWidth - 80
                    val ratio = bmp.width.toFloat() / bmp.height
                    val drawW = minOf(availW, (availH * ratio).toInt())
                    val drawH = (drawW / ratio).toInt()
                    val scaled = Bitmap.createScaledBitmap(bmp, drawW, drawH, true)
                    canvas.drawBitmap(scaled, 40f, y, null)
                    bmp.recycle(); scaled.recycle()
                }
            } catch (_: Exception) {}
        }

        doc.finishPage(page)
        return saveDoc(context, doc, "student_${result.id}")
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun startPage(doc: PdfDocument, pageNum: Int): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        val page = doc.startPage(info)
        page.canvas.drawColor(Color.WHITE)
        return page
    }

    private fun drawRow(
        canvas: Canvas, y: Float,
        rank: String, mssv: String, name: String, code: String,
        score: String, fraction: String, susp: String,
        isHeader: Boolean = false
    ) {
        val p = if (isHeader) headerPaint else bodyPaint
        // Columns: #, MSSV, Tên, Mã đề, Điểm, Đúng/Tổng, Nghi
        val cols = listOf(40f, 62f, 135f, 285f, 340f, 393f, 468f)
        listOf(rank, mssv, name, code, score, fraction, susp).forEachIndexed { i, text ->
            val paint = when {
                i == 6 && susp == "⚠" -> orangePaint
                isHeader -> headerPaint
                else -> p
            }
            canvas.drawText(text.take(22), cols[i], y, paint)
        }
    }

    private fun saveDoc(context: Context, doc: PdfDocument, name: String): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "GradeSnap")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "${name}_$stamp.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
