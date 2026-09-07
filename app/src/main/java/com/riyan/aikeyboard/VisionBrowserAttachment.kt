package com.riyan.aikeyboard

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Keeps one short-lived camera/gallery image together with the AI Vision query while the
 * embedded browser is opened. The image is stored as JPEG bytes instead of a Bitmap so the
 * scanner preview can be recycled immediately without leaking a large native allocation.
 */
object VisionBrowserAttachment {
    data class Snapshot(
        val jpegBytes: ByteArray,
        val query: String,
        val source: String,
        val createdAtMs: Long
    )

    private val lock = Any()
    private var jpegBytes: ByteArray? = null
    private var query: String = ""
    private var source: String = ""
    private var createdAtMs: Long = 0L

    fun capture(bitmap: Bitmap, source: String) {
        if (bitmap.width < 2 || bitmap.height < 2 || bitmap.isRecycled) return
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > 720) {
            val ratio = 720f / longest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap

        val bytes = runCatching {
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 84, output))
                output.toByteArray()
            }
        }.getOrNull()

        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        if (bytes == null || bytes.isEmpty()) return

        synchronized(lock) {
            jpegBytes = bytes
            query = ""
            this.source = source
            createdAtMs = System.currentTimeMillis()
        }
    }

    fun updateQuery(value: String) {
        val clean = value.replace(Regex("\\s+"), " ").trim().take(420)
        synchronized(lock) {
            if (jpegBytes != null) query = clean
        }
    }

    fun snapshot(maxAgeMs: Long = 5 * 60_000L): Snapshot? = synchronized(lock) {
        val bytes = jpegBytes ?: return@synchronized null
        if (createdAtMs <= 0L || System.currentTimeMillis() - createdAtMs > maxAgeMs) {
            clearLocked()
            return@synchronized null
        }
        Snapshot(bytes.copyOf(), query, source, createdAtMs)
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        jpegBytes = null
        query = ""
        source = ""
        createdAtMs = 0L
    }
}
