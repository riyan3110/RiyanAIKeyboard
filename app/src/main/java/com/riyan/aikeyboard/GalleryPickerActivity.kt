package com.riyan.aikeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * One-shot bridge between the IME and the phone Gallery/Photos app.
 *
 * The important rule here is that the "picker already open" state is written
 * synchronously to disk BEFORE Android leaves this app. That state survives an
 * IME/app process restart, which can happen on aggressive Android/OEM builds
 * while the external Gallery is in front.
 *
 * This replaces both the old build-time lifecycle patch and the process-only
 * AtomicBoolean guard. No broadcast or forced IME reopening is used.
 */
class GalleryPickerActivity : AppCompatActivity() {
    private var ownsSession = false
    private var pickerLaunched = false
    private var resultHandled = false
    private var leftForPicker = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val missingResultFallback = Runnable {
        if (ownsSession && pickerLaunched && leftForPicker && !resultHandled && !isFinishing) {
            resultHandled = true
            clearSession()
            finishWithoutAnimation()
        }
    }

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (resultHandled) return@registerForActivityResult
        resultHandled = true
        mainHandler.removeCallbacks(missingResultFallback)

        val uri = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data
                ?: result.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            null
        }

        completeSession(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickerLaunched = savedInstanceState?.getBoolean(STATE_PICKER_LAUNCHED, false) == true
        leftForPicker = savedInstanceState?.getBoolean(STATE_LEFT_FOR_PICKER, false) == true
        val restoredOwner = savedInstanceState?.getBoolean(STATE_OWNS_SESSION, false) == true

        if (restoredOwner) {
            ownsSession = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(SESSION_ACTIVE_KEY, true)
                .commit()
        } else if (!acquireFreshSession()) {
            finishWithoutAnimation()
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val launchAlreadyCommitted = prefs.getBoolean(PICKER_LAUNCHED_KEY, false)

        if (!pickerLaunched && !launchAlreadyCommitted) {
            if (!prefs.edit().putBoolean(PICKER_LAUNCHED_KEY, true).commit()) {
                resultHandled = true
                clearSession()
                finishWithoutAnimation()
                return
            }

            pickerLaunched = true
            runCatching {
                pickerLauncher.launch(createPickerIntent())
            }.onFailure {
                if (!resultHandled) {
                    resultHandled = true
                    clearSession()
                    finishWithoutAnimation()
                }
            }
        } else {
            pickerLaunched = true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_OWNS_SESSION, ownsSession)
        outState.putBoolean(STATE_PICKER_LAUNCHED, pickerLaunched)
        outState.putBoolean(STATE_LEFT_FOR_PICKER, leftForPicker)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (ownsSession && pickerLaunched && !resultHandled) {
            leftForPicker = true
        }
        mainHandler.removeCallbacks(missingResultFallback)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (ownsSession && pickerLaunched && leftForPicker && !resultHandled) {
            mainHandler.removeCallbacks(missingResultFallback)
            mainHandler.postDelayed(missingResultFallback, RETURN_FALLBACK_DELAY_MS)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(missingResultFallback)
        super.onDestroy()
    }

    private fun acquireFreshSession(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val active = prefs.getBoolean(SESSION_ACTIVE_KEY, false)
        val startedAt = prefs.getLong(SESSION_STARTED_KEY, 0L)
        val age = now - startedAt

        if (active && age in 0..SESSION_STALE_MS) return false

        val committed = prefs.edit()
            .putBoolean(SESSION_ACTIVE_KEY, true)
            .putLong(SESSION_STARTED_KEY, now)
            .putBoolean(PICKER_LAUNCHED_KEY, false)
            .commit()
        if (!committed) return false

        ownsSession = true
        return true
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

    private fun completeSession(uri: android.net.Uri?) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val editor = prefs.edit()

        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            editor
                .putString(GALLERY_URI_KEY, uri.toString())
                .putBoolean(GALLERY_READY_KEY, true)
        }

        editor
            .putBoolean(SESSION_ACTIVE_KEY, false)
            .remove(SESSION_STARTED_KEY)
            .putBoolean(PICKER_LAUNCHED_KEY, false)
            .commit()

        ownsSession = false
        finishWithoutAnimation()
    }

    private fun clearSession() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(SESSION_ACTIVE_KEY, false)
            .remove(SESSION_STARTED_KEY)
            .putBoolean(PICKER_LAUNCHED_KEY, false)
            .commit()
        ownsSession = false
    }

    private fun finishWithoutAnimation() {
        if (!isFinishing) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val SESSION_STALE_MS = 60_000L
        private const val RETURN_FALLBACK_DELAY_MS = 650L

        private const val STATE_OWNS_SESSION = "gallery_owns_session"
        private const val STATE_PICKER_LAUNCHED = "gallery_picker_launched_state"
        private const val STATE_LEFT_FOR_PICKER = "gallery_left_for_picker"

        private const val PREFS = "riyan_ai"
        private const val GALLERY_READY_KEY = "camera_gallery_ready"
        private const val GALLERY_URI_KEY = "camera_gallery_uri"
        private const val SESSION_ACTIVE_KEY = "gallery_durable_session_active"
        private const val SESSION_STARTED_KEY = "gallery_durable_session_started"
        private const val PICKER_LAUNCHED_KEY = "gallery_durable_picker_launched"
    }
}
