package com.gradesnap.omr

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * Xem + sửa đáp án một mã đề (AnswerKey).
 * Tap vào câu → dialog sửa đáp án đúng → lưu lại keys/{examCode}.json
 * và recalculate tất cả StudentResult dùng mã đề này.
 */
class AnswerKeyDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_EXAM_CODE  = "exam_code"
    }

    private lateinit var repo: ExamRepository
    private lateinit var projectId: String
    private lateinit var answerKey: AnswerKey
    private val questions = mutableListOf<Pair<Int, Set<String>>>() // qNum → answer
    private lateinit var adapter: AnswerKeyQuestionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_answer_key_detail)

        projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: return finish()
        val examCode = intent.getStringExtra(EXTRA_EXAM_CODE) ?: return finish()

        repo = ExamRepository(this)
        answerKey = repo.loadAnswerKey(projectId, examCode) ?: return finish()

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = "Đáp án — Mã đề $examCode"
            setDisplayHomeAsUpEnabled(true)
        }

        questions.addAll(answerKey.answers.entries.sortedBy { it.key }.map { it.key to it.value })

        // Load warped image preview if available
        val cardPreview = findViewById<View>(R.id.cardPreviewImage)
        val imgPreview  = findViewById<ImageView>(R.id.imgAnswerKeyPreview)
        val warpedPath  = answerKey.warpedImagePath
        if (!warpedPath.isNullOrBlank()) {
            val bmp = runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(warpedPath, opts)
            }.getOrNull()
            if (bmp != null) {
                imgPreview.setImageBitmap(bmp)
                cardPreview.visibility = View.VISIBLE
            }
        }

        adapter = AnswerKeyQuestionAdapter(questions) { qNum ->
            showEditDialog(qNum)
        }

        val rv = findViewById<RecyclerView>(R.id.rvKeyQuestions)
        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rv.adapter = adapter
    }

    private fun showEditDialog(qNum: Int) {
        val options = arrayOf("A", "B", "C", "D", "E")
        val idx = questions.indexOfFirst { it.first == qNum }
        if (idx < 0) return
        val current = questions[idx].second
        val selected = BooleanArray(options.size) { current.contains(options[it]) }
        val pending  = selected.copyOf()

        AlertDialog.Builder(this)
            .setTitle("Sửa đáp án câu $qNum (hiện tại: ${current.sorted().joinToString("")})")
            .setMultiChoiceItems(options, pending) { _, which, checked -> pending[which] = checked }
            .setPositiveButton("Lưu") { _, _ ->
                val newAnswer = options.filterIndexed { i, _ -> pending[i] }.toSet()
                applyEdit(idx, qNum, newAnswer)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun applyEdit(idx: Int, qNum: Int, newAnswer: Set<String>) {
        questions[idx] = qNum to newAnswer
        adapter.notifyItemChanged(idx)

        // Lưu lại AnswerKey
        val newAnswerMap = questions.associate { it.first to it.second }
        answerKey = answerKey.copy(answers = newAnswerMap)
        repo.saveAnswerKey(projectId, answerKey)

        // Recalculate tất cả StudentResult dùng mã đề này
        var updated = 0
        repo.loadAllStudentResults(projectId)
            .filter { it.examCode == answerKey.examCode }
            .forEach { result ->
                val rescored = ScoringEngine.recalculate(
                    result,
                    result.questions.map { q ->
                        q.copy(
                            correctAnswer = newAnswerMap[q.questionNum] ?: q.correctAnswer,
                            isCorrect = q.studentAnswer == (newAnswerMap[q.questionNum] ?: q.correctAnswer)
                        )
                    }
                )
                repo.saveStudentResult(projectId, rescored)
                updated++
            }

        Toast.makeText(
            this,
            "✅ Đã cập nhật câu $qNum → ${newAnswer.sorted().joinToString("").ifEmpty { "—" }}" +
                if (updated > 0) " | Chấm lại $updated bài" else "",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

class AnswerKeyQuestionAdapter(
    private val questions: List<Pair<Int, Set<String>>>,
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<AnswerKeyQuestionAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvNum: TextView    = view.findViewById(R.id.tvKeyQNum)
        val tvAnswer: TextView = view.findViewById(R.id.tvKeyAnswer)
        val btnEdit: MaterialButton = view.findViewById(R.id.btnKeyEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_answer_key_question, parent, false))

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (qNum, answer) = questions[position]
        holder.tvNum.text    = "Câu $qNum"
        holder.tvAnswer.text = answer.sorted().joinToString("").ifEmpty { "—" }
        holder.btnEdit.setOnClickListener { onEdit(qNum) }
    }
}
