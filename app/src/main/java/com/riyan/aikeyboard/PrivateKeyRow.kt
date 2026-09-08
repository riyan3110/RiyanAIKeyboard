package com.riyan.aikeyboard

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs

/** Fill the invisible gaps with hit targets, leaving all cap geometry untouched. */
internal class PrivateKeyRow(context: Context) : LinearLayout(context) {
    private data class Contact(val key: View, val x: Float, val y: Float, val time: Long)
    private val contacts = mutableMapOf<Int, Contact>()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelContacts(event.eventTime)
                val x = event.getX(index)
                val key = (0 until childCount).map(::getChildAt)
                    .filter { it.isClickable && it.isEnabled && it.visibility == View.VISIBLE }
                    .minByOrNull { abs(x - (it.left + it.right) / 2f) }
                if (key != null && contacts.values.none { it.key === key }) {
                    val contact = Contact(key, x, event.getY(index), event.eventTime)
                    contacts[id] = contact
                    send(contact, MotionEvent.ACTION_DOWN, event.eventTime, x, event.getY(index))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    contacts[event.getPointerId(i)]?.let {
                        send(it, MotionEvent.ACTION_MOVE, event.eventTime, event.getX(i), event.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                contacts.remove(id)?.let {
                    send(it, MotionEvent.ACTION_UP, event.eventTime, event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelContacts(event.eventTime)
        }
        return true
    }

    private fun send(c: Contact, action: Int, time: Long, x: Float, y: Float) {
        // Start at the cap center even when contact began in its surrounding margin.
        // Preserve movement distance in screen pixels regardless of the visual scale.
        val translated = MotionEvent.obtain(c.time, time, action,
            c.key.width / 2f + x - c.x, c.key.height / 2f + y - c.y, 0)
        try { c.key.dispatchTouchEvent(translated) } finally { translated.recycle() }
    }
    private fun cancelContacts(time: Long) {
        val old = contacts.values.toList()
        contacts.clear()
        old.forEach { send(it, MotionEvent.ACTION_CANCEL, time, it.x, it.y) }
    }
    override fun onDetachedFromWindow() {
        cancelContacts(android.os.SystemClock.uptimeMillis())
        super.onDetachedFromWindow()
    }
}
