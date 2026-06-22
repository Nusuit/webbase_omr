package com.gradesnap.omr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
/**
 * Màn hình batch scan:
 *   - Quét tất cả ảnh trong folder đáp án trước → build AnswerKey map
 *   - Quét bài làm học viên → chấm điểm → lưu kết quả
 *   - Hiển thị tiến trình (ProgressBar) + log real-time
 *
 * ═══ TUẦN TỰ vs SONG SONG ═══
 * Hiện tại: TUẦN TỰ (sequential, coroutine IO dispatcher, 1 ảnh/lần)
 *   ✅ Ổn định: OpenCV Mat operations không thread-safe nếu cùng dùng 1 Mat
 *   ✅ Dễ debug: log rõ ràng từng bước
 *   ✅ Ít lỗi OOM: load bitmap từng cái, giải phóng trước khi load cái tiếp
 *   ⚠ Chậm hơn: ~1.5–3s/ảnh → 50 ảnh ≈ 2–3 phút
 *
 * Nếu muốn SONG SONG (parallel):
 *   - Dùng Dispatchers.Default với coroutineScope { launch { ... } }
 *   - Cần tạo OmrProcessor riêng cho mỗi coroutine (không share Mat)
 *   - Có thể tăng tốc 3–5x trên máy có ≥4 core
 *   - Nguy cơ OOM nếu load nhiều bitmap lớn cùng lúc → cần semaphore
 *   - Recommend: Semaphore(3) để max 3 ảnh xử lý song song
 *   VD: val sem = Semaphore(3); async { sem.withPermit { processImage(...) } }
 */
class ProcessingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID      = "project_id"
        // EXTRA_ASSIGNMENT_URIS và EXTRA_KEY_URIS đã bỏ —
        // URIs được đọc trực tiếp từ project trong repo để tránh TransactionTooLargeException
    }

    private lateinit var repo: ExamRepository
    private lateinit var logger: ProcessingLogger
    private lateinit var logAdapter: LogAdapter

    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvLog: RecyclerView
    private lateinit var btnClose: MaterialButton

    // ── Cached state for re-scan after landscape alert ─────────────────────────
    private var cachedProjectId: String? = null
    private var cachedAnswerKeys: Map<String, AnswerKey> = emptyMap()
    private var cachedProcessor: OmrProcessor? = null

    // Launcher for picking replacement images after landscape warning
    private val replacePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) rescanReplacements(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_processing)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { title = "Đang xử lý..."; setDisplayHomeAsUpEnabled(false) }

        repo   = ExamRepository(this)
        logger = ProcessingLogger()

        progressBar   = findViewById(R.id.progressBar)
        tvProgressText = findViewById(R.id.tvProgressText)
        tvStatus      = findViewById(R.id.tvStatus)
        rvLog         = findViewById(R.id.rvLog)
        btnClose      = findViewById(R.id.btnClose)
        btnClose.isEnabled = false

        logAdapter = LogAdapter()
        rvLog.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        rvLog.adapter = logAdapter

        // Collect logs in real-time — phải dùng collect (không phải collectLatest)
        // collectLatest sẽ cancel block cũ khi có item mới → mất hầu hết log trong batch
        lifecycleScope.launch {
            logger.logs.collect { log ->
                logAdapter.addLog(log)
                rvLog.scrollToPosition(logAdapter.itemCount - 1)
            }
        }

        val projectId   = intent.getStringExtra(EXTRA_PROJECT_ID)
        val project     = projectId?.let { repo.loadProject(it) }

        if (projectId == null || project == null) {
            showFatalError("Không tìm được project.\nprojectId=$projectId")
            return
        }

        val assignUris = project.assignmentImageUris
        val keyUris    = project.answerKeyImageUris  // không dùng để scan nữa — đáp án lấy từ key đã xác nhận

        if (assignUris.isEmpty()) {
            showFatalError("Project chưa có ảnh bài làm học viên.")
            return
        }

        startProcessing(projectId, assignUris, keyUris)
    }

    private fun startProcessing(projectId: String, assignUriStrs: List<String>, keyUriStrs: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                runProcessing(projectId, assignUriStrs, keyUriStrs)
            } catch (e: Throwable) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val trace = sw.toString()
                Log.e("GradeSnap", "CRASH in processing: $trace")
                // Lưu vào file để đọc sau
                saveCrashLog("CRASH: ${e::class.simpleName}: ${e.message}\n$trace")
                logger.error("💥 CRASH: ${e::class.simpleName}: ${e.message}")
                logger.error("Stack: ${trace.take(500)}")
                withContext(Dispatchers.Main) {
                    tvStatus.text = "❌ Lỗi: ${e::class.simpleName}: ${e.message}"
                    btnClose.isEnabled = true
                    btnClose.setOnClickListener { finish() }
                    showFatalError("Lỗi nghiêm trọng:\n${e::class.simpleName}: ${e.message}\n\nXem chi tiết trong log bên dưới")
                }
            }
        }
    }

    private suspend fun runProcessing(projectId: String, assignUriStrs: List<String>, keyUriStrs: List<String>) {
            val project = repo.loadProject(projectId) ?: run {
                logger.error("Không tìm được project $projectId")
                return
            }
            cachedProjectId = projectId

            // Khởi tạo OpenCV — thử nhiều cách theo thứ tự ưu tiên
            logger.info("Đang khởi tạo OpenCV...")
            val opencvOk = initOpenCV()
            if (!opencvOk) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "❌ Không khởi tạo được OpenCV"
                    btnClose.isEnabled = true
                    btnClose.setOnClickListener { finish() }
                }
                return
            }
            logger.info("✅ OpenCV khởi tạo thành công")

            val processor = OmrProcessor(this@ProcessingActivity)
            cachedProcessor = processor

            // ── 1. Chuẩn bị danh sách URI bài làm ────────────────────────────
            val assignFiles = assignUriStrs.map { Pair(Uri.parse(it), getDisplayName(Uri.parse(it))) }
            val landscapeFiles = mutableListOf<String>() // images detected as landscape (width > height)

            // ── 2. Đáp án đã xác nhận ở Bước 1 (không scan lại) ──────────────
            val answerKeys = repo.loadConfirmedKeys(projectId)
            if (answerKeys.isEmpty()) {
                logger.error("⛔ Chưa có đáp án xác nhận. Hãy quét & xác nhận đáp án ở Bước 1.")
                withContext(Dispatchers.Main) {
                    tvStatus.text = "⛔ Chưa có đáp án xác nhận"
                    btnClose.isEnabled = true
                    btnClose.setOnClickListener { finish() }
                    showFatalError("Hãy quét & xác nhận đáp án ở Bước 1 trước khi chấm.")
                }
                return
            }
            cachedAnswerKeys = answerKeys
            logger.info("═══ Dùng ${answerKeys.size} mã đề đã xác nhận: ${answerKeys.keys.joinToString()}")
            logger.info("📋 Bài làm: ${assignFiles.size} ảnh")

            val total = assignFiles.size
            var done  = 0

            // ── 3. Scan bài làm học viên ─────────────────────────────────────
            setProgress(done, total, "Đang scan bài làm học viên...")
            var successCount = 0; var errorCount = 0; var suspCount = 0
            val studentsNoMssv = mutableListOf<String>() // validation: student sheets should have MSSV
            var streamIdx = 0

            for ((uri, name) in assignFiles) {
                streamIdx++
                val sheetStart = SystemClock.elapsedRealtime()

                logger.info("── Bài làm: $name")
                val loadStart = SystemClock.elapsedRealtime()
                val bmp = loadBitmap(uri)
                val loadMs = SystemClock.elapsedRealtime() - loadStart

                if (bmp == null) {
                    logger.warn("⚠ Không thể đọc ảnh: $name")
                    done++; setProgress(done, total, "Đang scan bài..."); errorCount++; continue
                }
                if (bmp.width > bmp.height) {
                    landscapeFiles += name
                    logger.warn("⚠ Ảnh '$name' có vẻ chụp NGANG — kết quả scan sẽ SAI. Vui lòng chụp lại theo chiều dọc.")
                }
                try {
                    val processStart = SystemClock.elapsedRealtime()
                    val raw = processor.process(
                        bitmap    = bmp,
                        sourceFile = name,
                        isAnswerKey = false,
                        logCallback = logger.blockingCallback(LogLevel.INFO)
                    )
                    val processMs = SystemClock.elapsedRealtime() - processStart
                    bmp.recycle()

                    // Validate: student sheet should have MSSV
                    if (raw.mssv == null) {
                        studentsNoMssv += name
                    }

                    // Tìm đáp án theo mã đề
                    val key = raw.examCode?.let { answerKeys[it] }
                    if (key == null) {
                        logger.warn("⚠ Không có đáp án cho mã đề '${raw.examCode}' (bài: $name)")
                    }

                    val studentResult = if (key != null) {
                        ScoringEngine.score(raw, key, name)
                    } else {
                        // Không có đáp án → lưu không chấm điểm
                        StudentResult(
                            id = raw.mssv ?: name,
                            mssv = raw.mssv,
                            examCode = raw.examCode,
                            score = 0.0, rawScore = 0,
                            totalQuestions = raw.answers.size,
                            questions = raw.answers.map { (q, ans) ->
                                QuestionResult(q, ans, emptySet(), false, q in raw.suspicious)
                            }.sortedBy { it.questionNum },
                            warpedImagePath = raw.warpedImagePath,
                            sourceFileName = name,
                            hasSuspicious = raw.suspicious.isNotEmpty()
                        )
                    }

                    val saveStart = SystemClock.elapsedRealtime()
                    repo.saveStudentResult(projectId, studentResult)
                    val saveMs  = SystemClock.elapsedRealtime() - saveStart
                    val totalMs = SystemClock.elapsedRealtime() - sheetStart

                    android.util.Log.i("StreamTiming",
                        "[STREAM] sheet=${streamIdx}/${assignFiles.size}" +
                        "  load_bitmap=${loadMs}ms" +
                        "  process=${processMs}ms" +
                        "  save=${saveMs}ms" +
                        "  total=${totalMs}ms")

                    successCount++
                    if (studentResult.hasSuspicious) {
                        suspCount++
                        logger.warn("⚠ ${studentResult.mssv ?: name} có ${studentResult.questions.count { it.isSuspicious }} câu suspicious")
                    } else {
                        logger.success("✅ ${studentResult.mssv ?: name}: ${"%.2f".format(studentResult.score)}/10")
                    }

                } catch (e: Exception) {
                    logger.error("❌ Lỗi bài $name: ${e.message}")
                    errorCount++
                }
                done++
                setProgress(done, total, "Bài làm: $done/$total")
            }

            // ── Validate: warn about student sheets missing MSSV ───────────
            if (studentsNoMssv.isNotEmpty()) {
                logger.warn("⚠ ${studentsNoMssv.size} bài làm không đọc được MSSV")
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ProcessingActivity)
                        .setTitle("⚠ Thiếu MSSV")
                        .setMessage(
                            "${studentsNoMssv.size} bài làm trong thư mục học viên " +
                            "không đọc được MSSV:\n\n" +
                            studentsNoMssv.joinToString("\n") { "• $it" } +
                            "\n\nCác bài này vẫn được lưu nhưng sẽ không có MSSV. " +
                            "Kiểm tra lại ảnh chụp."
                        )
                        .setPositiveButton("Đã hiểu", null)
                        .show()
                }
            }
            // ── Validate: alert for landscape images ─────────────────────────────
            if (landscapeFiles.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ProcessingActivity)
                        .setTitle("📵 Ảnh chụp ngang!")
                        .setMessage(
                            "${landscapeFiles.size} ảnh có chiều NGANG (rộng > cao):\n\n" +
                            landscapeFiles.joinToString("\n") { "• $it" } +
                            "\n\nVui lòng chọn ảnh thay thế chụp đúng chiều DỌC."
                        )
                        .setNegativeButton("Bỏ qua", null)
                        .setPositiveButton("⬆ Chọn ảnh thay thế") { _, _ ->
                            replacePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .show()
                }
            }
            // ── 4. Tổng kết ──────────────────────────────────────────────────
            logger.success("═══ HOÀN TẤT ═══")
            logger.success("Thành công: $successCount | Lỗi: $errorCount | Suspicious: $suspCount")
            if (suspCount > 0) {
                logger.warn("⚠ CÓ $suspCount BÀI SUSPICIOUS — Vui lòng vào màn hình 'Kiểm tra Suspicious' để review!")
            }

            withContext(Dispatchers.Main) {
                supportActionBar?.title = "Hoàn tất"
                progressBar.progress = total
                tvProgressText.text = "$total/$total"
                tvStatus.text = "Xong! $successCount bài thành công, $errorCount lỗi, $suspCount cần review"
                btnClose.isEnabled = true
                btnClose.setOnClickListener { finish() }
            }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Scan replacement images chosen after landscape warning.
     * Uses already-cached answerKeys + processor — no re-import of key files.
     */
    private fun rescanReplacements(uris: List<Uri>) {
        val projectId = cachedProjectId ?: return
        val answerKeys = cachedAnswerKeys
        val processor  = cachedProcessor ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                btnClose.isEnabled = false
                tvStatus.text = "Đang scan lại ${uris.size} ảnh thay thế..."
            }

            var success = 0; var fail = 0
            for (uri in uris) {
                val name = getDisplayName(uri)
                logger.info("── [Rescan] Bài làm: $name")
                val bmp = loadBitmap(uri)
                if (bmp == null) {
                    logger.warn("⚠ Không đọc được ảnh: $name")
                    fail++; continue
                }
                if (bmp.width > bmp.height) {
                    logger.warn("⚠ Ảnh '$name' vẫn còn ngang!")
                    bmp.recycle(); fail++; continue
                }
                try {
                    val raw = processor.process(
                        bitmap    = bmp,
                        sourceFile = name,
                        isAnswerKey = false,
                        logCallback = logger.blockingCallback(LogLevel.INFO)
                    )
                    bmp.recycle()
                    val key = raw.examCode?.let { answerKeys[it] }
                    val studentResult = if (key != null) {
                        ScoringEngine.score(raw, key, name)
                    } else {
                        StudentResult(
                            id = raw.mssv ?: name.substringBeforeLast("."),
                            mssv = raw.mssv,
                            examCode = raw.examCode,
                            score = 0.0, rawScore = 0,
                            totalQuestions = raw.answers.size,
                            questions = raw.answers.map { (q, ans) ->
                                QuestionResult(q, ans, emptySet(), false, q in raw.suspicious)
                            }.sortedBy { it.questionNum },
                            warpedImagePath = raw.warpedImagePath,
                            sourceFileName = name,
                            hasSuspicious = raw.suspicious.isNotEmpty()
                        )
                    }
                    repo.saveStudentResult(projectId, studentResult)
                    logger.success("✅ [Rescan] ${studentResult.mssv ?: name}: ${"%.2f".format(studentResult.score)}/10")
                    success++
                } catch (e: Exception) {
                    logger.error("❌ [Rescan] Lỗi $name: ${e.message}")
                    fail++
                }
            }

            withContext(Dispatchers.Main) {
                tvStatus.text = "Rescan xong: $success thành công, $fail lỗi"
                btnClose.isEnabled = true
                btnClose.setOnClickListener { finish() }
            }
        }
    }

    /**
     * Khởi tạo OpenCV — dùng OpenCVLoader.initLocal() (embedded AAR, không cần OpenCV Manager)
     */
    private fun initOpenCV(): Boolean {
        return try {
            // OpenCVLoader.initLocal() is the correct API for embedded OpenCV 4.9+
            // It handles ABI selection automatically without needing OpenCV Manager app
            val ok = OpenCVLoader.initLocal()
            if (ok) {
                logger.blockingCallback(LogLevel.INFO)("✅ OpenCV ${OpenCVLoader.OPENCV_VERSION} loaded via initLocal()")
            } else {
                logger.blockingCallback(LogLevel.ERROR)("initLocal() returned false")
            }
            ok
        } catch (e: Throwable) {
            logger.blockingCallback(LogLevel.ERROR)("initLocal() exception: ${e.message?.take(120)}")
            false
        }
    }

    /** Suspending dialog to manually enter exam code when OCR fails. Returns null if skipped. */
    private suspend fun promptExamCode(fileName: String): String? =
        suspendCancellableCoroutine { cont ->
            val input = android.widget.EditText(this@ProcessingActivity).apply {
                hint = "VD: 001"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(48, 24, 48, 24)
            }
            val dialog = AlertDialog.Builder(this@ProcessingActivity)
                .setTitle("Không đọc được mã đề")
                .setMessage("File: $fileName\n\nHệ thống không tự đọc được mã đề. Nhập thủ công:")
                .setView(input)
                .setPositiveButton("Xác nhận") { _, _ ->
                    val code = input.text.toString().trim()
                    if (cont.isActive) cont.resume(code.ifEmpty { null })
                }
                .setNegativeButton("Bỏ qua file này") { _, _ ->
                    if (cont.isActive) cont.resume(null)
                }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
            input.requestFocus()
        }

    /** Suspending confirmation dialog — returns true if user picks 'Tiếp tục', false if 'Huỷ' */
    private suspend fun showConfirmDialog(title: String, message: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this@ProcessingActivity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Tiếp tục") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Huỷ") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun showFatalError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Lỗi")
            .setMessage(msg)
            .setPositiveButton("Đóng") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun saveCrashLog(content: String) {
        try {
            val f = File(getExternalFilesDir(null), "crash_log.txt")
            f.writeText(content)
        } catch (_: Exception) {}
    }

    private suspend fun setProgress(done: Int, total: Int, status: String) {
        withContext(Dispatchers.Main) {
            progressBar.max = total
            progressBar.progress = done
            tvProgressText.text = "$done/$total"
            tvStatus.text = status
        }
    }

    /** Get display name for a content URI */
    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: uri.toString()
        } catch (_: Exception) { uri.lastPathSegment ?: uri.toString() }
    }

    /**
     * Load bitmap từ URI và tự động xoay theo EXIF orientation.
     * Chỉ xử lý EXIF — nếu chụp ngang/ngược thì kết quả chấm có thể sai (chấp nhận được).
     */
    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null

            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees != 0f) {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    bmp.recycle()
                    r
                } else bmp
            } ?: bmp
        } catch (_: Exception) { null }
    }
}

// ─── Log Adapter ──────────────────────────────────────────────────────────────

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
    private val logs = mutableListOf<ProcessingLog>()

    fun addLog(log: ProcessingLog) {
        logs.add(log)
        notifyItemInserted(logs.size - 1)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvLog: TextView = view.findViewById(R.id.tvLog)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun getItemCount() = logs.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val log = logs[pos]
        holder.tvLog.text = log.message
        holder.tvLog.setTextColor(when (log.level) {
            LogLevel.ERROR   -> 0xFFD32F2F.toInt()
            LogLevel.WARN    -> 0xFFF57C00.toInt()
            LogLevel.SUCCESS -> 0xFF388E3C.toInt()
            LogLevel.INFO    -> 0xFF212121.toInt()
        })
    }
}
