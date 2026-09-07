package com.riyan.aikeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

object ManualColorPickerDialog {
    fun show(anchor: View, title: String, initialColor: Int, onSelected: (Int) -> Unit) {
        val context = anchor.context
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var hue = hsv[0]
        var saturation = hsv[1]
        var value = hsv[2]

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 16))
            background = rounded(Color.rgb(31, 31, 33), 24f, Color.rgb(56, 56, 60), 1, context)
        }

        lateinit var popup: PopupWindow
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(context, 48), 1f))
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { popup.dismiss() }
        }, LinearLayout.LayoutParams(dp(context, 52), dp(context, 48)))
        root.addView(header)

        val hueBar = HueBarView(context).apply { currentHue = hue }
        root.addView(hueBar, LinearLayout.LayoutParams(-1, dp(context, 42)).apply {
            topMargin = dp(context, 12)
            bottomMargin = dp(context, 12)
        })

        val sv = SaturationValueView(context).apply {
            currentHue = hue
            currentSaturation = saturation
            currentValue = value
        }
        root.addView(sv, LinearLayout.LayoutParams(-1, dp(context, 220)))

        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 12), 0, dp(context, 8))
        }
        val swatch = View(context)
        previewRow.addView(swatch, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
            rightMargin = dp(context, 10)
        })
        val hexText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        previewRow.addView(hexText, LinearLayout.LayoutParams(0, dp(context, 40), 1f))
        root.addView(previewRow)

        fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        fun refreshPreview() {
            val color = currentColor()
            swatch.background = rounded(color, 8f, Color.WHITE, 1, context)
            hexText.text = String.format("#%06X", 0xFFFFFF and color)
        }
        refreshPreview()

        hueBar.onHueChanged = { selectedHue ->
            hue = selectedHue
            sv.currentHue = selectedHue
            sv.invalidate()
            refreshPreview()
        }
        sv.onChanged = { selectedSaturation, selectedValue ->
            saturation = selectedSaturation
            value = selectedValue
            refreshPreview()
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(TextView(context).apply {
            text = "Batal"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(55, 54, 60), 10f, Color.rgb(78, 76, 86), 1, context)
            setOnClickListener { popup.dismiss() }
        }, LinearLayout.LayoutParams(dp(context, 92), dp(context, 44)).apply { rightMargin = dp(context, 8) })
        actions.addView(TextView(context).apply {
            text = "Pilih Warna"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(91, 68, 230), 10f, Color.rgb(125, 105, 255), 1, context)
            setOnClickListener {
                val selected = currentColor()
                popup.dismiss()
                onSelected(selected)
            }
        }, LinearLayout.LayoutParams(dp(context, 130), dp(context, 44)))
        root.addView(actions)

        val screenWidth = anchor.resources.displayMetrics.widthPixels
        val popupWidth = minOf(screenWidth - dp(context, 28), dp(context, 430))
        popup = PopupWindow(root, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(context, 16).toFloat()
        }
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    private class HueBarView(context: Context) : View(context) {
        var currentHue = 0f
            set(value) { field = value.coerceIn(0f, 359.99f); invalidate() }
        var onHueChanged: ((Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 4).toFloat()
            color = Color.WHITE
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 7).toFloat()
            color = Color.BLACK
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
                null,
                Shader.TileMode.CLAMP
            )
            val cy = height / 2f
            val radius = height * 0.16f
            canvas.drawRoundRect(0f, cy - radius, width.toFloat(), cy + radius, radius, radius, paint)
            paint.shader = null
            val x = (currentHue / 360f) * width
            canvas.drawCircle(x, cy, height * 0.32f, shadow)
            canvas.drawCircle(x, cy, height * 0.32f, ring)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true
            val selected = ((event.x.coerceIn(0f, width.toFloat()) / width.coerceAtLeast(1)) * 359.99f)
            currentHue = selected
            onHueChanged?.invoke(selected)
            return true
        }
    }

    private class SaturationValueView(context: Context) : View(context) {
        var currentHue = 0f
        var currentSaturation = 1f
        var currentValue = 1f
        var onChanged: ((Float, Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 6).toFloat()
            color = Color.BLACK
        }
        private val markerInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 4).toFloat()
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val hueColor = Color.HSVToColor(floatArrayOf(currentHue, 1f, 1f))
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(context, 12).toFloat(), dp(context, 12).toFloat(), paint)
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(context, 12).toFloat(), dp(context, 12).toFloat(), paint)
            paint.shader = null
            val x = currentSaturation.coerceIn(0f, 1f) * width
            val y = (1f - currentValue.coerceIn(0f, 1f)) * height
            canvas.drawCircle(x, y, dp(context, 12).toFloat(), markerOuter)
            canvas.drawCircle(x, y, dp(context, 12).toFloat(), markerInner)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true
            currentSaturation = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            currentValue = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
            invalidate()
            onChanged?.invoke(currentSaturation, currentValue)
            return true
        }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Int, context: Context) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
        setStroke(dp(context, strokeDp), stroke)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
