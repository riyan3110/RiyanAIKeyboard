package com.riyan.aikeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal bridge between the IME and the phone Gallery/Photos app.
 *
 * This flow is intentionally self-contained:
 * - the IME opens this bridge once
 * - this bridge launches exactly one external image picker
 * - the selected Uri is stored for the IME
 * - the bridge finishes and Android returns to the previous app
 *
 * No broadcast, forced keyboard reopening, build-time patch, or lifecycle lock is used.
 */
class GalleryPickerActivity : AppCompatActivity() {
    private var ownsPickerSession = false
    private var pickerLaunched = false
    private var resultHandled = false

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (resultHandled) return@registerForActivityResult
        resultHandled = true

        val uri = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data
                ?: result.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            null
        }

        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(GALLERY_URI_KEY, uri.toString())
                .putBoolean(GALLERY_READY_KEY, true)
                .apply()
        }

        releaseSessionAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val restoredOwner = savedInstanceState?.getBoolean(STATE_OWNS_SESSION, false) == true
        pickerLaunched = savedInstanceState?.getBoolean(STATE_PICKER_LAUNCHED, false) == true

        if (restoredOwner) {
            ownsPickerSession = true
            pickerSessionActive.set(true)
            pickerSessionStartedAt = SystemClock.elapsedRealtime()
        } else if (!acquirePickerSession()) {
            finishWithoutAnimation()
            return
        }

        if (!pickerLaunched) {
            pickerLaunched = true
            runCatching {
                pickerLauncher.launch(createPickerIntent())
            }.onFailure {
                releaseSessionAndFinish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_OWNS_SESSION, ownsPickerSession)
        outState.putBoolean(STATE_PICKER_LAUNCHED, pickerLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun createPickerIntent(): Intent {
        val gallery = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (gallery.resolveActivity(packageManager) != null) return gallery

        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    private fun acquirePickerSession(): Boolean = synchronized(pickerSessionActive) {
        val now = SystemClock.elapsedRealtime()
        if (pickerSessionActive.get() && now - pickerSessionStartedAt <= STALE_SESSION_MS) {
            return@synchronized false
        }

        pickerSessionActive.set(true)
        pickerSessionStartedAt = now
        ownsPickerSession = true
        true
    }

    private fun releaseSessionAndFinish() {
        if (ownsPickerSession) {
            ownsPickerSession = false
            pickerSessionActive.set(false)
            pickerSessionStartedAt = 0L
        }
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        if (!isFinishing) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private val pickerSessionActive = AtomicBoolean(false)
        @Volatile private var pickerSessionStartedAt = 0L

        // Only protects the initial duplicate-launch storm. A stale session must never
        // leave the Gallery button permanently locked if Android destroys the bridge.
        private const val STALE_SESSION_MS = 5_000L

        private const val STATE_OWNS_SESSION = "gallery_owns_session"
        private const val STATE_PICKER_LAUNCHED = "gallery_picker_launched"
        private const val PREFS = "riyan_ai"
        private const val GALLERY_READY_KEY = "camera_gallery_ready"
        private const val GALLERY_URI_KEY = "camera_gallery_uri"
    }
}
