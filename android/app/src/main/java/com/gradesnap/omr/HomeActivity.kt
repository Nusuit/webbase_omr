package com.gradesnap.omr

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Màn hình chính: danh sách các Exam Project.
 * FAB → tạo project mới.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var repo: ExamRepository
    private lateinit var adapter: ExamAdapter
    private lateinit var rvExams: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "GradeSnap"

        repo = ExamRepository(this)
        rvExams  = findViewById(R.id.rvExams)
        tvEmpty  = findViewById(R.id.tvEmpty)

        adapter = ExamAdapter(
            onOpen   = { project -> openExam(project) },
            onDelete = { project -> confirmDelete(project) }
        )
        rvExams.layoutManager = LinearLayoutManager(this)
        rvExams.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabCreate).setOnClickListener {
            showCreateDialog()
        }

        checkAndShowCrashLog()
    }

    private fun checkAndShowCrashLog() {
        if (!GradeSnapApp.hasCrashLog(this)) return
        val log = GradeSnapApp.readCrashLog(this)

        val tv = TextView(this).apply {
            text = log
            textSize = 11f
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("⚠️ App bị crash lần trước")
            .setView(sv)
            .setPositiveButton("📤 Chia sẻ log") { _, _ ->
                val file = GradeSnapApp.getCrashLogFile(this)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "GradeSnap Crash Log")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Chia sẻ crash log"
                ))
                GradeSnapApp.clearCrashLog(this)
            }
            .setNegativeButton("❌ Đóng") { _, _ -> GradeSnapApp.clearCrashLog(this) }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val projects = repo.listProjects()
        adapter.submitList(projects)
        tvEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "Tên kỳ thi (ví dụ: CTTT2022_GK)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Tạo Exam mới")
            .setView(input)
            .setPositiveButton("Tạo") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val project = repo.createProject(name)
                    openExam(project)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun openExam(project: ExamProject) {
        startActivity(Intent(this, ExamActivity::class.java).apply {
            putExtra(ExamActivity.EXTRA_PROJECT_ID, project.id)
        })
    }

    private fun confirmDelete(project: ExamProject) {
        AlertDialog.Builder(this)
            .setTitle("Xóa \"${project.name}\"?")
            .setMessage("Toàn bộ kết quả sẽ bị xóa vĩnh viễn.")
            .setPositiveButton("Xóa") { _, _ ->
                repo.deleteProject(project.id)
                refreshList()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}

// ─── Adapter ──────────────────────────────────────────────────────────────────

class ExamAdapter(
    private val onOpen: (ExamProject) -> Unit,
    private val onDelete: (ExamProject) -> Unit
) : RecyclerView.Adapter<ExamAdapter.VH>() {

    private var items = listOf<ExamProject>()

    fun submitList(list: List<ExamProject>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView    = view.findViewById(R.id.tvExamName)
        val tvInfo: TextView    = view.findViewById(R.id.tvExamInfo)
        val tvDate: TextView    = view.findViewById(R.id.tvExamDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_exam, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.tvName.text = p.name
        holder.tvInfo.text = "${p.studentCount} học viên | ${p.answerKeyCount} mã đề"
        holder.tvDate.text = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(p.createdAt))
        holder.itemView.setOnClickListener { onOpen(p) }
        holder.itemView.setOnLongClickListener { onDelete(p); true }
    }
}
