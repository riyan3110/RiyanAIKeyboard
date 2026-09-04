package com.riyan.aikeyboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gallery rendered entirely inside the IME/search panel.
 * It never starts an external photo picker, so the keyboard keeps focus while browsing photos.
 */
class InternalGalleryPanel(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailExecutor = Executors.newFixedThreadPool(3)
    private val generation = AtomicInteger(0)
    @Volatile private var released = false

    fun show(
        container: FrameLayout,
        onSelected: (Uri) -> Unit,
        onCamera: () -> Unit,
        onPermissionRequired: () -> Unit
    ) {
        released = false
        val token = generation.incrementAndGet()
        container.removeAllViews()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(21, 21, 27))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
        }
        header.addView(TextView(context).apply {
            text = "Galeri · Terbaru"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_camera_modern)
            setColorFilter(Color.WHITE)
            contentDescription = "Kembali ke kamera"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setBackgroundColor(Color.rgb(49, 48, 58))
            setOnClickListener { onCamera() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))

        if (!hasPhotoPermission()) {
            val permissionBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            permissionBox.addView(TextView(context).apply {
                text = "Akses Foto & Video diperlukan satu kali agar galeri bisa tampil langsung di dalam keyboard."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            permissionBox.addView(Button(context).apply {
                text = "Izinkan akses foto"
                isAllCaps = false
                setOnClickListener { onPermissionRequired() }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
            root.addView(permissionBox, LinearLayout.LayoutParams(-1, 0, 1f))
            container.addView(root, FrameLayout.LayoutParams(-1, -1))
            return
        }

        val status = TextView(context).apply {
            text = "Memuat foto…"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        scroll.addView(rows, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        container.addView(root, FrameLayout.LayoutParams(-1, -1))

        thumbnailExecutor.execute {
            val images = queryRecentImages(MAX_IMAGES)
            mainHandler.post {
                if (released || token != generation.get()) return@post
                status.text = if (images.isEmpty()) "Tidak ada foto yang dapat ditampilkan" else "${images.size} foto terbaru"
                renderRows(rows, images, token, onSelected)
            }
        }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun renderRows(
        parent: LinearLayout,
        images: List<Uri>,
        token: Int,
        onSelected: (Uri) -> Unit
    ) {
        parent.removeAllViews()
        val columns = if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        val cellHeight = if (columns >= 5) dp(74) else dp(96)

        images.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            for (index in 0 until columns) {
                if (index < chunk.size) {
                    val uri = chunk[index]
                    val image = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.rgb(37, 37, 45))
                        contentDescription = "Pilih foto"
                        isClickable = true
                        setOnClickListener {
                            if (!released && token == generation.get()) onSelected(uri)
                        }
                    }
                    row.addView(image, LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                    loadThumbnailAsync(uri, image, token)
                } else {
                    row.addView(View(context), LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, cellHeight + dp(4)))
        }
    }

    private fun loadThumbnailAsync(uri: Uri, target: ImageView, token: Int) {
        thumbnailExecutor.execute {
            val bitmap = loadThumbnail(uri)
            mainHandler.post {
                if (released || token != generation.get()) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadThumbnail(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
        } else {
            decodeSampledBitmap(uri, 360)
        }
    }.getOrNull()

    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxDimension * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun queryRecentImages(limit: Int): List<Uri> {
        val result = ArrayList<Uri>(limit)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idColumn)
                result += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return result
    }

    private fun hasPhotoPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 34 -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= 33 -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val MAX_IMAGES = 120
    }
}
