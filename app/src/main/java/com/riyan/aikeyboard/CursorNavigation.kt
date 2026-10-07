package com.riyan.aikeyboard

import android.view.KeyEvent
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

internal object CursorNavigation {
    fun target(text: String, position: Int, keyCode: Int): Int {
        val cursor = position.coerceIn(0, text.length)
        val horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        val iterator = if (horizontal) BreakIterator.getCharacterInstance(Locale.getDefault())
            else BreakIterator.getSentenceInstance(Locale("id", "ID"))
        iterator.setText(text)
        val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val boundary = if (forward) iterator.following(cursor) else iterator.preceding(cursor)
        var result = if (boundary == BreakIterator.DONE) { if (forward) text.length else 0 } else boundary
        // A line break also ends a sentence, even without punctuation.
        if (!horizontal) {
            if (forward) {
                val newline = text.indexOf('\n', cursor)
                if (newline >= 0) result = minOf(result, newline + 1)
            } else if (cursor > 0) {
                val newline = text.lastIndexOf('\n', cursor - 2)
                if (newline >= 0) result = maxOf(result, newline + 1)
            }
        }
        return result
    }
}

/** One dominant-axis step per motion event; discard excess to avoid catch-up jumps. */
internal class CursorDrag(private val threshold: Float) {
    private var x = 0f
    private var y = 0f
    fun start(rawX: Float, rawY: Float) { x = rawX; y = rawY }
    fun move(rawX: Float, rawY: Float): Int? {
        val dx = rawX - x
        val dy = rawY - y
        if (maxOf(abs(dx), abs(dy)) < threshold) return null
        x = rawX
        y = rawY
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        } else {
            if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        }
    }
}
