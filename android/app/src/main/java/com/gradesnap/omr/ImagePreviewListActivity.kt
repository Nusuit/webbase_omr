package com.gradesnap.omr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hiển thị danh sách thumbnail ảnh gốc (bài làm hoặc đáp án).
 * Tap vào ảnh → fullscreen ZoomableImageView.
 */
class ImagePreviewListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_URIS  = "uris"          // ArrayList<String>

        fun start(from: android.app.Activity, title: String, uris: List<String>) {
            from.startActivity(Intent(from, ImagePreviewListActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview_list)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Ảnh"
        val uriStrings = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { this.title = title; setDisplayHomeAsUpEnabled(true) }

        val rv = findViewById<RecyclerView>(R.id.rvImages)
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.adapter = ImageThumbAdapter(uriStrings) { uriStr ->
            FullscreenImageActivity.start(this, uriStr)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

// ─── Grid Adapter ─────────────────────────────────────────────────────────────

class ImageThumbAdapter(
    private val uriStrings: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<ImageThumbAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView  = view.findViewById(R.id.imgThumb)
        val tvName: TextView = view.findViewById(R.id.tvThumbName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_image_thumb, parent, false))

    override fun getItemCount() = uriStrings.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uriStr = uriStrings[position]
        val uri = Uri.parse(uriStr)

        // Display name
        val name = runCatching {
            holder.img.context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "Ảnh ${position + 1}"
        holder.tvName.text = name

        // Load thumbnail async
        holder.img.setImageResource(android.R.drawable.ic_menu_gallery)
        val ctx = holder.img.context
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = 4   // load at 1/4 size for thumbnails
                    }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bmp != null) holder.img.setImageBitmap(bmp)
            }
        }

        holder.itemView.setOnClickListener { onClick(uriStr) }
    }
}

// ─── Fullscreen viewer ────────────────────────────────────────────────────────

class FullscreenImageActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "uri"
        fun start(from: android.app.Activity, uriStr: String) {
            from.startActivity(Intent(from, FullscreenImageActivity::class.java)
                .putExtra(EXTRA_URI, uriStr))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_image)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { title = ""; setDisplayHomeAsUpEnabled(true) }

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: return finish()
        val imgView = findViewById<ZoomableImageView>(R.id.imgFullscreen)

        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bmp != null) imgView.setImageBitmap(bmp)
                else imgView.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
