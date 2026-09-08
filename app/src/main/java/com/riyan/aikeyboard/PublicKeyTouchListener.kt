package com.riyan.aikeyboard

import android.os.Handler
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Feedback on contact; exactly one character OR alternate, never an undo/delete. */
internal class PublicKeyTouchListener(
    private val handler: Handler,
    private val holdMs: Long,
    private val tolerance: Float,
    private val feedback: (Boolean) -> Unit,
    private val pressed: (Boolean) -> Unit,
    private val preview: () -> Unit,
    private val hidePreview: () -> Unit,
    private val alternatePreview: () -> Unit,
    private val tap: () -> Unit,
    private val hold: (() -> Unit)?
) : View.OnTouchListener, View.OnAttachStateChangeListener {
    private var active = false
    private var consumed = false
    private var x = 0f
    private var y = 0f
    private val longPress = Runnable {
        if (active && !consumed) {
            consumed = true
            alternatePreview()
            feedback(true)
            hold?.invoke()
        }
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(longPress)
                active = true
                consumed = false
                x = event.x
                y = event.y
                feedback(false)
                pressed(true)
                preview()
                if (hold != null) handler.postDelayed(longPress, holdMs)
            }
            MotionEvent.ACTION_MOVE -> if (active && hypot(event.x - x, event.y - y) > tolerance) cancel()
            MotionEvent.ACTION_UP -> {
                val send = active && !consumed && hypot(event.x - x, event.y - y) <= tolerance
                cancel()
                if (send) tap()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            else -> return false
        }
        return true
    }

    private fun cancel() {
        handler.removeCallbacks(longPress)
        if (active) {
            active = false
            pressed(false)
            hidePreview()
        }
    }
    override fun onViewDetachedFromWindow(view: View) = cancel()
    override fun onViewAttachedToWindow(view: View) = Unit
}
