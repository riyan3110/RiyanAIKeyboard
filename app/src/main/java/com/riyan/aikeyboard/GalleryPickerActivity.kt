package com.riyan.aikeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity

/**
 * Opens the device Gallery / Photos app in real PICK mode and hands the selected
 * image URI back to the IME.
 *
 * Important: ACTION_PICK needs both the MediaStore collection URI and MIME type.
 * Calling setType() after setting data clears the data URI on Android, which can
 * make some OEM gallery apps (including Vivo/iQOO) open in normal browse mode
 * where tapping a thumbnail does not return a selection.
 */
class GalleryPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openGallery()
    }

    private fun openGallery() {
        val galleryIntent = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val intent = if (galleryIntent.resolveActivity(packageManager) != null) {
            galleryIntent
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(GALLERY_URI_KEY, uri.toString())
                    .putBoolean(GALLERY_READY_KEY, true)
                    .apply()
            }
        }
        finishWithoutAnimation()
    }

    override fun onBackPressed() {
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PICK_IMAGE_REQUEST = 702
        private const val PREFS = "riyan_ai"
        private const val GALLERY_READY_KEY = "camera_gallery_ready"
        private const val GALLERY_URI_KEY = "camera_gallery_uri"
    }
}
