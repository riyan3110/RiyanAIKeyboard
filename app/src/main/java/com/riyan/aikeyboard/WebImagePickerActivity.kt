package com.riyan.aikeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.inputmethodservice.InputMethodService
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference

/**
 * Bridges WebView image-file requests (Bing Visual Search) to Android's system photo picker.
 * It is deliberately not noHistory: it must stay alive until the picker returns its result.
 */
class WebImagePickerActivity : AppCompatActivity() {
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPendingRequest()) {
            finish()
            return
        }
        if (savedInstanceState == null) openPicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A duplicate Bing file-input event must reuse this bridge. Opening the system picker
        // again here is what previously produced stacked Gallery/Photos pages.
    }

    private fun openPicker() {
        val requested = pendingAcceptTypes
            .orEmpty()
            .map(String::trim)
            .firstOrNull { it.startsWith("image/", ignoreCase = true) }
            ?: "image/*"

        // Bing exposes this through an HTML file input. Open Android's real photo picker first so
        // the Gallery button goes straight to device photos instead of a generic Documents page.
        // Keep OPEN_DOCUMENT as a final fallback for older/vendor-modified Android builds.
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    type = requested
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                })
            }
            add(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = requested
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            })
            add(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = requested
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        }

        val launched = candidates.any { intent ->
            runCatching {
                startActivityForResult(intent, REQUEST_PICK_IMAGE)
                true
            }.getOrDefault(false)
        }
        if (!launched) finishWithResult(null)
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
            }.firstOrNull()?.let { arrayOf(it) }
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
        val completion = takePendingRequest()
        val callback = completion.first
        val keyboardOwner = completion.second
        callback?.onReceiveValue(uris)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        // The Android photo picker temporarily takes focus away from the host app. Ask this same
        // IME to return only after the selected URI has been delivered to Bing's WebView.
        Handler(Looper.getMainLooper()).postDelayed({
            keyboardOwner?.get()?.requestShowSelf(InputMethodManager.SHOW_IMPLICIT)
        }, KEYBOARD_RESTORE_DELAY_MS)
    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 9231
        private const val KEYBOARD_RESTORE_DELAY_MS = 220L
        @Volatile private var pendingCallback: ValueCallback<Array<Uri>>? = null
        @Volatile private var pendingAcceptTypes: Array<String>? = null
        @Volatile private var keyboardOwner: WeakReference<InputMethodService>? = null
        @Volatile private var launchInFlight = false

        @Synchronized
        private fun hasPendingRequest(): Boolean = launchInFlight && pendingCallback != null

        @Synchronized
        private fun takePendingRequest(): Pair<ValueCallback<Array<Uri>>?, WeakReference<InputMethodService>?> {
            val callback = pendingCallback
            val owner = keyboardOwner
            pendingCallback = null
            pendingAcceptTypes = null
            keyboardOwner = null
            launchInFlight = false
            return callback to owner
        }

        @Synchronized
        fun launch(
            context: Context,
            callback: ValueCallback<Array<Uri>>,
            acceptTypes: Array<String>?
        ): Boolean {
            if (launchInFlight) {
                // Bing can emit the same chooser request more than once for one tap. Complete only
                // the duplicate callback and keep the first picker/callback alive.
                callback.onReceiveValue(null)
                return true
            }
            launchInFlight = true
            pendingCallback = callback
            pendingAcceptTypes = acceptTypes
            keyboardOwner = (context as? InputMethodService)?.let(::WeakReference)
            return runCatching {
                context.startActivity(
                    Intent(context, WebImagePickerActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                )
                true
            }.getOrElse {
                takePendingRequest().first?.onReceiveValue(null)
                false
            }
        }
    }
}
