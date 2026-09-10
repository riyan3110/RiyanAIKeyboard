package com.riyan.aikeyboard

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
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
class PrivateShiftAiActionsTest {
    @Test fun shiftLetterShiftNeedsNoDelayAndSurvivesDelayedCursorUpdates() {
        val state = PrivateShiftState()
        repeat(5) {
            state.tap()
            assertTrue(state.uppercase)
            state.automatic(false) // delayed host callback from previous letter
            assertTrue(state.uppercase)
            state.letterCommitted()
            assertFalse(state.uppercase)
            state.automatic(true) // stale beginning-of-sentence callback
            assertFalse(state.uppercase)
        }
        assertFalse(state.locked)
    }

    @Test fun doubleTapNeverLocksAndHoldReleaseDoesNotUnlock() {
        val state = PrivateShiftState()
        val view = View(RuntimeEnvironment.getApplication())
        val listener = PrivateKeyTouchListener(Handler(Looper.getMainLooper()), 300L, 20f,
            {}, {}, {}, {}, {}, state::tap, state::hold)
        fun send(action: Int) {
            val event = MotionEvent.obtain(0, 0, action, 10f, 10f, 0)
            listener.onTouch(view, event)
            event.recycle()
        }
        repeat(2) { send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_UP) }
        assertFalse(state.locked)
        assertFalse(state.uppercase)
        send(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        send(MotionEvent.ACTION_UP)
        repeat(3) { state.letterCommitted(); state.automatic(false) }
        assertTrue(state.locked)
        assertTrue(state.uppercase)
        send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_UP)
        assertFalse(state.locked)
        assertFalse(state.uppercase)
    }

    @Test fun periodWithSpacesOrNewlineOverridesHostSentenceCapsFlag() {
        for (text in listOf("selesai.", "selesai. ", "selesai.  ", "selesai.\n")) {
            assertTrue(PrivateShiftState.afterPeriod(text))
        }
        assertFalse(PrivateShiftState.afterPeriod("selesai! "))
    }

    @Test fun generatedServiceKeepsManualShiftAndDoesNotCapitalizeAfterPeriod() {
        val service = Robolectric.buildService(RiyanKeyboardService::class.java).get()
        val input = EditText(RuntimeEnvironment.getApplication())
        ReflectionHelpers.setField(service, "aiInput", input)
        ReflectionHelpers.setField(service, "aiComposeActive", true)
        val state = ReflectionHelpers.getField<PrivateShiftState>(service, "privateShift")
        fun update() = ReflectionHelpers.callInstanceMethod<Unit>(service, "updateAutomaticShift")
        for (text in listOf("selesai.", "selesai. ", "123.")) {
            input.setText(text)
            input.setSelection(input.length())
            state.manual = false
            state.uppercase = true
            update()
            assertFalse(state.uppercase)
        }
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleShiftTap")
        update()
        assertTrue(state.uppercase)
        state.letterCommitted()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleShiftTap")
        update()
        assertTrue(state.uppercase)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleShiftHold")
        state.letterCommitted()
        update()
        assertTrue(state.locked)
        assertTrue(state.uppercase)
    }

    @Test fun answerCanBeChainedWhileNewDraftTakesPriority() {
        val state = PrivateAiTextState()
        assertEquals("", state.source("Ringkas", ""))
        state.finish(state.begin(), "Jawaban panjang")
        for (action in listOf("Ringkas", "Perbaiki", "Santai", "Inggris")) {
            assertEquals("Jawaban panjang", state.source(action, ""))
            assertEquals("Teks baru", state.source(action, " Teks baru "))
        }
        state.finish(state.begin(), "Ringkasan")
        assertEquals("Ringkasan", state.source("Inggris", ""))
        state.finish(state.begin(), "Summary")
        assertEquals("Summary", state.source("Santai", ""))
        // Existing reply and Indonesian screen translation do not change source behavior.
        assertEquals("", state.source("Balas", ""))
        assertEquals("", state.source("Terjemah", ""))
    }

    @Test fun failedRequestKeepsLastAnswerAndClearRejectsOldResponse() {
        val state = PrivateAiTextState()
        state.finish(state.begin(), "Jawaban")
        val failure = state.begin()
        assertTrue(state.busy)
        assertTrue(state.finish(failure, null))
        assertFalse(state.busy)
        assertEquals("Jawaban", state.answer)
        val old = state.begin()
        state.clear()
        val current = state.begin()
        assertFalse(state.finish(old, "Jawaban lama"))
        assertTrue(state.busy)
        assertEquals("", state.answer)
        assertTrue(state.finish(current, "Jawaban baru"))
        assertEquals("Jawaban baru", state.answer)
    }
}
