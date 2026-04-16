package com.gradesnap.omr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * Photo picker kiểu Zalo:
 *   - Grid 3 cột, ảnh từ MediaStore (gần nhất trước)
 *   - Ô đầu = "Chụp ảnh" (mở camera)
 *   - Tap để chọn/bỏ chọn, hiện số thứ tự
 *   - Nút "Xong (N)" ở dưới để confirm
 *
 * Dùng: startActivityForResult với EXTRA_MAX nếu muốn giới hạn số ảnh
 * Trả về: ArrayList<String> qua RESULT_URIS
 */
class PhotoPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAX         = "max_count"   // giới hạn chọn (0 = unlimited)
        const val RESULT_URIS       = "result_uris"
        private const val DEFAULT_MAX = 0
    }

    private lateinit var adapter: PhotoGridAdapter
    private lateinit var rvGrid: RecyclerView
    private lateinit var btnConfirm: MaterialButton
    private var maxCount = DEFAULT_MAX

    // ── Camera capture ─────────────────────────────────────────────────────────
    // CropRotateActivity handles its own camera (CameraX) — no TakePicture contract needed.
    // Receives the final photo URI from CropRotateActivity and returns it immediately.
    private val cropRotateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uriStr = result.data?.getStringExtra(CropRotateActivity.RESULT_IMAGE_URI)
            if (uriStr != null) {
                setResult(RESULT_OK, Intent().apply {
                    putStringArrayListExtra(RESULT_URIS, arrayListOf(uriStr))
                })
                finish()
            }
        }
    }

    // ── Camera permission ─────────────────────────────────────────────────────
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCropRotate()
        else Toast.makeText(this, "Cần quyền Camera để chụp ảnh", Toast.LENGTH_LONG).show()
    }

    // ── Gallery permission ────────────────────────────────────────────────────
    private val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadPhotos()
        else Toast.makeText(this, "Cần quyền truy cập ảnh để hiện thư viện", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_picker)

        maxCount = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = "Chọn ảnh"
            setDisplayHomeAsUpEnabled(true)
        }

        btnConfirm = findViewById(R.id.btnConfirm)
        btnConfirm.isEnabled = false
        btnConfirm.setOnClickListener { returnResults() }

        rvGrid = findViewById(R.id.rvPhotoGrid)
        adapter = PhotoGridAdapter(
            onCameraClick = { launchCamera() },
            onSelectionChanged = { count -> updateConfirmButton(count) },
            maxCount = maxCount
        )
        rvGrid.layoutManager = GridLayoutManager(this, 3)
        rvGrid.adapter = adapter

        // Attach drag-select gesture listener
        rvGrid.addOnItemTouchListener(DragSelectTouchListener())

        // Check permission then load gallery
        if (ContextCompat.checkSelfPermission(this, readPermission) == PackageManager.PERMISSION_GRANTED) {
            loadPhotos()
        } else {
            permissionLauncher.launch(readPermission)
        }
    }

    private fun loadPhotos() {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor: Cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                uris.add(contentUri)
            }
        }
        adapter.setPhotos(uris)
    }

    private fun launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            openCropRotate()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCropRotate() {
        cropRotateLauncher.launch(Intent(this, CropRotateActivity::class.java))
    }

    private fun updateConfirmButton(count: Int) {
        btnConfirm.isEnabled = count > 0
        btnConfirm.text = if (count > 0) "Xong ($count)" else "Xong"
    }

    private fun returnResults() {
        val selected = adapter.getSelectedUris().map { it.toString() }
        setResult(RESULT_OK, Intent().apply {
            putStringArrayListExtra(RESULT_URIS, ArrayList(selected))
        })
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(RESULT_CANCELED)
        finish()
        return true
    }

    // ─── Drag-Select Touch Listener ──────────────────────────────────────────────
    /**
     * Long-press + drag để chọn nhiều ảnh một lượt (giống Google Photos).
     *
     * Logic:
     *   1. ACTION_DOWN → ghi nhớ vị trí, bắt đầu đếm long-press timeout
     *   2. ACTION_MOVE trước timeout + vượt touchSlop → huỷ (user đang scroll)
     *   3. Long-press fires → vào drag mode, rung nhẹ, chọn item dưới ngón tay
     *   4. ACTION_MOVE trong drag mode → tìm item dưới ngón tay → chọn nếu mới
     *   5. ACTION_UP/CANCEL → thoát drag mode
     */
    private inner class DragSelectTouchListener : RecyclerView.OnItemTouchListener {

        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val touchSlop = ViewConfiguration.get(this@PhotoPickerActivity).scaledTouchSlop
        private val handler = Handler(Looper.getMainLooper())

        private var isDragging   = false
        private var startX       = 0f
        private var startY       = 0f
        private var lastPos      = RecyclerView.NO_ID.toInt()
        private var longPressFired = false

        private val longPressRunnable = Runnable {
            // Fired after long-press timeout → enter drag mode
            val rv = rvGrid
            val view = rv.findChildViewUnder(startX, startY) ?: return@Runnable
            val pos  = rv.getChildAdapterPosition(view)
            if (pos <= 0) return@Runnable  // skip camera cell (pos 0)
            isDragging    = true
            longPressFired = true
            lastPos       = pos
            adapter.selectAt(pos - 1)  // -1 offset for camera cell
            vibrate()
            rv.parent.requestDisallowInterceptTouchEvent(true)
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging     = false
                    longPressFired = false
                    startX = e.x; startY = e.y
                    lastPos = RecyclerView.NO_ID.toInt()
                    handler.removeCallbacks(longPressRunnable)
                    // Only schedule if finger is on a photo (not camera cell)
                    val view = rv.findChildViewUnder(e.x, e.y)
                    val pos  = view?.let { rv.getChildAdapterPosition(it) } ?: -1
                    if (pos > 0) handler.postDelayed(longPressRunnable, longPressTimeout)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        // Cancel long-press if finger moved too far
                        val dx = e.x - startX; val dy = e.y - startY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) {
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    isDragging     = false
                    longPressFired = false
                    rv.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return isDragging
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            if (!isDragging) return
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val view = rv.findChildViewUnder(e.x, e.y) ?: return
                    val pos  = rv.getChildAdapterPosition(view)
                    if (pos > 0 && pos != lastPos) {
                        lastPos = pos
                        adapter.selectAt(pos - 1)   // -1 for camera offset
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging     = false
                    longPressFired = false
                    rv.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

        @Suppress("DEPRECATION")
        private fun vibrate() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(
                        VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        v.vibrate(40)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}

// ─── Grid Adapter ─────────────────────────────────────────────────────────────

class PhotoGridAdapter(
    private val onCameraClick: () -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val maxCount: Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_CAMERA = 0
        private const val TYPE_PHOTO  = 1
    }

    private val photos = mutableListOf<Uri>()
    // uri → selection order (1-based)
    private val selected = mutableMapOf<Uri, Int>()
    private var selectionCounter = 0

    fun setPhotos(uris: List<Uri>) {
        photos.clear()
        photos.addAll(uris)
        notifyDataSetChanged()
    }

    fun getSelectedUris(): List<Uri> =
        selected.entries.sortedBy { it.value }.map { it.key }

    /**
     * Chọn item tại photo-index [photoIdx] (0-based trong photos list, không tính camera cell).
     * Không toggle: chỉ add nếu chưa có. Dùng cho drag-select.
     */
    fun selectAt(photoIdx: Int) {
        if (photoIdx < 0 || photoIdx >= photos.size) return
        val uri = photos[photoIdx]
        if (selected.containsKey(uri)) return   // đã chọn rồi, bỏ qua
        if (maxCount > 0 && selected.size >= maxCount) return
        selectionCounter++
        selected[uri] = selectionCounter
        notifyItemChanged(photoIdx + 1)          // +1 vì camera cell ở pos 0
        onSelectionChanged(selected.size)
    }

    override fun getItemViewType(position: Int) =
        if (position == 0) TYPE_CAMERA else TYPE_PHOTO

    override fun getItemCount() = photos.size + 1   // +1 for camera cell

    inner class CameraVH(view: View) : RecyclerView.ViewHolder(view) {
        init { view.setOnClickListener { onCameraClick() } }
    }

    inner class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
        val ivPhoto: ImageView  = view.findViewById(R.id.ivPhoto)
        val tvOrder: TextView   = view.findViewById(R.id.tvOrder)
        val vOverlay: View      = view.findViewById(R.id.vOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CAMERA) {
            CameraVH(inflater.inflate(R.layout.item_photo_camera, parent, false))
        } else {
            PhotoVH(inflater.inflate(R.layout.item_photo_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PhotoVH) {
            val uri = photos[position - 1]   // -1 for camera offset
            val order = selected[uri]

            // Load thumbnail
            holder.ivPhoto.setImageURI(null)   // reset first to avoid flicker
            holder.ivPhoto.setImageURI(uri)

            // Selection state
            if (order != null) {
                holder.vOverlay.visibility = View.VISIBLE
                holder.tvOrder.visibility  = View.VISIBLE
                holder.tvOrder.text        = order.toString()
            } else {
                holder.vOverlay.visibility = View.GONE
                holder.tvOrder.visibility  = View.GONE
            }

            holder.itemView.setOnClickListener {
                val alreadySelected = selected.containsKey(uri)
                if (alreadySelected) {
                    // Deselect: renumber remaining items
                    val removedOrder = selected.remove(uri)!!
                    selected.entries.forEach { entry ->
                        if (entry.value > removedOrder) {
                            selected[entry.key] = entry.value - 1
                        }
                    }
                    selectionCounter = selected.size
                    notifyDataSetChanged()
                    onSelectionChanged(selected.size)
                } else {
                    // Check max limit
                    if (maxCount > 0 && selected.size >= maxCount) {
                        Toast.makeText(
                            holder.itemView.context,
                            "Tối đa $maxCount ảnh",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    selectionCounter++
                    selected[uri] = selectionCounter
                    notifyItemChanged(position)
                    onSelectionChanged(selected.size)
                }
            }
        }
    }
}
