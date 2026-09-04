package com.riyan.aikeyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import kotlin.math.max

data class KeyboardThemePalette(
    val background: Int,
    val key: Int,
    val specialKey: Int,
    val pressedKey: Int,
    val accent: Int,
    val text: Int,
    val usesPhoto: Boolean
)

object KeyboardTheme {
    const val MODE_DARK = "dark"
    const val MODE_PURPLE = "purple"
    const val MODE_BLUE = "blue"
    const val MODE_GREEN = "green"
    const val MODE_ROSE = "rose"
    const val MODE_CUSTOM = "custom"
    const val MODE_PHOTO = "photo"
    const val MODE_AMOLED = "amoled"

    val modes = listOf(
        MODE_DARK to "Gelap",
        MODE_PURPLE to "Ungu",
        MODE_BLUE to "Biru",
        MODE_GREEN to "Hijau",
        MODE_ROSE to "Merah muda",
        MODE_CUSTOM to "Warna sendiri",
        MODE_AMOLED to "Amoled Pitch Black",
        MODE_PHOTO to "Foto dari galeri"
    )

    private var cachedPhotoUri: String? = null
    private var cachedPhotoBitmap: Bitmap? = null

    fun palette(prefs: SharedPreferences): KeyboardThemePalette {
        val mode = prefs.getString("keyboard_theme_mode", MODE_DARK).orEmpty()
        val custom = parseColor(prefs.getString("keyboard_theme_color", "#5D4AC4"), Color.rgb(93, 74, 196))
        val accent = when (mode) {
            MODE_PURPLE -> Color.rgb(104, 75, 214)
            MODE_BLUE -> Color.rgb(25, 118, 210)
            MODE_GREEN -> Color.rgb(38, 137, 91)
            MODE_ROSE -> Color.rgb(194, 64, 115)
            MODE_CUSTOM -> custom
            else -> Color.rgb(89, 68, 196)
        }
        val usesPhoto = mode == MODE_PHOTO && !prefs.getString("keyboard_theme_image_uri", "").isNullOrBlank()
        if (usesPhoto) {
            return KeyboardThemePalette(
                background = Color.rgb(18, 18, 23),
                key = Color.argb(224, 45, 45, 54),
                specialKey = Color.argb(230, 35, 63, 75),
                pressedKey = Color.rgb(111, 83, 220),
                accent = Color.rgb(105, 76, 218),
                text = Color.WHITE,
                usesPhoto = true
            )
        }
        if (mode == MODE_AMOLED) {
            return KeyboardThemePalette(
                background = Color.BLACK,
                key = Color.rgb(17, 17, 20),
                specialKey = Color.rgb(21, 21, 25),
                pressedKey = Color.rgb(96, 67, 210),
                accent = Color.rgb(111, 79, 232),
                text = Color.WHITE,
                usesPhoto = false
            )
        }
        if (mode == MODE_DARK) {
            return KeyboardThemePalette(
                background = Color.rgb(18, 18, 23),
                key = Color.rgb(50, 50, 60),
                specialKey = Color.rgb(43, 68, 80),
                pressedKey = Color.rgb(93, 74, 196),
                accent = Color.rgb(89, 68, 196),
                text = Color.WHITE,
                usesPhoto = false
            )
        }
        return KeyboardThemePalette(
            background = blend(accent, Color.BLACK, 0.76f),
            key = blend(accent, Color.rgb(45, 45, 53), 0.48f),
            specialKey = blend(accent, Color.rgb(27, 55, 68), 0.42f),
            pressedKey = blend(accent, Color.WHITE, 0.10f),
            accent = accent,
            text = Color.WHITE,
            usesPhoto = false
        )
    }

    fun background(context: Context, prefs: SharedPreferences, palette: KeyboardThemePalette): Drawable {
        if (!palette.usesPhoto) return ColorDrawable(palette.background)
        val uri = prefs.getString("keyboard_theme_image_uri", "").orEmpty()
        val bitmap = loadThemeBitmap(context, uri) ?: return ColorDrawable(palette.background)
        val dim = prefs.getInt("keyboard_theme_photo_dim", 48).coerceIn(10, 85)
        return CenterCropPhotoDrawable(bitmap, Color.argb((255f * dim / 100f).toInt(), 8, 8, 13))
    }

    fun normalizeColor(value: String): String {
        val cleaned = value.trim().uppercase().let { if (it.startsWith("#")) it else "#$it" }
        return if (runCatching { Color.parseColor(cleaned) }.isSuccess) cleaned else "#5D4AC4"
    }

    private fun parseColor(value: String?, fallback: Int): Int =
        runCatching { Color.parseColor(normalizeColor(value.orEmpty())) }.getOrDefault(fallback)

    private fun loadThemeBitmap(context: Context, uriValue: String): Bitmap? {
        cachedPhotoBitmap?.takeIf { cachedPhotoUri == uriValue && !it.isRecycled }?.let { return it }
        val uri = Uri.parse(uriValue)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PHOTO_EDGE || bounds.outHeight / sampleSize > MAX_PHOTO_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null
        cachedPhotoUri = uriValue
        cachedPhotoBitmap = decoded
        return decoded
    }

    private fun blend(first: Int, second: Int, secondRatio: Float): Int {
        val ratio = secondRatio.coerceIn(0f, 1f)
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(first) * inverse + Color.red(second) * ratio).toInt(),
            (Color.green(first) * inverse + Color.green(second) * ratio).toInt(),
            (Color.blue(first) * inverse + Color.blue(second) * ratio).toInt()
        )
    }

    private const val MAX_PHOTO_EDGE = 1600
}

private class CenterCropPhotoDrawable(
    private val bitmap: Bitmap,
    private val overlayColor: Int
) : Drawable() {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlayColor }
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = max(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = bounds.left + (bounds.width() - width) / 2f
        val top = bounds.top + (bounds.height() - height) / 2f
        bitmapPaint.alpha = drawableAlpha
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), bitmapPaint)
        overlayPaint.alpha = (Color.alpha(overlayColor) * (drawableAlpha / 255f)).toInt()
        canvas.drawRect(RectF(bounds), overlayPaint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
