package com.riyan.aikeyboard

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("riyan_ai", MODE_PRIVATE) }
    private lateinit var settingsView: KeyboardSettingsOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(18, 17, 24)
        window.navigationBarColor = Color.rgb(18, 17, 24)

        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 17, 24))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        settingsView = KeyboardSettingsOverlay(
            context = this,
            prefs = prefs,
            onApply = {
                // Settings are persisted by KeyboardSettingsOverlay itself.
                // Keeping the Activity open makes it easy to continue editing other tabs.
            },
            onClose = { finish() },
            onInputFocusChanged = { }
        ).apply {
            visibility = android.view.View.VISIBLE
        }

        host.addView(
            settingsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(host)
        settingsView.show()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsView.isInitialized) settingsView.refreshExternalChanges()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
