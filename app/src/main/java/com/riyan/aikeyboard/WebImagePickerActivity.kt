package com.riyan.aikeyboard

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Bridges WebView image-file requests (Bing Visual Search) to the device image/document picker.
 * It is deliberately not noHistory: it must stay alive until the picker returns its result.
 */
class WebImagePickerActivity : Activity() {
    private var resultDelivered = false
    private var requestId = 0L
    private val pickerRequestCode: Int get() = 1000 + (requestId % 60000L).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        if (!hasPendingRequest(requestId)) {
            finish()
            return
        }
        if (savedInstanceState == null) openPicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nextId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        if (nextId != requestId && hasPendingRequest(nextId)) {
            requestId = nextId
            resultDelivered = false
            openPicker()
        }
    }

    private fun openPicker() {
        // Do not use ACTION_PICK_IMAGES here: some vendor Photo Pickers open successfully but
        // display an empty library. DocumentsUI can browse device Images, DCIM and Downloads.
        // Bing's accept list can begin with a single format; never filter out the other photos.
        val candidates = buildList {
            add(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildRootUri("com.android.providers.media.documents", "images"))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
            add(Intent(Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            add(Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }

        val launched = candidates.any { intent ->
            runCatching {
                startActivityForResult(intent, pickerRequestCode)
                true
            }.getOrDefault(false)
        }
        if (!launched) {
            Toast.makeText(this, "Pemilih foto perangkat tidak tersedia.", Toast.LENGTH_SHORT).show()
            finishWithResult(null)
        }
    }

    @Deprecated("Kept for broad WebView compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickerRequestCode) return

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
        val completion = takePendingRequest(requestId)
        val callback = completion.first
        val keyboardOwner = completion.second
        // A stale WebView callback must never prevent the bridge from finishing or the IME
        // from returning to the host app.
        runCatching { callback?.onReceiveValue(uris) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        keyboardOwner?.get()?.onBrowserImagePickerFinished()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "web_image_request_id"
        private var activeRequestId = 0L
        @Volatile private var pendingCallback: ValueCallback<Array<Uri>>? = null
        @Volatile private var keyboardOwner: WeakReference<RiyanKeyboardService>? = null
        @Volatile private var launchInFlight = false

        @Synchronized
        private fun hasPendingRequest(id: Long): Boolean =
            id == activeRequestId && launchInFlight && pendingCallback != null

        @Synchronized
        private fun takePendingRequest(id: Long = activeRequestId): Pair<ValueCallback<Array<Uri>>?, WeakReference<RiyanKeyboardService>?> {
            if (id != activeRequestId) return null to null
            val callback = pendingCallback
            val owner = keyboardOwner
            pendingCallback = null
            keyboardOwner = null
            launchInFlight = false
            return callback to owner
        }

        @Synchronized
        fun launch(
            context: Context,
            callback: ValueCallback<Array<Uri>>,
            @Suppress("UNUSED_PARAMETER") acceptTypes: Array<String>?
        ): Boolean {
            if (launchInFlight) {
                // Bing can emit the same chooser request more than once for one tap. Complete only
                // the duplicate callback and keep the first picker/callback alive.
                if (callback !== pendingCallback) runCatching { callback.onReceiveValue(null) }
                return true
            }
            launchInFlight = true
            activeRequestId++
            pendingCallback = callback
            keyboardOwner = (context as? RiyanKeyboardService)?.let(::WeakReference)
            keyboardOwner?.get()?.onBrowserImagePickerOpened()
            return runCatching {
                context.startActivity(
                    Intent(context, WebImagePickerActivity::class.java)
                        .putExtra(EXTRA_REQUEST_ID, activeRequestId)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                )
                true
            }.getOrElse {
                val completion = takePendingRequest()
                runCatching { completion.first?.onReceiveValue(null) }
                completion.second?.get()?.onBrowserImagePickerFinished()
                // The callback was consumed, so WebView must not dispatch it a second time.
                true
            }
        }

        @Synchronized
        fun cancelFor(context: Context) {
            if (keyboardOwner?.get() !== context) return
            val completion = takePendingRequest()
            runCatching { completion.first?.onReceiveValue(null) }
        }
    }
}
