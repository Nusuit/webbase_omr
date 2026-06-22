package com.gradesnap.omr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

/**
 * Màn hình camera nội bộ + xoay ảnh — một màn hình duy nhất.
 *
 * Phase 1 (Camera): Hiển thị PreviewView + nút chụp.
 * Phase 2 (Review): Hiển thị ảnh đã chụp + nút xoay / "Chụp lại" / "Dùng ảnh này".
 *
 * Đầu ra: RESULT_IMAGE_URI (String) — content URI ảnh hoàn chỉnh.
 */
class CropRotateActivity : AppCompatActivity() {

    companion object {
        const val RESULT_IMAGE_URI = "image_uri"
        // Kept for backwards-compat (not used internally anymore)
        const val EXTRA_IMAGE_PATH = "image_path"
        const val RESULT_RETAKE    = "retake"
    }

    private enum class Phase { CAMERA, REVIEW }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var cameraContainer: View
    private lateinit var reviewContainer: View
    private lateinit var cameraPreview: PreviewView
    private lateinit var imgPreview: ImageView
    private lateinit var btnShutter: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnRetake: MaterialButton
    private lateinit var btnUsePhoto: MaterialButton

    // ── State ───────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturedFile: File? = null
    private var currentBitmap: Bitmap? = null

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_rotate)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = "Chụp ảnh"
            setDisplayHomeAsUpEnabled(true)
        }

        cameraContainer  = findViewById(R.id.cameraContainer)
        reviewContainer  = findViewById(R.id.reviewContainer)
        cameraPreview    = findViewById(R.id.cameraPreview)
        imgPreview       = findViewById(R.id.imgPreview)
        btnShutter       = findViewById(R.id.btnShutter)
        btnRotateLeft    = findViewById(R.id.btnRotateLeft)
        btnRotateRight   = findViewById(R.id.btnRotateRight)
        btnRetake        = findViewById(R.id.btnRetake)
        btnUsePhoto      = findViewById(R.id.btnUsePhoto)

        btnShutter.setOnClickListener      { takePhoto() }
        btnRotateLeft.setOnClickListener   { rotate(-90) }
        btnRotateRight.setOnClickListener  { rotate(90)  }
        btnRetake.setOnClickListener       { showPhase(Phase.CAMERA) }
        btnUsePhoto.setOnClickListener     { saveAndReturn() }

        showPhase(Phase.CAMERA)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Phase control ─────────────────────────────────────────────────────────
    private fun showPhase(phase: Phase) {
        if (phase == Phase.CAMERA) {
            cameraContainer.visibility = View.VISIBLE
            reviewContainer.visibility = View.GONE
            supportActionBar?.title = "Chụp ảnh"
            startCamera()
        } else {
            cameraContainer.visibility = View.GONE
            reviewContainer.visibility = View.VISIBLE
            supportActionBar?.title = "Xem trước"
            stopCamera()
        }
    }

    // ── CameraX ───────────────────────────────────────────────────────────────
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Không thể khởi động camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        btnShutter.isEnabled = false

        val outFile = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        capturedFile = outFile
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()

        ic.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    btnShutter.isEnabled = true
                    val bmp = loadBitmapWithExif(outFile)
                    if (bmp == null) {
                        Toast.makeText(this@CropRotateActivity, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
                        return
                    }
                    currentBitmap?.recycle()
                    currentBitmap = bmp
                    imgPreview.setImageBitmap(bmp)
                    showPhase(Phase.REVIEW)
                }
                override fun onError(e: ImageCaptureException) {
                    btnShutter.isEnabled = true
                    Toast.makeText(this@CropRotateActivity, "Lỗi chụp: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // ── Rotate ────────────────────────────────────────────────────────────────
    private fun rotate(degrees: Int) {
        val src = currentBitmap ?: return
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        src.recycle()
        currentBitmap = rotated
        imgPreview.setImageBitmap(rotated)
    }

    // ── Save & return ─────────────────────────────────────────────────────────
    private fun saveAndReturn() {
        val bmp = currentBitmap
        val file = capturedFile
        if (bmp == null || file == null) {
            Toast.makeText(this, "Lỗi: không có ảnh", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_IMAGE_URI, uri.toString()))
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi lưu ảnh: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── EXIF-aware bitmap loader ──────────────────────────────────────────────
    private fun loadBitmapWithExif(file: File): Bitmap? {
        return try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val exif = ExifInterface(file.absolutePath)
            val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                rotated
            } else bmp
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        currentBitmap = null
    }
}
