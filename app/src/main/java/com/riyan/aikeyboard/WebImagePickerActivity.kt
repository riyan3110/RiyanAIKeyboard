package com.riyan.aikeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Bridges WebView image-file requests (Bing Visual Search) to Android's system photo picker.
 * It is deliberately not noHistory: it must stay alive until the picker returns its result.
 */
class WebImagePickerActivity : AppCompatActivity() {
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openPicker()
    }

    private fun openPicker() {
        val requested = pendingAcceptTypes
            .orEmpty()
            .map(String::trim)
            .firstOrNull { it.startsWith("image/", ignoreCase = true) }
            ?: "image/*"

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = requested
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_IMAGE) }
            .onFailure { finishWithResult(null) }
    }

    @Deprecated("Kept for broad WebView compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGE) return

        val uris = if (resultCode == RESULT_OK) {
            buildList {
                data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
                }
                data?.data?.let { if (it !in this) add(it) }
            }.takeIf { it.isNotEmpty() }?.toTypedArray()
        } else null

        uris?.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        finishWithResult(uris)
    }

    override fun onDestroy() {
        if (isFinishing && !resultDelivered) finishWithResult(null)
        super.onDestroy()
    }

    private fun finishWithResult(uris: Array<Uri>?) {
        if (resultDelivered) return
        resultDelivered = true
        val callback = pendingCallback
        pendingCallback = null
        pendingAcceptTypes = null
        callback?.onReceiveValue(uris)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 9231
        @Volatile private var pendingCallback: ValueCallback<Array<Uri>>? = null
        @Volatile private var pendingAcceptTypes: Array<String>? = null

        @Synchronized
        fun launch(
            context: Context,
            callback: ValueCallback<Array<Uri>>,
            acceptTypes: Array<String>?
        ): Boolean {
            pendingCallback?.onReceiveValue(null)
            pendingCallback = callback
            pendingAcceptTypes = acceptTypes
            return runCatching {
                context.startActivity(
                    Intent(context, WebImagePickerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
                true
            }.getOrElse {
                pendingCallback = null
                pendingAcceptTypes = null
                callback.onReceiveValue(null)
                false
            }
        }
    }
}
