package com.gradesnap.omr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class BenchmarkActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnPick: Button
    private lateinit var btnRun: Button
    private lateinit var btnRunCv: Button

    private val pickedRawBytes = mutableListOf<ByteArray>()
    private val pickedFilenames = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)
        supportActionBar?.title = "YOLO Benchmark"

        tvLog     = findViewById(R.id.tvBenchLog)
        svLog     = findViewById(R.id.svBenchLog)
        btnPick   = findViewById(R.id.btnPickImages)
        btnRun    = findViewById(R.id.btnRunBenchmark)
        btnRunCv  = findViewById(R.id.btnRunCvBenchmark)

        if (!OpenCVLoader.initLocal()) {
            appendLog("ERROR: OpenCV failed to load")
        }

        if (HeadlessBenchmark.shouldHandle(intent)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnPick.isEnabled = false; btnRun.isEnabled = false; btnRunCv.isEnabled = false
            appendLog("[HEADLESS] starting…")
            val intentSnapshot = intent
            lifecycleScope.launch(Dispatchers.Default) {
                val status = HeadlessBenchmark.run(applicationContext, intentSnapshot) { msg ->
                    lifecycleScope.launch(Dispatchers.Main) { appendLog(msg) }
                }
                withContext(Dispatchers.Main) {
                    appendLog("[HEADLESS] $status")
                    appendLog("[HEADLESS] === FINISHED ===")
                }
            }
            return
        }

        btnPick.setOnClickListener   { pickImages() }
        btnPick.setOnLongClickListener {
            pickedRawBytes.clear(); pickedFilenames.clear()
            appendLog("[CLEARED] picked-image buffer reset to 0.")
            btnRun.isEnabled = false; btnRunCv.isEnabled = false
            true
        }
        btnRun.setOnClickListener    { runBenchmark() }
        btnRunCv.setOnClickListener  { runCvBenchmark() }
        btnRun.isEnabled   = false
        btnRunCv.isEnabled = false

        appendLog("Pick images — APPEND mode. Tap Pick multiple times to add more (e.g., 100 + 79 = 179).")
        appendLog("Long-press [Pick Images] to clear the buffer.")
        appendLog("Duplicates (same filename) auto-skipped.")
        appendLog("Run YOLO Bench  → YOLO paper detection + OMR (CPU-1T / CPU-4T / NNAPI).")
        appendLog("Run CV Benchmark → pure OpenCV OMR, no YOLO.")
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(intent, "Select answer sheets"), REQ_PICK)
    }

    @Deprecated("Using onActivityResult for simplicity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null) return

        var added = 0
        var skipped = 0
        var failed = 0
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                when (tryAdd(uri)) {
                    AddResult.ADDED   -> added++
                    AddResult.SKIPPED -> skipped++
                    AddResult.FAILED  -> failed++
                }
            }
        } else {
            data.data?.let {
                when (tryAdd(it)) {
                    AddResult.ADDED   -> added++
                    AddResult.SKIPPED -> skipped++
                    AddResult.FAILED  -> failed++
                }
            }
        }

        appendLog("[PICK] +$added new, skipped $skipped dup, failed $failed.  TOTAL = ${pickedRawBytes.size}")
        btnRun.isEnabled   = pickedRawBytes.isNotEmpty()
        btnRunCv.isEnabled = pickedRawBytes.isNotEmpty()
    }

    private enum class AddResult { ADDED, SKIPPED, FAILED }

    private fun tryAdd(uri: Uri): AddResult {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "uri_${System.nanoTime()}"
        if (pickedFilenames.contains(name)) return AddResult.SKIPPED
        val bytes = loadUri(uri) ?: return AddResult.FAILED
        pickedRawBytes += bytes
        pickedFilenames += name
        return AddResult.ADDED
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun loadUri(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }

    private fun runBenchmark() {
        if (pickedRawBytes.isEmpty()) { appendLog("No images loaded."); return }
        setBusy(true)
        tvLog.text = ""

        lifecycleScope.launch(Dispatchers.Default) {
            val runner = YoloBenchmarkRunner(applicationContext)
            val summary = runner.run(pickedRawBytes.toList()) { msg ->
                lifecycleScope.launch(Dispatchers.Main) { appendLog(msg) }
            }
            withContext(Dispatchers.Main) {
                appendLog("")
                appendLog("=== DONE ===")
                appendLog(summary.paperReadyText)
                setBusy(false)
            }
        }
    }

    private fun runCvBenchmark() {
        if (pickedRawBytes.isEmpty()) { appendLog("No images loaded."); return }
        setBusy(true)
        tvLog.text = ""

        lifecycleScope.launch(Dispatchers.Default) {
            val runner = CvBenchmarkRunner(applicationContext)
            val summary = runner.run(pickedRawBytes.toList()) { msg ->
                lifecycleScope.launch(Dispatchers.Main) { appendLog(msg) }
            }
            withContext(Dispatchers.Main) {
                appendLog("")
                appendLog("=== DONE ===")
                appendLog(summary.paperReadyText)
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        btnRun.isEnabled   = !busy && pickedRawBytes.isNotEmpty()
        btnRunCv.isEnabled = !busy && pickedRawBytes.isNotEmpty()
        btnPick.isEnabled  = !busy
    }

    private fun appendLog(msg: String) {
        tvLog.append(msg + "\n")
        svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val REQ_PICK = 101
    }
}
