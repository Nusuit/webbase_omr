package com.gradesnap.omr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình chi tiết một Exam:
 *   - Chọn ảnh bài làm + đáp án (PhotoPickerActivity - kiểu Zalo)
 *   - Bắt đầu batch scan
 *   - Dashboard thống kê
 *   - Danh sách học viên (tap → chi tiết)
 */
class ExamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private lateinit var repo: ExamRepository
    private lateinit var project: ExamProject
    private lateinit var studentAdapter: StudentResultAdapter
    private var studentNameMap: Map<String, String> = emptyMap()

    // UI refs
    private lateinit var tvAssignmentFolder: TextView
    private lateinit var tvAnswerKeyFolder: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvSuspiciousAlert: TextView
    private lateinit var tvStudentListStatus: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnSuspicious: MaterialButton
    private lateinit var btnExportPdf: MaterialButton
    private lateinit var btnClearAssignment: MaterialButton
    private lateinit var btnClearAnswerKey: MaterialButton
    private lateinit var rvStudents: RecyclerView

    private var pendingTarget: ImageTarget = ImageTarget.ASSIGNMENT

    private enum class ImageTarget { ASSIGNMENT, ANSWER_KEY }

    // ── Photo picker (Android Photo Picker — stable MediaStore URIs) ──────────
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) addImages(uris)
    }
    // ── CSV student list picker ───────────────────────────────────────────────
    private val csvPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) importStudentListFromUri(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam)

        val id = intent.getStringExtra(EXTRA_PROJECT_ID) ?: return finish()
        repo = ExamRepository(this)
        project = repo.loadProject(id) ?: return finish()

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { title = project.name; setDisplayHomeAsUpEnabled(true) }

        bindViews()
        setupRecyclerView()
        refreshUI()
    }

    private fun bindViews() {
        tvAssignmentFolder = findViewById(R.id.tvAssignmentFolder)
        tvAnswerKeyFolder  = findViewById(R.id.tvAnswerKeyFolder)
        tvStats            = findViewById(R.id.tvStats)
        tvSuspiciousAlert  = findViewById(R.id.tvSuspiciousAlert)
        tvStudentListStatus = findViewById(R.id.tvStudentListStatus)
        btnScan            = findViewById(R.id.btnScan)
        btnSuspicious      = findViewById(R.id.btnSuspicious)
        btnExportPdf       = findViewById(R.id.btnExportPdf)
        btnClearAssignment = findViewById(R.id.btnClearAssignment)
        btnClearAnswerKey  = findViewById(R.id.btnClearAnswerKey)
        rvStudents         = findViewById(R.id.rvStudents)

        findViewById<MaterialButton>(R.id.btnPickAssignment).setOnClickListener {
            pendingTarget = ImageTarget.ASSIGNMENT
            launchPhotoPicker()
        }
        findViewById<MaterialButton>(R.id.btnPickAnswerKey).setOnClickListener {
            // Bước 1: mở màn quét & xác nhận đáp án (ground truth)
            startActivity(Intent(this, AnswerKeyReviewActivity::class.java).apply {
                putExtra(AnswerKeyReviewActivity.EXTRA_PROJECT_ID, project.id)
            })
        }
        btnClearAssignment.setOnClickListener {
            project.assignmentImageUris.clear()
            repo.saveProject(project)
            refreshUI()
        }
        btnClearAnswerKey.setOnClickListener {
            project.answerKeyImageUris.clear()
            repo.saveProject(project)
            refreshUI()
        }
        btnScan.setOnClickListener { startBatchScan() }
        btnSuspicious.setOnClickListener {
            val susp = repo.loadAllStudentResults(project.id).filter { it.hasSuspicious }
            if (susp.isEmpty()) {
                Toast.makeText(this, "Không có bài suspicious", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, SuspiciousReviewActivity::class.java).apply {
                    putExtra(SuspiciousReviewActivity.EXTRA_PROJECT_ID, project.id)
                })
            }
        }
        btnExportPdf.setOnClickListener { exportPdf() }
        findViewById<MaterialButton>(R.id.btnImportStudentList).setOnClickListener {
            csvPicker.launch("*/*")
        }
    }

    // ─── Launch Android Photo Picker ──────────────────────────────────────────
    private fun launchPhotoPicker() {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // ─── Add images to the pending target list ─────────────────────────────────
    private fun addImages(uris: List<Uri>) {
        val list = uris.map { it.toString() }
        when (pendingTarget) {
            ImageTarget.ASSIGNMENT -> project.assignmentImageUris.addAll(list)
            ImageTarget.ANSWER_KEY -> project.answerKeyImageUris.addAll(list)
        }
        repo.saveProject(project)
        refreshUI()
    }

    private fun setupRecyclerView() {
        studentAdapter = StudentResultAdapter { result ->
            startActivity(Intent(this, StudentResultActivity::class.java).apply {
                putExtra(StudentResultActivity.EXTRA_PROJECT_ID, project.id)
                putExtra(StudentResultActivity.EXTRA_STUDENT_ID, result.id)
            })
        }
        rvStudents.layoutManager = LinearLayoutManager(this)
        rvStudents.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rvStudents.adapter = studentAdapter
    }

    private fun refreshUI() {
        val assignCount = project.assignmentImageUris.size
        val confirmedKeys = repo.loadConfirmedKeys(project.id)

        tvAssignmentFolder.text = if (assignCount == 0) "Chưa chọn ảnh nào"
                                  else "$assignCount ảnh đã chọn"
        tvAnswerKeyFolder.text  = if (confirmedKeys.isEmpty())
                                      "Chưa có đáp án — nhấn để quét & xác nhận"
                                  else
                                      "✅ " + confirmedKeys.values.joinToString("   ") {
                                          "Mã đề ${it.examCode} (${it.answers.size} câu)"
                                      }

        btnClearAssignment.visibility = if (assignCount > 0) View.VISIBLE else View.GONE
        btnClearAnswerKey.visibility  = View.GONE   // đáp án quản lý qua màn xác nhận

        // Bước 3 chỉ bật khi đã có đáp án xác nhận + có ảnh bài làm
        btnScan.isEnabled = confirmedKeys.isNotEmpty() && assignCount > 0

        val results = repo.loadAllStudentResults(project.id)
        studentNameMap = repo.loadStudentMap(project.id)
        val avg = ScoringEngine.average(results)
        val susp = ScoringEngine.countWithSuspicious(results)

        tvStats.text = buildString {
            append("Tổng: ${results.size} bài  |  TB: ${"%.2f".format(avg)}\n")
            append("Điểm 10: ${ScoringEngine.countPerfect(results)}  |  Điểm 0: ${ScoringEngine.countZero(results)}")
        }

        if (susp > 0) {
            tvSuspiciousAlert.visibility = View.VISIBLE
            tvSuspiciousAlert.text = "⚠ $susp bài cần kiểm tra (suspicious)"
            btnSuspicious.visibility = View.VISIBLE
        } else {
            tvSuspiciousAlert.visibility = View.GONE
            btnSuspicious.visibility = View.GONE
        }

        btnExportPdf.isEnabled = results.isNotEmpty()
        studentAdapter.updateStudentMap(studentNameMap)
        studentAdapter.submitList(ScoringEngine.sortedByScore(results))

        // Student list import status
        val importedCount = studentNameMap.size
        tvStudentListStatus.text = if (importedCount > 0)
            "✅ Đã import $importedCount học viên"
        else
            "💡 Chưa import danh sách học viên. Định dạng: MSSV,Họ tên (mỗi dòng)"
    }

    private fun startBatchScan() {
        if (repo.loadConfirmedKeys(project.id).isEmpty()) {
            Toast.makeText(this, "Bước 1: hãy quét & xác nhận đáp án trước", Toast.LENGTH_SHORT).show()
            return
        }
        if (project.assignmentImageUris.isEmpty()) {
            Toast.makeText(this, "Bước 2: hãy thêm ảnh bài làm học viên", Toast.LENGTH_SHORT).show()
            return
        }
        // Chỉ truyền projectId — URIs đã được lưu trong project.json,
        // ProcessingActivity tự đọc từ repo để tránh TransactionTooLargeException
        startActivity(Intent(this, ProcessingActivity::class.java).apply {
            putExtra(ProcessingActivity.EXTRA_PROJECT_ID, project.id)
        })
    }

    private fun importStudentListFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val lines = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readLines()
                    ?: return@launch

                if (lines.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ExamActivity, "File rỗng", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Detect header and column indices
                val firstLine = lines.first()
                val headerParts = firstLine.split(",", "\t", ";").map { it.trim().lowercase() }

                val mssvIdx = headerParts.indexOfFirst { it.contains("mssv") || it.contains("id") || it.contains("số sv") }
                val nameIdx = headerParts.indexOfFirst { it.contains("tên") || it.contains("ten") || it.contains("họ") || it.contains("name") }

                val hasHeader = mssvIdx >= 0 || nameIdx >= 0
                val dataLines = if (hasHeader) lines.drop(1) else lines

                // Final column mapping: if header found use detected indices, else fallback
                val colMssv = if (mssvIdx >= 0) mssvIdx else 1   // default: col B = MSSV (like the screenshot)
                val colName = if (nameIdx >= 0) nameIdx else 0    // default: col A = Tên

                val students = dataLines
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val parts = line.split(",", "\t", ";").map { it.trim() }
                        val mssv = parts.getOrNull(colMssv)?.trim() ?: return@mapNotNull null
                        val name = parts.getOrNull(colName)?.trim() ?: ""
                        if (mssv.isEmpty()) null else StudentInfo(mssv = mssv, name = name)
                    }

                repo.saveStudentList(project.id, students)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ExamActivity, "Đã import ${students.size} học viên", Toast.LENGTH_SHORT).show()
                    refreshUI()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ExamActivity, "Lỗi đọc file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportPdf() {
        lifecycleScope.launch(Dispatchers.IO) {
            val results = repo.loadAllStudentResults(project.id)
            val nameMap = repo.loadStudentMap(project.id)
            val file = PdfExporter.exportClassSummary(this@ExamActivity, project.name, results, nameMap)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ExamActivity, "Đã xuất: ${file.name}", Toast.LENGTH_LONG).show()
                val uri = FileProvider.getUriForFile(this@ExamActivity, "$packageName.fileprovider", file)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        project = repo.loadProject(project.id) ?: project
        refreshUI()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}

