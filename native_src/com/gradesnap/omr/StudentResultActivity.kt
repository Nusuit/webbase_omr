package com.gradesnap.omr

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chi tiết kết quả 1 học viên:
 *   - Header: MSSV, mã đề, điểm, số câu đúng/tổng
 *   - Split preview: [Trái] ảnh bài làm với overlay | [Phải] ảnh đáp án với overlay
 *   - Danh sách từng câu: Câu số | Đáp án SV | Đáp án đúng | Kết quả | Suspicious
 *   - Tap vào câu → Edit manually
 *   - Menu: Export PDF, Edit All
 */
class StudentResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_STUDENT_ID = "student_id"
    }

    private lateinit var repo: ExamRepository
    private lateinit var result: StudentResult
    private lateinit var projectId: String

    private lateinit var tvHeader: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvSuspWarning: TextView
    private lateinit var tvUndetectedWarning: TextView
    private lateinit var bannerMissingExamCode: LinearLayout
    private lateinit var tvBannerMessage: TextView
    private lateinit var btnEnterExamCode: MaterialButton
    private lateinit var imgStudent: ZoomableImageView
    private lateinit var imgAnswerKey: ZoomableImageView
    private lateinit var rvAnswers: RecyclerView
    private lateinit var answerAdapter: AnswerDetailAdapter

    private var currentQuestions = mutableListOf<QuestionResult>()

    /** Chế độ preview: false = chấm điểm (xanh lá/đỏ), true = phát hiện (xanh dương). */
    private var detectMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_result)

        projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: return finish()
        val studentId = intent.getStringExtra(EXTRA_STUDENT_ID) ?: return finish()

        repo = ExamRepository(this)
        result = repo.loadAllStudentResults(projectId).find { it.id == studentId } ?: return finish()
        currentQuestions.addAll(result.questions)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = result.mssv ?: result.id
            setDisplayHomeAsUpEnabled(true)
        }

        bindViews()
        setupPreviewToggle()
        renderPreview()
        renderAnswerList()
    }

    private fun setupPreviewToggle() {
        val group = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.togglePreviewMode)
        group.check(R.id.btnModeGrade)   // mặc định: chấm điểm
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            detectMode = checkedId == R.id.btnModeDetect
            renderPreview()
        }
    }

    private fun bindViews() {
        tvHeader             = findViewById(R.id.tvStudentHeader)
        tvScore              = findViewById(R.id.tvStudentScore)
        tvSuspWarning        = findViewById(R.id.tvSuspiciousWarning)
        tvUndetectedWarning  = findViewById(R.id.tvUndetectedWarning)
        bannerMissingExamCode = findViewById(R.id.bannerMissingExamCode)
        tvBannerMessage      = findViewById(R.id.tvBannerMessage)
        btnEnterExamCode     = findViewById(R.id.btnEnterExamCode)
        imgStudent           = findViewById(R.id.imgStudent)
        imgAnswerKey         = findViewById(R.id.imgAnswerKey)
        rvAnswers            = findViewById(R.id.rvAnswerDetail)

        tvHeader.text = "MSSV: ${result.mssv ?: "N/A"}  |  Mã đề: ${result.examCode ?: "? — không đọc được"}"
        tvScore.text  = "Điểm: ${"%.2f".format(result.score)}  (${result.rawScore}/${result.totalQuestions})"

        tvSuspWarning.visibility = if (result.hasSuspicious) View.VISIBLE else View.GONE
        val suspCount = currentQuestions.count { it.isSuspicious }
        tvSuspWarning.text = "⚠ $suspCount câu nghi vấn — nhấn \"Sửa\" ở câu bất kỳ để điều chỉnh đáp án."

        val undetectedCount = currentQuestions.count { it.studentAnswer.isEmpty() }
        tvUndetectedWarning.visibility = if (undetectedCount > 0) View.VISIBLE else View.GONE
        tvUndetectedWarning.text = "⚠ $undetectedCount câu chưa đọc được — kiểm tra ảnh hoặc nhấn câu để nhập tay."

        // Hiển thị banner nếu mã đề hoặc MSSV bị thiếu
        val missingCode = result.examCode == null || currentQuestions.all { it.correctAnswer.isEmpty() }
        val missingMssv = result.mssv == null
        val bannerVisible = missingCode || missingMssv
        bannerMissingExamCode.visibility = if (bannerVisible) View.VISIBLE else View.GONE

        // Cập nhật text banner theo tình huống
        tvBannerMessage.text = when {
            missingCode && missingMssv -> "❌ Không đọc được mã đề và MSSV — cần nhập thủ công để chấm điểm."
            missingCode -> "❌ Không đọc được mã đề — học viên có thể chưa tô hoặc tô không rõ. Cần nhập mã đề để chấm điểm."
            else -> "⚠ Không đọc được MSSV — nhấn để nhập thủ công."
        }
        val btnText = when {
            missingCode && missingMssv -> "Nhập mã đề & MSSV"
            missingCode -> "Nhập mã đề"
            else -> "Nhập MSSV"
        }
        btnEnterExamCode.text = btnText
        btnEnterExamCode.setOnClickListener {
            when {
                missingCode && missingMssv -> showEnterBothDialog()
                missingCode -> showEnterExamCodeDialog()
                else -> showEnterMssvDialog()
            }
        }
    }

    private fun renderPreview() {
        lifecycleScope.launch(Dispatchers.IO) {
            val config = try { ConfigLoader.load(this@StudentResultActivity) } catch (_: Exception) { null }
            if (config == null) return@launch

            // ── Pane bài làm học viên ────────────────────────────────────────
            val studentPath = result.warpedImagePath
            if (studentPath != null && File(studentPath).exists()) {
                val bmp = BitmapFactory.decodeFile(studentPath)
                if (bmp != null) {
                    val overlay = if (detectMode) {
                        val detected   = currentQuestions.associate { it.questionNum to it.studentAnswer }
                        val undetected = currentQuestions.filter { it.studentAnswer.isEmpty() }.map { it.questionNum }.toSet()
                        val susp       = currentQuestions.filter { it.isSuspicious }.map { it.questionNum }.toSet()
                        OverlayRenderer.renderDetection(bmp, config, detected, susp, undetected)
                    } else {
                        OverlayRenderer.render(bmp, config, currentQuestions)
                    }
                    bmp.recycle()
                    withContext(Dispatchers.Main) { imgStudent.setImageBitmap(overlay) }
                }
            }

            // ── Pane đáp án chuẩn ────────────────────────────────────────────
            val key = repo.loadAnswerKey(projectId, result.examCode ?: "")
            val keyPath = key?.warpedImagePath
            if (keyPath != null && File(keyPath).exists()) {
                val keyBmp = BitmapFactory.decodeFile(keyPath)
                if (keyBmp != null) {
                    val keyOverlay = if (detectMode) {
                        val detected   = currentQuestions.associate { it.questionNum to it.correctAnswer }
                        val undetected = currentQuestions.filter { it.correctAnswer.isEmpty() }.map { it.questionNum }.toSet()
                        OverlayRenderer.renderDetection(keyBmp, config, detected, emptySet(), undetected)
                    } else {
                        val keyQuestions = currentQuestions.map { q ->
                            q.copy(studentAnswer = q.correctAnswer, isCorrect = true, isSuspicious = false)
                        }
                        OverlayRenderer.render(keyBmp, config, keyQuestions)
                    }
                    keyBmp.recycle()
                    withContext(Dispatchers.Main) { imgAnswerKey.setImageBitmap(keyOverlay) }
                }
            } else {
                withContext(Dispatchers.Main) {
                    imgAnswerKey.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        }
    }

    private fun renderAnswerList() {
        answerAdapter = AnswerDetailAdapter(currentQuestions) { question ->
            showEditDialog(question)
        }
        rvAnswers.layoutManager = LinearLayoutManager(this)
        rvAnswers.adapter = answerAdapter
    }

    /** Nhập cả mã đề và MSSV khi cả 2 bị thiếu */
    private fun showEnterBothDialog() {
        val allKeys = repo.loadAllAnswerKeys(projectId)
        val knownCodes = allKeys.keys.toList()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val codeInput = EditText(this).apply {
            hint = "Mã đề (ví dụ: 001)"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
        }
        val mssvInput = EditText(this).apply {
            hint = "MSSV (ví dụ: 215893)"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setPadding(0, 16, 0, 0)
        }
        container.addView(codeInput)
        container.addView(mssvInput)

        val msg = buildString {
            append("Nhập mã đề và MSSV để chấm lại bài này.")
            if (knownCodes.isNotEmpty()) append("\nMã đề hiện có: ${knownCodes.joinToString(", ")}")
        }

        AlertDialog.Builder(this)
            .setTitle("❌ Nhập mã đề & MSSV")
            .setMessage(msg)
            .setView(container)
            .setPositiveButton("Chấm lại") { _, _ ->
                val code = codeInput.text.toString().trim()
                val mssv = mssvInput.text.toString().trim()
                if (code.isEmpty() || mssv.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập cả mã đề và MSSV", Toast.LENGTH_SHORT).show()
                } else {
                    rescoreWithExamCode(code, overrideMssv = mssv)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Chỉ nhập MSSV (mã đề đã có) */
    private fun showEnterMssvDialog() {
        val input = EditText(this).apply {
            hint = "Ví dụ: 215893"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("⚠ Nhập MSSV thủ công")
            .setMessage("Mã đề đã đọc được: ${result.examCode}\nNhập MSSV của học viên:")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val mssv = input.text.toString().trim()
                if (mssv.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập MSSV", Toast.LENGTH_SHORT).show()
                } else {
                    saveMssv(mssv)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Lưu MSSV thủ công (không cần chấm lại) */
    private fun saveMssv(mssv: String) {
        result = result.copy(
            id   = mssv,
            mssv = mssv
        )
        repo.saveStudentResult(projectId, result)
        bindViews()
        Toast.makeText(this, "✅ Đã cập nhật MSSV: $mssv", Toast.LENGTH_SHORT).show()
    }

    /**
     * Hiển thị dialog để người dùng nhập thủ công mã đề khi model không đọc được.
     * Sau khi nhập, sẽ tìm đáp án tương ứng và chấm lại bài này.
     */
    private fun showEnterExamCodeDialog() {
        val allKeys = repo.loadAllAnswerKeys(projectId)
        val knownCodes = allKeys.keys.toList()

        val input = EditText(this).apply {
            hint = "Ví dụ: 001"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            setPadding(48, 24, 48, 24)
        }

        val messageText = buildString {
            append("Lý do: Học viên có thể chưa tô mã đề, hoặc tô không rõ (2 ô gần bằng nhau)\n\n")
            if (knownCodes.isNotEmpty()) {
                append("Mã đề đã có trong hệ thống: ${knownCodes.joinToString(", ")}\n\n")
            } else {
                append("⚠ Chưa có đáp án nào trong project này. Hãy scan đáp án trước.\n\n")
            }
            append("Nhập mã đề để chấm lại bài này:")
        }

        AlertDialog.Builder(this)
            .setTitle("❌ Nhập mã đề thủ công")
            .setMessage(messageText)
            .setView(input)
            .setPositiveButton("Chấm lại") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập mã đề", Toast.LENGTH_SHORT).show()
                } else {
                    rescoreWithExamCode(code)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /**
     * Chấm lại bài hiện tại với mã đề do người dùng nhập thủ công.
     * Dùng lại đáp án của học viên như model đạ đọc, chỉ đổi correctAnswer theo key mới.
     */
    private fun rescoreWithExamCode(code: String, overrideMssv: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val key = repo.loadAnswerKey(projectId, code)
            if (key == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StudentResultActivity,
                        "❌ Không tìm thấy đáp án mã đề '$code'. Hãy kiểm tra lại.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // Tái sử dụng đáp án học viên (như model đã đọc) — chỉ thay đổi correctAnswer
            val rawAnswers = currentQuestions
                .associate { it.questionNum to it.studentAnswer }
                .toMutableMap()
            val suspiciousSet = currentQuestions
                .filter { it.isSuspicious && !it.isManuallyEdited }
                .map { it.questionNum }
                .toMutableSet()

            val rawScan = RawScanResult(
                mssv            = overrideMssv ?: result.mssv,
                examCode        = code,
                answers         = rawAnswers,
                suspicious      = suspiciousSet,
                rawRatios       = emptyMap(),
                warpedImagePath = result.warpedImagePath
            )

            val rescored = ScoringEngine.score(rawScan, key, result.sourceFileName)
            result = rescored
            currentQuestions.clear()
            currentQuestions.addAll(rescored.questions)
            repo.saveStudentResult(projectId, rescored)

            withContext(Dispatchers.Main) {
                bindViews()
                answerAdapter.notifyDataSetChanged()
                renderPreview()
                Toast.makeText(
                    this@StudentResultActivity,
                    "✅ Đã chấm lại với mã đề $code — điểm: ${"%.2f".format(rescored.score)}/10",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showEditDialog(question: QuestionResult) {
        val options = arrayOf("A", "B", "C", "D", "E")
        val currentSelected = BooleanArray(options.size) { question.studentAnswer.contains(options[it]) }
        val pendingSelected = currentSelected.copyOf()

        val studentStr = question.studentAnswer.sorted().joinToString("").ifEmpty { "—" }
        val correctStr = question.correctAnswer.sorted().joinToString("").ifEmpty { "—" }

        // NOTE: setMessage() + setMultiChoiceItems() conflict — message hides the list.
        // Put context info in the title only; the list renders in the content area.
        AlertDialog.Builder(this)
            .setTitle("Câu ${question.questionNum} — Đá đúng: $correctStr  |  SV tô: $studentStr")
            .setMultiChoiceItems(options, pendingSelected) { _, which, checked ->
                pendingSelected[which] = checked
            }
            .setPositiveButton("Lưu") { _, _ ->
                val newAnswer = options.filterIndexed { i, _ -> pendingSelected[i] }.toSet()
                applyEdit(question.questionNum, newAnswer)
            }
            .setNeutralButton("Bỏ trống") { _, _ ->
                applyEdit(question.questionNum, emptySet())
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun applyEdit(qNum: Int, newAnswer: Set<String>) {
        val idx = currentQuestions.indexOfFirst { it.questionNum == qNum }
        if (idx < 0) return
        val q = currentQuestions[idx]
        val edited = q.copy(
            studentAnswer    = newAnswer,
            isCorrect        = newAnswer == q.correctAnswer,
            isSuspicious     = false,
            isManuallyEdited = true
        )
        currentQuestions[idx] = edited

        // Recalculate score
        val updated = ScoringEngine.recalculate(result, currentQuestions)
        result = updated
        repo.saveStudentResult(projectId, updated)

        // Refresh UI
        tvScore.text = "Điểm: ${"%.2f".format(result.score)}  (${result.rawScore}/${result.totalQuestions})"
        tvSuspWarning.visibility = if (result.hasSuspicious) View.VISIBLE else View.GONE
        answerAdapter.notifyItemChanged(idx)
        renderPreview()
        Toast.makeText(this, "Đã cập nhật câu $qNum → ${newAnswer.sorted().joinToString("").ifEmpty { "—" }}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_student_result, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_export_pdf -> { exportPdf(); true }
            R.id.action_mark_suspicious -> { markSuspicious(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Đưa bài vào danh sách nghi vấn để xem lại (khi người dùng chưa yên tâm).
     * Các câu chưa đọc được / đang nghi vấn sẽ được đánh dấu suspicious.
     */
    private fun markSuspicious() {
        val updated = currentQuestions.map { q ->
            if (q.studentAnswer.isEmpty() || q.isSuspicious) q.copy(isSuspicious = true) else q
        }
        currentQuestions.clear(); currentQuestions.addAll(updated)
        result = result.copy(hasSuspicious = true, questions = updated)
        repo.saveStudentResult(projectId, result)
        bindViews()
        answerAdapter.notifyDataSetChanged()
        Toast.makeText(this, "Đã đưa bài vào danh sách nghi vấn để xem lại", Toast.LENGTH_SHORT).show()
    }

    private fun exportPdf() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = ConfigLoader.load(this@StudentResultActivity)
                val file = PdfExporter.exportStudentResult(this@StudentResultActivity, result, config)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StudentResultActivity, "PDF: ${file.name}", Toast.LENGTH_LONG).show()
                    val uri = FileProvider.getUriForFile(this@StudentResultActivity, "$packageName.fileprovider", file)
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@StudentResultActivity, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

// ─── Answer detail adapter ─────────────────────────────────────────────────────

class AnswerDetailAdapter(
    private val questions: List<QuestionResult>,
    private val onEdit: (QuestionResult) -> Unit
) : RecyclerView.Adapter<AnswerDetailAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvNum:     TextView     = view.findViewById(R.id.tvQNum)
        val tvStudent: TextView     = view.findViewById(R.id.tvStudentAns)
        val tvCorrect: TextView     = view.findViewById(R.id.tvCorrectAns)
        val tvStatus:  TextView     = view.findViewById(R.id.tvQStatus)
        val btnEdit:   MaterialButton = view.findViewById(R.id.btnEditQuestion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_answer_detail, parent, false))

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val q = questions[pos]
        val studentStr = q.studentAnswer.sorted().joinToString("").ifEmpty { "—" }
        val correctStr = q.correctAnswer.sorted().joinToString("").ifEmpty { "—" }

        holder.tvNum.text     = if (q.isManuallyEdited) "Câu ${q.questionNum} ✏" else "Câu ${q.questionNum}"
        holder.tvStudent.text = "SV: $studentStr"
        holder.tvCorrect.text = "ĐA: $correctStr"

        when {
            q.isSuspicious -> {
                holder.tvStatus.text = "⚠"
                holder.tvStatus.setTextColor(0xFFF57C00.toInt())
            }
            q.isCorrect -> {
                holder.tvStatus.text = "✓"
                holder.tvStatus.setTextColor(0xFF388E3C.toInt())
            }
            else -> {
                holder.tvStatus.text = "✗"
                holder.tvStatus.setTextColor(0xFFD32F2F.toInt())
            }
        }

        holder.btnEdit.setOnClickListener { onEdit(q) }
        // Tapping the whole row also triggers edit
        holder.itemView.setOnClickListener { onEdit(q) }
    }
}
