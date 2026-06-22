package com.gradesnap.omr

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.gradesnap.omr.databinding.ActivityResultBinding

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding

    companion object {
        const val EXTRA_ANSWERS = "extra_answers"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Kết quả chấm bài"
            setDisplayHomeAsUpEnabled(true)
        }

        @Suppress("UNCHECKED_CAST")
        val answers = intent.getSerializableExtra(EXTRA_ANSWERS) as? HashMap<String, String?>
            ?: return

        // Tổng hợp thống kê
        val total = answers.size
        val answered = answers.values.count { it != null }
        val unanswered = total - answered

        binding.tvSummary.text = "Tổng: $total câu | Đã tô: $answered | Bỏ trống: $unanswered"

        // Hiển thị danh sách câu trả lời
        val items = answers.entries
            .sortedBy { it.key.toIntOrNull() ?: 0 }
            .map { (qNum, ans) -> AnswerItem(qNum.toInt(), ans) }

        binding.rvAnswers.apply {
            layoutManager = LinearLayoutManager(this@ResultActivity)
            addItemDecoration(DividerItemDecoration(this@ResultActivity, DividerItemDecoration.VERTICAL))
            adapter = AnswerAdapter(items)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

// ─── Data class ───────────────────────────────────────────────────────────────
data class AnswerItem(val questionNumber: Int, val answer: String?)
