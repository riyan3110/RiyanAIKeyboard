package com.riyan.aikeyboard

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class PublicKeyboardInputTest {
    private val events = mutableListOf<String>()
    private val view = View(RuntimeEnvironment.getApplication())
    private fun listener() = PublicKeyTouchListener(
        Handler(Looper.getMainLooper()), 300L, 20f,
        { events += if (it) "hold-feedback" else "feedback" },
        { }, { events += "preview" }, { }, { events += "symbol-preview" },
        { events += "a" }, { events += "@" }
    )
    private fun send(listener: PublicKeyTouchListener, action: Int, x: Float = 10f) {
        val e = MotionEvent.obtain(0, 0, action, x, 10f, 0)
        listener.onTouch(view, e)
        e.recycle()
    }
    @Test fun tapGivesFeedbackAndPreviewBeforeAnyText() {
        val l = listener()
        send(l, MotionEvent.ACTION_DOWN)
        assertEquals(listOf("feedback", "preview"), events)
        send(l, MotionEvent.ACTION_UP)
        assertEquals(listOf("feedback", "preview", "a"), events)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, events.count { it == "a" })
        assertFalse(events.contains("@"))
    }
    @Test fun holdSendsOnlySymbolWithoutTemporaryLetter() {
        val l = listener()
        send(l, MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        send(l, MotionEvent.ACTION_UP)
        assertEquals(listOf("feedback", "preview", "symbol-preview", "hold-feedback", "@"), events)
    }
    @Test fun cancelledOrDetachedPressNeverSendsText() {
        val l = listener()
        send(l, MotionEvent.ACTION_DOWN)
        send(l, MotionEvent.ACTION_CANCEL)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        send(l, MotionEvent.ACTION_UP)
        send(l, MotionEvent.ACTION_DOWN)
        l.onViewDetachedFromWindow(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(events.contains("a"))
        assertFalse(events.contains("@"))
    }
    @Test fun movingAwayCancelsEvenWhenFingerReturns() {
        val l = listener()
        send(l, MotionEvent.ACTION_DOWN)
        send(l, MotionEvent.ACTION_MOVE, 100f)
        send(l, MotionEvent.ACTION_UP)
        assertFalse(events.contains("a"))
        assertFalse(events.contains("@"))
    }
    @Test fun fastRepeatedTapsDoNotDropOrDuplicateCharacters() {
        val l = listener()
        repeat(10) { send(l, MotionEvent.ACTION_DOWN); send(l, MotionEvent.ACTION_UP) }
        assertEquals(10, events.count { it == "a" })
        assertEquals(10, events.count { it == "feedback" })
    }
    @Test fun gapsRouteToNearestCapWithoutChangingItsBoundsOrScale() {
        val context = RuntimeEnvironment.getApplication()
        val row = PublicKeyRow(context).apply { orientation = LinearLayout.HORIZONTAL }
        val a = View(context).apply { isClickable = true; scaleX = 0.7f; scaleY = 0.7f }
        val b = View(context).apply { isClickable = true }
        row.addView(a, LinearLayout.LayoutParams(80, 60).apply { setMargins(10, 10, 10, 10) })
        row.addView(b, LinearLayout.LayoutParams(80, 60).apply { setMargins(10, 10, 10, 10) })
        a.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) events += "a"; true }
        b.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) events += "b"; true }
        row.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, 200, 80)
        for (x in listOf(1f, 98f, 102f, 199f)) {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val e = MotionEvent.obtain(0, 0, action, x, 1f, 0)
                row.dispatchTouchEvent(e); e.recycle()
            }
        }
        assertEquals(listOf("a", "a", "b", "b"), events)
        assertEquals(10, a.left)
        assertEquals(80, a.width)
        assertEquals(0.7f, a.scaleX, 0f)
    }
    @Test fun emojiMatchesSentenceAndTrailingSpaceWithoutSubstringFalsePositive() {
        assertEquals(listOf("🔑", "🔧", "🔐"), PublicEmojiSuggestions.suggest("Aku mencari KUNCI "))
        assertEquals(listOf("🔑", "🔧", "🔐"), PublicEmojiSuggestions.suggest("kunci ada di meja"))
        assertTrue(PublicEmojiSuggestions.suggest("penguncian").isEmpty())
        assertTrue(PublicEmojiSuggestions.suggest("kunci. halo").isEmpty())
        assertTrue(PublicEmojiSuggestions.suggest("").isEmpty())
        assertEquals(listOf("🎂", "🎉", "🎁"), PublicEmojiSuggestions.suggest("selamat ulang tahun"))
        assertTrue(PublicEmojiSuggestions.suggest("aku minum kopi").contains("☕"))
    }
}
