package com.riyan.aikeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity

/**
 * Tiny bridge between the IME and the phone's normal Gallery/Photos app.
 * The keyboard service owns the launch lock; this activity opens exactly one
 * external picker and reports the result/cancel back so the IME can reopen.
 */
class GalleryPickerActivity : AppCompatActivity() {
    private var pickerStarted = false
    private var sessionCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerStarted = savedInstanceState?.getBoolean(STATE_PICKER_STARTED, false) == true
        if (!pickerStarted) openGallery()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PICKER_STARTED, pickerStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // A duplicate launch must never open another Gallery page.
        // The existing picker session remains the only active one.
    }

    private fun openGallery() {
        if (pickerStarted) return
        pickerStarted = true

        val galleryIntent = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val pickerIntent = if (galleryIntent.resolveActivity(packageManager) != null) {
            galleryIntent
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(pickerIntent, PICK_IMAGE_REQUEST)
        }.onFailure {
            completeSession(null)
        }
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE_REQUEST) return

        val uri = if (resultCode == Activity.RESULT_OK) {
            data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            null
        }
        completeSession(uri)
    }

    override fun onBackPressed() {
        completeSession(null)
    }

    override fun onDestroy() {
        if (isFinishing && !sessionCompleted) completeSession(null, finishActivity = false)
        super.onDestroy()
    }

    private fun completeSession(uri: android.net.Uri?, finishActivity: Boolean = true) {
        if (sessionCompleted) {
            if (finishActivity && !isFinishing) finishWithoutAnimation()
            return
        }
        sessionCompleted = true

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val editor = prefs.edit()
            .putBoolean(GALLERY_SESSION_ACTIVE_KEY, false)
            .remove(GALLERY_SESSION_STARTED_KEY)

        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            editor
                .putString(GALLERY_URI_KEY, uri.toString())
                .putBoolean(GALLERY_READY_KEY, true)
        }
        editor.apply()

        // Explicit in-app broadcast: the IME clears its launch lock and asks Android
        // to show the keyboard again after this transparent bridge closes.
        sendBroadcast(
            Intent(GALLERY_RESULT_ACTION)
                .setPackage(packageName)
                .putExtra(EXTRA_HAS_IMAGE, uri != null)
        )

        if (finishActivity && !isFinishing) finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PICK_IMAGE_REQUEST = 702
        private const val STATE_PICKER_STARTED = "picker_started"
        private const val PREFS = "riyan_ai"
        private const val GALLERY_READY_KEY = "camera_gallery_ready"
        private const val GALLERY_URI_KEY = "camera_gallery_uri"
        private const val GALLERY_SESSION_ACTIVE_KEY = "camera_gallery_session_active"
        private const val GALLERY_SESSION_STARTED_KEY = "camera_gallery_session_started"
        private const val GALLERY_RESULT_ACTION = "com.riyan.aikeyboard.GALLERY_RESULT"
        private const val EXTRA_HAS_IMAGE = "has_image"
    }
}
