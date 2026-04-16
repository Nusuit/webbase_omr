package com.gradesnap.omr

import android.graphics.Paint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
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
    private lateinit var btnScan: MaterialButton
    private lateinit var btnSuspicious: MaterialButton
    private lateinit var btnExportPdf: MaterialButton
    private lateinit var btnClearAssignment: MaterialButton
    private lateinit var btnClearAnswerKey: MaterialButton
    private lateinit var rvStudents: RecyclerView
    private lateinit var rvAnswerKeys: RecyclerView
    private lateinit var tabResults: TabLayout
    private lateinit var answerKeyAdapter: AnswerKeyListAdapter
    private lateinit var tvStudentEmpty: TextView
    private lateinit var tvAnswerKeyEmpty: TextView

    private var pendingTarget: ImageTarget = ImageTarget.ASSIGNMENT

    private enum class ImageTarget { ASSIGNMENT, ANSWER_KEY }

    // ── Photo picker (Zalo-style) ─────────────────────────────────────────────
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = result.data
                ?.getStringArrayListExtra(PhotoPickerActivity.RESULT_URIS)
                ?: return@registerForActivityResult
            addImages(uris.map { Uri.parse(it) })
        }
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
        btnScan            = findViewById(R.id.btnScan)
        btnSuspicious      = findViewById(R.id.btnSuspicious)
        btnExportPdf       = findViewById(R.id.btnExportPdf)
        btnClearAssignment = findViewById(R.id.btnClearAssignment)
        btnClearAnswerKey  = findViewById(R.id.btnClearAnswerKey)
        rvStudents         = findViewById(R.id.rvStudents)
        rvAnswerKeys       = findViewById(R.id.rvAnswerKeys)
        tabResults         = findViewById(R.id.tabResults)
        tvStudentEmpty     = findViewById(R.id.tvStudentEmpty)
        tvAnswerKeyEmpty   = findViewById(R.id.tvAnswerKeyEmpty)

        // Tabs setup
        tabResults.addTab(tabResults.newTab().setText("Bài làm học viên"))
        tabResults.addTab(tabResults.newTab().setText("Đáp án"))
        tabResults.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                applyTabVisibility(tab?.position == 1)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Click on folder counts → image preview list
        tvAssignmentFolder.setOnClickListener {
            if (project.assignmentImageUris.isNotEmpty())
                ImagePreviewListActivity.start(this, "Bài làm", project.assignmentImageUris)
        }
        tvAnswerKeyFolder.setOnClickListener {
            if (project.answerKeyImageUris.isNotEmpty())
                ImagePreviewListActivity.start(this, "Đáp án", project.answerKeyImageUris)
        }

        findViewById<MaterialButton>(R.id.btnPickAssignment).setOnClickListener {
            pendingTarget = ImageTarget.ASSIGNMENT
            launchPhotoPicker()
        }
        findViewById<MaterialButton>(R.id.btnPickAnswerKey).setOnClickListener {
            pendingTarget = ImageTarget.ANSWER_KEY
            launchPhotoPicker()
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
    }

    // ─── Launch Zalo-style photo picker ───────────────────────────────────────
    private fun launchPhotoPicker() {
        photoPicker.launch(Intent(this, PhotoPickerActivity::class.java))
    }

    /** Show/hide the correct RV + empty-state based on selected tab. */
    private fun applyTabVisibility(isAnswerKeyTab: Boolean) {
        val hasStudents   = studentAdapter.itemCount > 0
        val hasAnswerKeys = answerKeyAdapter.itemCount > 0

        rvStudents.visibility     = if (!isAnswerKeyTab) View.VISIBLE else View.GONE
        tvStudentEmpty.visibility = if (!isAnswerKeyTab && !hasStudents) View.VISIBLE else View.GONE

        rvAnswerKeys.visibility    = if (isAnswerKeyTab) View.VISIBLE else View.GONE
        tvAnswerKeyEmpty.visibility = if (isAnswerKeyTab && !hasAnswerKeys) View.VISIBLE else View.GONE
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
        // Answer key adapter
        answerKeyAdapter = AnswerKeyListAdapter { examCode ->
            startActivity(Intent(this, AnswerKeyDetailActivity::class.java).apply {
                putExtra(AnswerKeyDetailActivity.EXTRA_PROJECT_ID, project.id)
                putExtra(AnswerKeyDetailActivity.EXTRA_EXAM_CODE, examCode)
            })
        }
        rvAnswerKeys.layoutManager = LinearLayoutManager(this)
        rvAnswerKeys.adapter = answerKeyAdapter

        studentAdapter = StudentResultAdapter(
            onClick = { result ->
                startActivity(Intent(this, StudentResultActivity::class.java).apply {
                    putExtra(StudentResultActivity.EXTRA_PROJECT_ID, project.id)
                    putExtra(StudentResultActivity.EXTRA_STUDENT_ID, result.id)
                })
            },
            onLongClick = { result ->
                AlertDialog.Builder(this)
                    .setTitle("Xóa kết quả")
                    .setMessage("Xóa bài '${result.mssv ?: result.sourceFileName}'?\nHành động này không thể hoàn tác.")
                    .setPositiveButton("Xóa") { _, _ ->
                        repo.deleteStudentResult(project.id, result.id)
                        refreshUI()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        )
        rvStudents.layoutManager = LinearLayoutManager(this)
        rvStudents.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rvStudents.adapter = studentAdapter
    }

    private fun refreshUI() {
        val assignCount = project.assignmentImageUris.size
        val keyCount    = project.answerKeyImageUris.size

        // Folder count labels: blue + underline when clickable (has images), grey otherwise
        val primaryColor  = getColor(R.color.primary)
        val defaultColor  = getColor(R.color.on_surface)
        if (assignCount > 0) {
            tvAssignmentFolder.text = "$assignCount Bài làm  ›"
            tvAssignmentFolder.setTextColor(primaryColor)
            tvAssignmentFolder.paintFlags = tvAssignmentFolder.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            tvAssignmentFolder.text = "0 Bài làm"
            tvAssignmentFolder.setTextColor(defaultColor)
            tvAssignmentFolder.paintFlags = tvAssignmentFolder.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
        if (keyCount > 0) {
            tvAnswerKeyFolder.text = "$keyCount Đáp án  ›"
            tvAnswerKeyFolder.setTextColor(primaryColor)
            tvAnswerKeyFolder.paintFlags = tvAnswerKeyFolder.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            tvAnswerKeyFolder.text = "0 Đáp án"
            tvAnswerKeyFolder.setTextColor(defaultColor)
            tvAnswerKeyFolder.paintFlags = tvAnswerKeyFolder.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }

        btnClearAssignment.visibility = if (assignCount > 0) View.VISIBLE else View.GONE
        btnClearAnswerKey.visibility  = if (keyCount > 0)  View.VISIBLE else View.GONE

        val results = repo.loadAllStudentResults(project.id)
        studentNameMap = repo.loadStudentMap(project.id)
        val avg = ScoringEngine.average(results)
        val susp = ScoringEngine.countWithSuspicious(results)

        tvStats.text = "Tổng: ${results.size} bài  |  TB: ${"%.2f".format(avg)}  |  Điểm 10: ${ScoringEngine.countPerfect(results)}  |  Điểm 0: ${ScoringEngine.countZero(results)}"

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

        // Populate answer keys tab
        val answerKeys = repo.loadAllAnswerKeys(project.id)
        answerKeyAdapter.submitList(answerKeys.values.toList())

        // Re-apply tab visibility (empty states + RV show/hide)
        applyTabVisibility(tabResults.selectedTabPosition == 1)

    }

    private fun startBatchScan() {
        if (project.assignmentImageUris.isEmpty() || project.answerKeyImageUris.isEmpty()) {
            Toast.makeText(this, "Vui lòng thêm ảnh bài làm và đáp án trước", Toast.LENGTH_SHORT).show()
            return
        }
        val existingResults = repo.loadAllStudentResults(project.id)
        if (existingResults.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("🗑 Xóa kết quả cũ?")
                .setMessage("Hiện có ${existingResults.size} kết quả từ lần chấm trước.\n\nBắt đầu chấm mới sẽ xóa toàn bộ kết quả và đáp án cũ. Tiếp tục?")
                .setPositiveButton("Ðồng ý, chấm lại") { _, _ ->
                    repo.clearAllResults(project.id)
                    repo.clearAllAnswerKeys(project.id)
                    launchProcessing()
                }
                .setNegativeButton("Hủy", null)
                .show()
        } else {
            launchProcessing()
        }
    }

    private fun launchProcessing() {
        startActivity(Intent(this, ProcessingActivity::class.java).apply {
            putExtra(ProcessingActivity.EXTRA_PROJECT_ID, project.id)
        })
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
    private val onClick: (StudentResult) -> Unit,
    private val onLongClick: (StudentResult) -> Unit = {}
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
        init {
            view.setOnClickListener { onClick(items[adapterPosition]) }
            view.setOnLongClickListener { onLongClick(items[adapterPosition]); true }
        }
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

// ─── Answer key list adapter ──────────────────────────────────────────────────

class AnswerKeyListAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<AnswerKeyListAdapter.VH>() {

    private var items: List<AnswerKey> = emptyList()

    fun submitList(list: List<AnswerKey>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode: TextView    = view.findViewById(R.id.tvAnswerKeyCode)
        val tvCount: TextView   = view.findViewById(R.id.tvAnswerKeyCount)
        init { view.setOnClickListener { onClick(items[adapterPosition].examCode) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_answer_key_list, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val k = items[pos]
        holder.tvCode.text  = "Mã đề: ${k.examCode}"
        holder.tvCount.text = "${k.answers.size} câu"
    }
}