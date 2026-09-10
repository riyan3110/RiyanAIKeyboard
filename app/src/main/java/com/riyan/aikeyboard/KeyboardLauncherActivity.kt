package com.riyan.aikeyboard

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * PRIVATE launcher surface: tapping the app icon opens the currently selected IME directly
 * instead of showing the legacy full settings page. Settings remain available from the
 * keyboard's own settings button.
 */
class KeyboardLauncherActivity : AppCompatActivity() {
    private lateinit var focusTarget: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        focusTarget = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            alpha = 0.01f
            isCursorVisible = false
            showSoftInputOnFocus = true
        }
        root.addView(
            focusTarget,
            FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        setContentView(root)
        showKeyboard()
    }

    override fun onResume() {
        super.onResume()
        showKeyboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showKeyboard()
    }

    private fun showKeyboard() {
        if (!::focusTarget.isInitialized) return
        focusTarget.post {
            focusTarget.requestFocus()
            focusTarget.setSelection(focusTarget.text.length)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
