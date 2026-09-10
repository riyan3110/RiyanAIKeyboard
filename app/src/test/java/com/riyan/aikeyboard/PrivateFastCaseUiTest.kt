package com.riyan.aikeyboard

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateFastCaseUiTest {
    @Test fun oneShotCapitalFallsBackInPlaceWithoutReplacingTouchableRows() {
        val controller = Robolectric.buildService(RiyanKeyboardService::class.java).create()
        val service = controller.get()
        service.onCreateInputView()
        val panel = ReflectionHelpers.getField<LinearLayout>(service, "keyboardPanel")
        val rows = (0 until panel.childCount).map(panel::getChildAt)
        assertTrue(rows.isNotEmpty())

        fun hasLegend(text: String): Boolean {
            fun walk(view: View): Boolean {
                if (view is TextView && view.text?.toString() == text) return true
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) if (walk(view.getChildAt(i))) return true
                }
                return false
            }
            return walk(panel)
        }

        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleShiftTap")
        assertTrue(hasLegend("Q"))
        rows.forEachIndexed { index, row -> assertSame(row, panel.getChildAt(index)) }

        val state = ReflectionHelpers.getField<PrivateShiftState>(service, "privateShift")
        state.letterCommitted()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshPrivateCaseLabels")
        assertTrue(hasLegend("q"))
        rows.forEachIndexed { index, row -> assertSame(row, panel.getChildAt(index)) }

        // Repeat without any artificial sleep: the next key row must remain immediately usable.
        repeat(8) {
            state.tap()
            ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshPrivateCaseLabels")
            state.letterCommitted()
            ReflectionHelpers.callInstanceMethod<Unit>(service, "refreshPrivateCaseLabels")
            rows.forEachIndexed { index, row -> assertSame(row, panel.getChildAt(index)) }
        }
        controller.destroy()
    }
}
