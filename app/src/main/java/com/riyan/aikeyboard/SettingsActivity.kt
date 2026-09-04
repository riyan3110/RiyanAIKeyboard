package com.riyan.aikeyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("riyan_ai", MODE_PRIVATE) }
    private lateinit var settingsView: KeyboardSettingsOverlay

    override fun attachBaseContext(newBase: Context) {
        val compactConfig = Configuration(newBase.resources.configuration).apply {
            val sourceDensity = if (densityDpi > 0) {
                densityDpi
            } else {
                newBase.resources.displayMetrics.densityDpi
            }
            densityDpi = (sourceDensity * PAGE_UI_SCALE).roundToInt().coerceAtLeast(120)
            fontScale = minOf(fontScale, 1.0f)
        }
        super.attachBaseContext(newBase.createConfigurationContext(compactConfig))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(18, 17, 24)
        window.navigationBarColor = Color.rgb(18, 17, 24)

        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 17, 24))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        settingsView = KeyboardSettingsOverlay(
            context = this,
            prefs = prefs,
            onApply = {
                // Settings are persisted by KeyboardSettingsOverlay itself.
            },
            onClose = { closeSettingsTask() },
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

    @Deprecated("Deprecated in Android API; retained so Back matches the close button behavior")
    override fun onBackPressed() {
        closeSettingsTask()
    }

    private fun closeSettingsTask() {
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_UI_SCALE = 0.86f
    }
}
