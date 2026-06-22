package com.gradesnap.omr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Bước 1 — Quét & xác nhận ĐÁP ÁN (ground truth).
 *
 * Luồng:
 *   1. Chọn ảnh đáp án → scan từng ảnh (isAnswerKey = true).
 *   2. Với mỗi mã đề: preview các bubble model PHÁT HIỆN (khoanh XANH DƯƠNG)
 *      + danh sách câu. Câu không đọc được → cảnh báo đỏ.
 *   3. Người dùng kiểm tra, sửa thủ công nếu cần, rồi "Xác nhận làm đáp án chuẩn".
 *      Chỉ key đã xác nhận (confirmed = true) mới được dùng để chấm ở Bước 3.
 */
class AnswerKeyReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    /** Bản nháp đáp án 1 ảnh, có thể sửa trước khi xác nhận. */
    private data class KeyDraft(
        var examCode: String?,
        val answers: MutableMap<Int, MutableSet<String>>,
        val warpedImagePath: String?,
        val sourceName: String,
        var confirmed: Boolean = false
    )

    private lateinit var repo: ExamRepository
    private lateinit var project: ExamProject
    private var config: OmrConfig? = null

    private lateinit var tvCounter: TextView
    private lateinit var tvExamCode: TextView
    private lateinit var tvUndetectedBanner: TextView
    private lateinit var imgPreview: ZoomableImageView
    private lateinit var rvQuestions: RecyclerView
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnConfirm: MaterialButton
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: KeyQuestionAdapter

    private val drafts = mutableListOf<KeyDraft>()
    private var idx = 0

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            project.answerKeyImageUris.clear()
            project.answerKeyImageUris.addAll(uris.map { it.toString() })
            repo.saveProject(project)
            scanAll(uris)
        } else if (drafts.isEmpty()) {
            finish()  // không chọn gì và chưa có draft nào
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_answer_key_review)

        val id = intent.getStringExtra(EXTRA_PROJECT_ID) ?: return finish()
        repo = ExamRepository(this)
        project = repo.loadProject(id) ?: return finish()

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { title = "Bước 1 · Xác nhận đáp án"; setDisplayHomeAsUpEnabled(true) }

        tvCounter          = findViewById(R.id.tvKeyCounter)
        tvExamCode         = findViewById(R.id.tvKeyExamCode)
        tvUndetectedBanner = findViewById(R.id.tvKeyUndetectedBanner)
        imgPreview         = findViewById(R.id.imgKeyPreview)
        rvQuestions        = findViewById(R.id.rvKeyQuestions)
        btnSkip            = findViewById(R.id.btnKeySkip)
        btnConfirm         = findViewById(R.id.btnKeyConfirm)
        tvEmpty            = findViewById(R.id.tvKeyEmpty)

        adapter = KeyQuestionAdapter { qNum -> editQuestion(qNum) }
        rvQuestions.layoutManager = LinearLayoutManager(this)
        rvQuestions.adapter = adapter

        tvExamCode.setOnClickListener { editExamCode() }
        btnSkip.setOnClickListener { goNext() }
        btnConfirm.setOnClickListener { confirmCurrent() }

        // Nếu đã có ảnh đáp án từ trước → scan luôn; chưa có → mở picker
        val existing = project.answerKeyImageUris.map { Uri.parse(it) }
        if (existing.isNotEmpty()) scanAll(existing) else launchPicker()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Chọn ảnh khác").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        1 -> { launchPicker(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun launchPicker() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // ─── Scan tất cả ảnh đáp án ──────────────────────────────────────────────────
    private fun scanAll(uris: List<Uri>) {
        setBusy(true, "Đang quét ${uris.size} ảnh đáp án...")
        lifecycleScope.launch(Dispatchers.IO) {
            if (!OpenCVLoader.initLocal()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AnswerKeyReviewActivity, "Không khởi tạo được OpenCV", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }
            config = runCatching { ConfigLoader.load(this@AnswerKeyReviewActivity) }.getOrNull()
            val processor = OmrProcessor(this@AnswerKeyReviewActivity)
            val newDrafts = mutableListOf<KeyDraft>()
            for (uri in uris) {
                val name = "key_${uri.lastPathSegment?.substringAfterLast('/') ?: "img"}"
                val bmp = loadBitmap(uri) ?: continue
                try {
                    val raw = processor.process(bmp, sourceFile = name, isAnswerKey = true)
                    val ans = raw.answers.mapValues { it.value.toMutableSet() }.toMutableMap()
                    newDrafts += KeyDraft(raw.examCode, ans, raw.warpedImagePath, name)
                } catch (_: Exception) {
                } finally { bmp.recycle() }
            }
            withContext(Dispatchers.Main) {
                drafts.clear(); drafts.addAll(newDrafts); idx = 0
                setBusy(false, null)
                if (drafts.isEmpty()) showEmpty("Không quét được ảnh đáp án nào. Thử chọn ảnh khác.")
                else showCurrent()
            }
        }
    }

    // ─── Hiển thị draft hiện tại ─────────────────────────────────────────────────
    private fun showCurrent() {
        if (idx >= drafts.size) { showEmpty("✅ Đã xử lý xong tất cả đáp án."); return }
        val d = drafts[idx]
        tvEmpty.visibility = View.GONE
        listOf<View>(tvCounter, tvExamCode, imgPreview, rvQuestions, btnSkip, btnConfirm).forEach { it.visibility = View.VISIBLE }

        tvCounter.text = "Đáp án ${idx + 1} / ${drafts.size}  ·  ${d.sourceName}"
        tvExamCode.text = "Mã đề: ${d.examCode ?: "? (chạm để nhập)"}"

        val undetected = d.answers.filterValues { it.isEmpty() }.keys.sorted()
        if (undetected.isNotEmpty()) {
            tvUndetectedBanner.visibility = View.VISIBLE
            tvUndetectedBanner.text = "⚠ ${undetected.size} câu chưa đọc được (${undetected.joinToString(", ")}). " +
                "Kiểm tra ảnh / chạm vào câu để sửa trước khi xác nhận."
        } else {
            tvUndetectedBanner.visibility = View.GONE
        }

        adapter.submit(d.answers.toSortedMap())
        renderPreview(d)
    }

    private fun renderPreview(d: KeyDraft) {
        val cfg = config ?: return
        val path = d.warpedImagePath
        if (path == null || !File(path).exists()) { imgPreview.setImageResource(android.R.drawable.ic_menu_gallery); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = BitmapFactory.decodeFile(path) ?: return@launch
            val undetected = d.answers.filterValues { it.isEmpty() }.keys
            val detected = d.answers.filterValues { it.isNotEmpty() }
            val overlay = OverlayRenderer.renderDetection(bmp, cfg, detected, emptySet(), undetected)
            bmp.recycle()
            withContext(Dispatchers.Main) { imgPreview.setImageBitmap(overlay) }
        }
    }

    // ─── Sửa thủ công 1 câu ──────────────────────────────────────────────────────
    private fun editQuestion(qNum: Int) {
        val d = drafts.getOrNull(idx) ?: return
        val options = (config?.questions?.options ?: listOf("A", "B", "C", "D", "E")).toTypedArray()
        val current = d.answers[qNum].orEmpty()
        val pending = BooleanArray(options.size) { options[it] in current }
        AlertDialog.Builder(this)
            .setTitle("Câu $qNum — đáp án đúng")
            .setMultiChoiceItems(options, pending) { _, which, checked -> pending[which] = checked }
            .setPositiveButton("Lưu") { _, _ ->
                val set = options.filterIndexed { i, _ -> pending[i] }.toMutableSet()
                d.answers[qNum] = set
                showCurrent()
            }
            .setNeutralButton("Bỏ trống") { _, _ -> d.answers[qNum] = mutableSetOf(); showCurrent() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun editExamCode() {
        val d = drafts.getOrNull(idx) ?: return
        val input = EditText(this).apply {
            hint = "VD: 001"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(d.examCode ?: "")
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập mã đề")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                d.examCode = input.text.toString().trim().ifEmpty { null }
                showCurrent()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ─── Xác nhận làm ground truth ───────────────────────────────────────────────
    private fun confirmCurrent() {
        val d = drafts.getOrNull(idx) ?: return
        val code = d.examCode
        if (code.isNullOrEmpty()) {
            Toast.makeText(this, "Hãy nhập mã đề trước khi xác nhận", Toast.LENGTH_SHORT).show()
            editExamCode(); return
        }
        val undetected = d.answers.filterValues { it.isEmpty() }.keys.size
        val save = {
            val key = AnswerKey(
                examCode = code,
                answers = d.answers.filterValues { it.isNotEmpty() }.mapValues { it.value.toSet() },
                warpedImagePath = d.warpedImagePath,
                confirmed = true
            )
            repo.saveAnswerKey(project.id, key)
            d.confirmed = true
            Toast.makeText(this, "✅ Đã xác nhận đáp án mã đề $code", Toast.LENGTH_SHORT).show()
            goNext()
        }
        if (undetected > 0) {
            AlertDialog.Builder(this)
                .setTitle("Vẫn còn $undetected câu chưa có đáp án")
                .setMessage("Các câu này sẽ không có đáp án đúng khi chấm. Vẫn xác nhận?")
                .setPositiveButton("Xác nhận") { _, _ -> save() }
                .setNegativeButton("Để sửa tiếp", null)
                .show()
        } else save()
    }

    private fun goNext() {
        idx++
        if (idx >= drafts.size) {
            val confirmed = drafts.count { it.confirmed }
            showEmpty(if (confirmed > 0) "✅ Đã xác nhận $confirmed mã đề. Quay lại để chấm bài." else "Chưa xác nhận đáp án nào.")
        } else showCurrent()
    }

    private fun showEmpty(msg: String) {
        listOf<View>(tvCounter, tvExamCode, tvUndetectedBanner, imgPreview, rvQuestions, btnSkip, btnConfirm)
            .forEach { it.visibility = View.GONE }
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = msg
    }

    private fun setBusy(busy: Boolean, msg: String?) {
        if (busy) {
            showEmpty(msg ?: "Đang xử lý...")
            tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) null else contentResolver.openInputStream(uri)?.use { stream ->
            val deg = when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                val m = Matrix().apply { postRotate(deg) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { bmp.recycle() }
            } else bmp
        } ?: bmp
    } catch (_: Exception) { null }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}

// ─── Adapter: danh sách câu đáp án ──────────────────────────────────────────────

class KeyQuestionAdapter(
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<KeyQuestionAdapter.VH>() {

    private var items: List<Pair<Int, Set<String>>> = emptyList()

    fun submit(map: Map<Int, Set<String>>) {
        items = map.toList()
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNum: TextView = v.findViewById(R.id.tvKeyQNum)
        val tvAns: TextView = v.findViewById(R.id.tvKeyQAns)
        val tvStatus: TextView = v.findViewById(R.id.tvKeyQStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_key_question, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val (qNum, ans) = items[pos]
        val txt = ans.sorted().joinToString("")
        holder.tvNum.text = "Câu $qNum"
        holder.tvAns.text = txt.ifEmpty { "—" }
        if (ans.isEmpty()) {
            holder.tvStatus.text = "⚠ chưa đọc được"
            holder.tvStatus.setTextColor(0xFFD32F2F.toInt())
            holder.tvAns.setTextColor(0xFFD32F2F.toInt())
        } else {
            holder.tvStatus.text = "✓"
            holder.tvStatus.setTextColor(0xFF2196F3.toInt())
            holder.tvAns.setTextColor(0xFF2196F3.toInt())
        }
        holder.itemView.setOnClickListener { onEdit(qNum) }
    }
}