// ─── Student list adapter ─────────────────────────────────────────────────────

class StudentResultAdapter(
    private val onClick: (StudentResult) -> Unit
) : RecyclerView.Adapter<StudentResultAdapter.VH>() {

    private var items: List<StudentResult> = emptyList()
    private var nameMap: Map<String, String> = emptyMap()

    fun submitList(list: List<StudentResult>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateStudentMap(map: Map<String, String>) {
        nameMap = map
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMssv: TextView  = view.findViewById(R.id.tvStudentMssv)
        val tvName: TextView  = view.findViewById(R.id.tvStudentCode)
        val tvScore: TextView = view.findViewById(R.id.tvStudentScore)
        val tvRaw: TextView   = view.findViewById(R.id.tvStudentRaw)
        val tvSusp: TextView  = view.findViewById(R.id.tvStudentAlert)
        init { view.setOnClickListener { onClick(items[adapterPosition]) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_student_result, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val r = items[pos]
        holder.tvMssv.text  = r.mssv ?: r.sourceFileName
        val name = r.mssv?.let { nameMap[it] } ?: ""
        holder.tvName.text  = name
        holder.tvName.visibility = if (name.isNotEmpty()) View.VISIBLE else View.GONE
        holder.tvScore.text = "${"%.2f".format(r.score)}/10"
        holder.tvRaw.text   = "${r.rawScore}/${r.totalQuestions} câu"
        holder.tvSusp.visibility = if (r.hasSuspicious) View.VISIBLE else View.GONE
    }
}