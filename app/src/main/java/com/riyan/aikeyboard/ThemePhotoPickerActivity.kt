package com.riyan.aikeyboard

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Tiny compatibility dispatcher for callers that still invoke the old picker entry point.
 *
 * It never opens Gallery and never owns an Activity Result. It only asks the already-open
 * SettingsActivity to launch Gallery itself, matching the old settings-page behavior exactly:
 * Settings -> Gallery -> the same Settings page.
 */
class ThemePhotoPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val alreadyPending = prefs.getBoolean(SettingsActivity.THEME_PICKER_REQUEST_KEY, false)
        val alreadyActive = prefs.getBoolean(SettingsActivity.THEME_PICKER_ACTIVE_KEY, false)

        if (!alreadyPending && !alreadyActive) {
            prefs.edit()
                .putBoolean(SettingsActivity.THEME_PICKER_REQUEST_KEY, true)
                .apply()
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PREFS = "riyan_ai"
    }
}
