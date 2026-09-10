package com.riyan.aikeyboard

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * PRIVATE launcher surface. Tapping the app icon opens the selected keyboard directly instead
 * of showing a separate app page. The full AI Ads Keyboard UI remains owned by the IME service.
 */
class KeyboardLauncherActivity : AppCompatActivity() {
    private lateinit var focusTarget: EditText
    private var pickerShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        focusTarget = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            alpha = 0.01f
            isCursorVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
            setSingleLine(false)
        }
        root.addView(
            focusTarget,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 2, Gravity.BOTTOM)
        )
        setContentView(root)
        requestKeyboard()
    }

    override fun onResume() {
        super.onResume()
        requestKeyboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestKeyboard()
    }

    private fun requestKeyboard() {
        if (!::focusTarget.isInitialized) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        // Android cannot silently switch the user's default IME. If AI Ads Keyboard is not the
        // current default, show the system keyboard chooser once instead of appearing to do nothing.
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        if (!defaultIme.startsWith("$packageName/")) {
            if (!pickerShown && window.decorView.hasWindowFocus()) {
                pickerShown = true
                imm.showInputMethodPicker()
            }
            return
        }

        focusTarget.post {
            focusTarget.requestFocus()
            focusTarget.setSelection(focusTarget.text.length)
            imm.restartInput(focusTarget)
            imm.showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT)
            WindowCompat.getInsetsController(window, focusTarget).show(WindowInsetsCompat.Type.ime())

            // A second request after the launcher window has fully settled makes icon launches
            // reliable on Android builds that ignore the first IME request during onResume.
            focusTarget.postDelayed({
                if (!isFinishing && focusTarget.hasFocus()) {
                    imm.showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT)
                    WindowCompat.getInsetsController(window, focusTarget).show(WindowInsetsCompat.Type.ime())
                }
            }, 180L)
        }
    }
}
