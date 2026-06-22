package com.gradesnap.omr

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Màn hình review tuần tự các bài có suspicious:
 *   - Hiển thị MSSV, mã đề, điểm tạm thời
 *   - Hiển thị danh sách câu suspicious với ratios để debug
 *   - ZoomableImageView để xem ảnh bài làm (có overlay)
 *   - Nút "Edit" → mở StudentResultActivity để sửa chi tiết
 *   - Nút "Skip" → qua bài tiếp theo
 *   - Nút "Mark OK" → đánh dấu bài này không còn cần review
 *   - Counter: "Bài 2 / 5"
 */
class SuspiciousReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private lateinit var repo: ExamRepository
    private lateinit var projectId: String

    private lateinit var tvCounter: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvSuspiciousDetails: TextView
    private lateinit var imgPreview: ZoomableImageView
    private lateinit var btnEdit: MaterialButton
    private lateinit var btnMarkOk: MaterialButton
    private lateinit var tvNoMore: TextView

    private var suspiciousList = mutableListOf<StudentResult>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suspicious_review)

        projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: return finish()
        repo = ExamRepository(this)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { title = "Review Suspicious"; setDisplayHomeAsUpEnabled(true) }

        tvCounter           = findViewById(R.id.tvCounter)
        tvInfo              = findViewById(R.id.tvInfo)
        tvSuspiciousDetails = findViewById(R.id.tvSuspiciousDetails)
        imgPreview          = findViewById(R.id.imgPreviewSuspicious)
        btnEdit             = findViewById(R.id.btnEditSuspicious)
        btnMarkOk           = findViewById(R.id.btnMarkOk)
        tvNoMore            = findViewById(R.id.tvNoMore)
        // List loaded in onResume
    }

    private fun loadSuspiciousList() {
        suspiciousList = repo.loadAllStudentResults(projectId)
            .filter { it.hasSuspicious }
            .toMutableList()

        if (suspiciousList.isEmpty()) {
            showEmpty()
            return
        }
        currentIndex = 0
        showCurrent()
    }

    private fun showCurrent() {
        if (currentIndex >= suspiciousList.size) {
            showEmpty()
            return
        }
        val result = suspiciousList[currentIndex]
        tvNoMore.visibility = View.GONE
        listOf(tvCounter, tvInfo, tvSuspiciousDetails, imgPreview, btnEdit, btnMarkOk)
            .forEach { it.visibility = View.VISIBLE }

        tvCounter.text = "Bài ${currentIndex + 1} / ${suspiciousList.size}"
        tvInfo.text    = buildString {
            append("MSSV: ${result.mssv ?: "N/A"}  |  Mã đề: ${result.examCode ?: "?"}\n")
            append("Điểm: ${"%.2f".format(result.score)} (${result.rawScore}/${result.totalQuestions})")
        }

        // Suspicious câu details
        val suspQuestions = result.questions.filter { it.isSuspicious }
        tvSuspiciousDetails.text = "Câu suspicious (${suspQuestions.size}):\n" +
            suspQuestions.joinToString("\n") { q ->
                val svAns = q.studentAnswer.sorted().joinToString("").ifEmpty { "—" }
                val da    = q.correctAnswer.sorted().joinToString("").ifEmpty { "—" }
                "  Câu ${q.questionNum}: SV tô [$svAns] | ĐA [$da]"
            }

        // Load image with overlay
        lifecycleScope.launch(Dispatchers.IO) {
            val config = runCatching { ConfigLoader.load(this@SuspiciousReviewActivity) }.getOrNull()
            val path = result.warpedImagePath
            if (path != null && File(path).exists() && config != null) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    val overlay = OverlayRenderer.render(bmp, config, result.questions)
                    bmp.recycle()
                    withContext(Dispatchers.Main) { imgPreview.setImageBitmap(overlay) }
                }
            }
        }

        // Buttons
        btnEdit.setOnClickListener {
            startActivity(Intent(this, StudentResultActivity::class.java).apply {
                putExtra(StudentResultActivity.EXTRA_PROJECT_ID, projectId)
                putExtra(StudentResultActivity.EXTRA_STUDENT_ID, result.id)
            })
        }
        btnMarkOk.setOnClickListener {
            val r = suspiciousList[currentIndex]
            // Persist: clear all suspicious flags so this student no longer shows as suspicious
            val cleared = r.copy(
                hasSuspicious = false,
                questions = r.questions.map { q ->
                    if (q.isSuspicious) q.copy(isSuspicious = false) else q
                }
            )
            repo.saveStudentResult(projectId, cleared)
            suspiciousList.removeAt(currentIndex)
            if (currentIndex >= suspiciousList.size) currentIndex = suspiciousList.size - 1
            if (suspiciousList.isEmpty()) showEmpty() else showCurrent()
            Toast.makeText(this, "✅ Đã xác nhận OK — bài này không còn suspicious", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEmpty() {
        listOf(tvCounter, tvInfo, tvSuspiciousDetails, imgPreview, btnEdit, btnMarkOk)
            .forEach { it.visibility = View.GONE }
        tvNoMore.visibility = View.VISIBLE
        tvNoMore.text = "✅ Không còn bài suspicious nào cần review!"
    }

    override fun onResume() {
        super.onResume()
        // Reload in case user edited from StudentResultActivity
        loadSuspiciousList()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }
}
