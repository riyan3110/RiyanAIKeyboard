package com.riyan.aikeyboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to Android's normal document/gallery picker for the keyboard theme.
 *
 * The previous implementation was declared noHistory in the manifest. That is
 * incompatible with hosting Activity Result: Android may destroy the bridge as
 * soon as the external picker opens, which can lose the result and leave
 * multiple picker pages/tasks stacked. Keep exactly one live picker session and
 * only finish this bridge after the user selects a photo or cancels.
 */
class ThemePhotoPickerActivity : AppCompatActivity() {
    private var ownsPickerSession = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(THEME_IMAGE_URI, uri.toString())
                .putString(THEME_MODE, KeyboardTheme.MODE_PHOTO)
                .apply()
            Toast.makeText(this, "Foto tema tersimpan.", Toast.LENGTH_SHORT).show()
        }
        finishSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A fast double tap or duplicate intent must never create another picker.
        if (!pickerOpen.compareAndSet(false, true)) {
            finishQuietly()
            return
        }
        ownsPickerSession = true

        // registerForActivityResult restores an in-flight request after recreation.
        // Launch only for the first creation so configuration/lifecycle recreation
        // cannot open another Gallery page.
        if (savedInstanceState == null) {
            picker.launch(arrayOf("image/*"))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTop duplicate launch: keep the current picker, do not launch again.
    }

    @Deprecated("Deprecated in Android API; retained for consistent cancel behavior")
    override fun onBackPressed() {
        finishSession()
    }

    override fun onDestroy() {
        if (isFinishing) releaseSession()
        super.onDestroy()
    }

    private fun finishSession() {
        releaseSession()
        finishQuietly()
    }

    private fun releaseSession() {
        if (!ownsPickerSession) return
        ownsPickerSession = false
        pickerOpen.set(false)
    }

    private fun finishQuietly() {
        if (!isFinishing) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private val pickerOpen = AtomicBoolean(false)
        private const val PREFS = "riyan_ai"
        private const val THEME_IMAGE_URI = "keyboard_theme_image_uri"
        private const val THEME_MODE = "keyboard_theme_mode"
    }
}
