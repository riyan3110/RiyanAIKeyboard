package com.riyan.aikeyboard

import android.view.KeyEvent.*
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class CursorNavigationTest {
    @Test fun horizontalMovesOneCharacterAndDoesNotSplitSurrogatePairs() {
        val text = "A😀B"
        assertEquals(1, CursorNavigation.target(text, 0, KEYCODE_DPAD_RIGHT))
        assertEquals(3, CursorNavigation.target(text, 1, KEYCODE_DPAD_RIGHT))
        assertEquals(1, CursorNavigation.target(text, 3, KEYCODE_DPAD_LEFT))
        assertEquals(0, CursorNavigation.target(text, 0, KEYCODE_DPAD_LEFT))
        assertEquals(text.length, CursorNavigation.target(text, text.length, KEYCODE_DPAD_RIGHT))
    }
    @Test fun verticalSelectsSentencesInParagraphWithoutNewlines() {
        val text = "Kalimat satu. Kalimat dua! Kalimat tiga?"
        val second = text.indexOf("Kalimat dua")
        val third = text.indexOf("Kalimat tiga")
        assertEquals(second, CursorNavigation.target(text, 0, KEYCODE_DPAD_DOWN))
        assertEquals(third, CursorNavigation.target(text, second, KEYCODE_DPAD_DOWN))
        assertEquals(second, CursorNavigation.target(text, third, KEYCODE_DPAD_UP))
        assertEquals(0, CursorNavigation.target(text, second, KEYCODE_DPAD_UP))
    }
    @Test fun newlineWithoutPunctuationIsOneVerticalStep() {
        assertEquals(5, CursorNavigation.target("Satu\nDua\nTiga", 0, KEYCODE_DPAD_DOWN))
        assertEquals(5, CursorNavigation.target("Satu\nDua\nTiga", 9, KEYCODE_DPAD_UP))
    }
    @Test fun largeDiagonalMotionOnlyMovesOnceAndStationaryFingerDoesNotRepeat() {
        val drag = CursorDrag(24f)
        drag.start(0f, 0f)
        assertNull(drag.move(8f, 9f))
        assertEquals(KEYCODE_DPAD_RIGHT, drag.move(600f, 500f))
        assertNull(drag.move(600f, 500f))
        assertEquals(KEYCODE_DPAD_UP, drag.move(602f, 470f))
        assertEquals(KEYCODE_DPAD_LEFT, drag.move(570f, 470f))
    }
    @Test fun extractedWindowPreservesAbsoluteOffsetInLongDocuments() {
        val service = Robolectric.buildService(RiyanKeyboardService::class.java).get()
        val connection = object : android.view.inputmethod.BaseInputConnection(android.view.View(service), true) {
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int) =
                android.view.inputmethod.ExtractedText().apply {
                    text = "Kalimat satu. Kalimat dua."
                    startOffset = 8000
                    selectionStart = 3
                    selectionEnd = 7
                }
        }
        val read = RiyanKeyboardService::class.java.getDeclaredMethod("cursorTextWindow", android.view.inputmethod.InputConnection::class.java).apply { isAccessible = true }
        val window = read.invoke(service, connection)
        fun value(name: String) = window.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(window)
        assertEquals(8000, value("offset"))
        assertEquals(8003, value("start"))
        assertEquals(8007, value("end"))
    }
    @Test fun repeatedSelectionKeepsAnchorAndCanShrinkThenReverse() {
        val service = Robolectric.buildService(RiyanKeyboardService::class.java).get()
        val input = EditText(service).apply { setText("abcdef"); setSelection(2) }
        fun field(name: String, value: Any) {
            RiyanKeyboardService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        field("aiInput", input)
        field("aiComposeActive", true)
        val move = RiyanKeyboardService::class.java.getDeclaredMethod("extendCursorSelection", Int::class.javaPrimitiveType).apply { isAccessible = true }
        move.invoke(service, KEYCODE_DPAD_RIGHT)
        move.invoke(service, KEYCODE_DPAD_RIGHT)
        assertEquals(2, input.selectionStart)
        assertEquals(4, input.selectionEnd)
        repeat(3) { move.invoke(service, KEYCODE_DPAD_LEFT) }
        assertEquals(2, input.selectionStart)
        assertEquals(1, input.selectionEnd)
    }
}
