from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
GALLERY = Path("app/src/main/java/com/riyan/aikeyboard/InternalGalleryPanel.kt")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, got {count}")
    return source.replace(old, new, 1)


source = SERVICE.read_text()

source = replace_once(
    source,
    "import android.view.MotionEvent\nimport android.view.View\n",
    "import android.view.MotionEvent\nimport android.view.ScaleGestureDetector\nimport android.view.View\n",
    "ScaleGestureDetector import",
)

source = replace_once(
    source,
    """    private var scannerGalleryUri: Uri? = null
    private var scannerGalleryPreviewBitmap: Bitmap? = null
    private var internalGalleryPanel: InternalGalleryPanel? = null
    private var scannerBestScore = 0
""",
    """    private var scannerGalleryUri: Uri? = null
    private var scannerGalleryPreviewBitmap: Bitmap? = null
    private var internalGalleryPanel: InternalGalleryPanel? = null
    private var scannerCameraZoomRatio = 1f
    private var scannerGalleryZoom = 1f
    private var scannerGalleryFocusX = 0.5f
    private var scannerGalleryFocusY = 0.5f
    private var scannerBestScore = 0
""",
    "scanner zoom state",
)

source = replace_once(
    source,
    """        scannerPreviewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    focusScannerAt(event.x, event.y)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                true
            }
        }
""",
    """        scannerPreviewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
            installScannerCameraGestures(this)
        }
""",
    "camera gesture installation",
)

source = replace_once(
    source,
    """        scannerGalleryImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
""",
    """        scannerGalleryImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            installScannerGalleryGestures(this)
        }
""",
    "gallery gesture installation",
)

source = replace_once(
    source,
    """                scannerCamera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                scannerStatusText?.text = "Aktif · ketuk objek untuk fokus"
                previewView.postDelayed({ focusScannerAt(previewView.width / 2f, previewView.height / 2f) }, 450L)
""",
    """                scannerCamera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                applyScannerCameraZoom(scannerCameraZoomRatio, showStatus = false)
                scannerStatusText?.text = if (scannerCameraZoomRatio > 1.05f) {
                    "Zoom %.1fx · cubit layar untuk atur zoom".format(scannerCameraZoomRatio)
                } else {
                    "Aktif · cubit untuk zoom, ketuk objek untuk fokus"
                }
                previewView.postDelayed({ focusScannerAt(previewView.width / 2f, previewView.height / 2f) }, 450L)
""",
    "restore camera zoom after bind",
)

focus_old = """    private fun focusScannerAt(x: Float, y: Float) {
        val preview = scannerPreviewView ?: return
        val camera = scannerCamera ?: return
        if (preview.width <= 0 || preview.height <= 0) return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        camera.cameraControl.startFocusAndMetering(action)
    }
"""
focus_new = focus_old + """

    private fun installScannerCameraGestures(view: PreviewView) {
        var downX = 0f
        var downY = 0f
        var lastTapAt = 0L
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = scannerCameraZoomRatio * detector.scaleFactor
                applyScannerCameraZoom(next, showStatus = true)
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!scaleDetector.isInProgress && moved <= dpFloat(12f)) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt <= 290L) {
                            val target = if (scannerCameraZoomRatio > 1.35f) 1f else 2.5f
                            applyScannerCameraZoom(target, showStatus = true)
                            lastTapAt = 0L
                        } else {
                            focusScannerAt(event.x, event.y)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            lastTapAt = now
                        }
                    }
                }
            }
            true
        }
    }

    private fun applyScannerCameraZoom(requested: Float, showStatus: Boolean) {
        val camera = scannerCamera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        val target = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        scannerCameraZoomRatio = target
        camera.cameraControl.setZoomRatio(target)
        if (showStatus) {
            scannerStatusText?.text = "Zoom %.1fx · cubit untuk dekat/jauh".format(target)
        }
    }

    private fun installScannerGalleryGestures(view: ImageView) {
        var lastTapAt = 0L
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (view.width > 0 && view.height > 0) {
                    scannerGalleryFocusX = (detector.focusX / view.width.toFloat()).coerceIn(0f, 1f)
                    scannerGalleryFocusY = (detector.focusY / view.height.toFloat()).coerceIn(0f, 1f)
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scannerGalleryZoom = (scannerGalleryZoom * detector.scaleFactor).coerceIn(1f, 6f)
                applyScannerGalleryZoom(view)
                scannerStatusText?.text = "Foto galeri · zoom %.1fx · tekan Cari untuk area yang terlihat".format(scannerGalleryZoom)
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapAt <= 290L) {
                    if (view.width > 0 && view.height > 0) {
                        scannerGalleryFocusX = (event.x / view.width.toFloat()).coerceIn(0f, 1f)
                        scannerGalleryFocusY = (event.y / view.height.toFloat()).coerceIn(0f, 1f)
                    }
                    scannerGalleryZoom = if (scannerGalleryZoom > 1.35f) 1f else 2.5f
                    applyScannerGalleryZoom(view)
                    scannerStatusText?.text = "Foto galeri · zoom %.1fx · tekan Cari untuk area yang terlihat".format(scannerGalleryZoom)
                    lastTapAt = 0L
                } else {
                    lastTapAt = now
                }
            }
            true
        }
    }

    private fun applyScannerGalleryZoom(view: ImageView) {
        val focusX = (scannerGalleryFocusX * view.width.toFloat()).coerceAtLeast(0f)
        val focusY = (scannerGalleryFocusY * view.height.toFloat()).coerceAtLeast(0f)
        view.pivotX = focusX
        view.pivotY = focusY
        view.scaleX = scannerGalleryZoom
        view.scaleY = scannerGalleryZoom
    }
"""
source = replace_once(source, focus_old, focus_new, "scanner gesture helpers")

source = replace_once(
    source,
    """    private fun showGalleryImage(uri: Uri) {
        scannerGalleryUri = uri
        scannerVisualSearchPreferred = true
""",
    """    private fun showGalleryImage(uri: Uri) {
        val isNewImage = scannerGalleryUri != uri
        scannerGalleryUri = uri
        if (isNewImage) {
            scannerGalleryZoom = 1f
            scannerGalleryFocusX = 0.5f
            scannerGalleryFocusY = 0.5f
        }
        scannerVisualSearchPreferred = true
""",
    "reset gallery zoom only for a new image",
)

source = replace_once(
    source,
    """                } else {
                    scannerGalleryImageView?.setImageBitmap(bitmap)
                }
""",
    """                } else {
                    scannerGalleryImageView?.setImageBitmap(bitmap)
                    scannerGalleryImageView?.let(::applyScannerGalleryZoom)
                }
""",
    "apply zoom after gallery bitmap decode",
)

source = replace_once(
    source,
    """    private fun clearScannerGalleryImage() {
        scannerGalleryUri = null
        scannerGalleryImageView?.setImageDrawable(null)
        scannerGalleryImageView = null
        scannerGalleryPreviewBitmap?.takeIf { !it.isRecycled }?.recycle()
        scannerGalleryPreviewBitmap = null
    }
""",
    """    private fun clearScannerGalleryImage() {
        scannerGalleryUri = null
        scannerGalleryImageView?.apply {
            scaleX = 1f
            scaleY = 1f
            pivotX = width / 2f
            pivotY = height / 2f
            setImageDrawable(null)
        }
        scannerGalleryImageView = null
        scannerGalleryPreviewBitmap?.takeIf { !it.isRecycled }?.recycle()
        scannerGalleryPreviewBitmap = null
        scannerGalleryZoom = 1f
        scannerGalleryFocusX = 0.5f
        scannerGalleryFocusY = 0.5f
    }
""",
    "clear gallery zoom state",
)

old_decode = """    private fun decodeGalleryBitmap(uri: Uri, maxDimension: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxDimension * 2) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null
        val decodedLongest = maxOf(decoded.width, decoded.height)
        if (decodedLongest <= maxDimension) return@runCatching decoded
        val scale = maxDimension.toFloat() / decodedLongest.toFloat()
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(decoded, width, height, true).also {
            if (it !== decoded && !decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()
"""
new_decode = """    private fun decodeGalleryBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modern = runCatching {
                val imageSource = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                    var sample = 1
                    while (longest / sample > maxDimension * 2) sample *= 2
                    decoder.setTargetSampleSize(sample.coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            if (modern != null) {
                val longest = maxOf(modern.width, modern.height)
                if (longest <= maxDimension) return modern
                val scale = maxDimension.toFloat() / longest.toFloat()
                val resized = Bitmap.createScaledBitmap(
                    modern,
                    (modern.width * scale).toInt().coerceAtLeast(1),
                    (modern.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (resized !== modern && !modern.isRecycled) modern.recycle()
                return resized
            }
        }

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDimension * 2) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null
            val decodedLongest = maxOf(decoded.width, decoded.height)
            if (decodedLongest <= maxDimension) return@runCatching decoded
            val scale = maxDimension.toFloat() / decodedLongest.toFloat()
            val width = (decoded.width * scale).toInt().coerceAtLeast(1)
            val height = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded && !decoded.isRecycled) decoded.recycle()
            }
        }.getOrNull()
    }

    private fun cropGalleryForCurrentZoom(source: Bitmap): Bitmap {
        val zoom = scannerGalleryZoom.coerceIn(1f, 6f)
        if (zoom <= 1.02f) return source
        val cropWidth = (source.width / zoom).toInt().coerceIn(1, source.width)
        val cropHeight = (source.height / zoom).toInt().coerceIn(1, source.height)
        val centerX = (source.width * scannerGalleryFocusX.coerceIn(0f, 1f)).toInt()
        val centerY = (source.height * scannerGalleryFocusY.coerceIn(0f, 1f)).toInt()
        val left = (centerX - cropWidth / 2).coerceIn(0, source.width - cropWidth)
        val top = (centerY - cropHeight / 2).coerceIn(0, source.height - cropHeight)
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }
"""
source = replace_once(source, old_decode, new_decode, "gallery decoder and zoom crop")

source = replace_once(
    source,
    """            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
""",
    """            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
""",
    "fit and zoom embedded visual-search web pages",
)

lens_anchor = """    private fun scaleBitmapForLens(source: Bitmap, maxDimension: Int = 1000): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
"""
lens_helpers = lens_anchor + r'''

    private fun uploadBitmapForVisualSearch(source: Bitmap, queryHint: String): String? {
        val prepared = scaleBitmapForLens(source, 1280)
        val jpeg = runCatching {
            val output = ByteArrayOutputStream()
            check(prepared.compress(Bitmap.CompressFormat.JPEG, 88, output))
            output.toByteArray()
        }.getOrNull()
        if (prepared !== source && !prepared.isRecycled) prepared.recycle()
        if (jpeg.isNullOrEmpty()) return null

        val timestamp = System.currentTimeMillis()
        val endpoints = listOf(
            "https://lens.google.com/v3/upload?ep=ccm&s=&st=$timestamp&hl=id" to true,
            "https://images.google.com/searchbyimage/upload" to false
        )
        for ((endpoint, includeDimensions) in endpoints) {
            val result = runCatching {
                uploadVisualSearchMultipart(endpoint, jpeg, source.width, source.height, includeDimensions)
            }.getOrNull()
            if (!result.isNullOrBlank()) return refineLensResultUrl(result, queryHint)
        }
        return null
    }

    private fun uploadVisualSearchMultipart(
        endpoint: String,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        includeDimensions: Boolean
    ): String? {
        val boundary = "----AIAdsKeyboard${UUID.randomUUID().toString().replace("-", "")}" 
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.6")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        java.io.DataOutputStream(connection.outputStream).use { body ->
            fun text(value: String) = body.write(value.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"encoded_image\"; filename=\"ai_ads_scan.jpg\"\r\n")
            text("Content-Type: image/jpeg\r\n\r\n")
            body.write(jpeg)
            text("\r\n")
            if (includeDimensions) {
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"processed_image_dimensions\"\r\n\r\n")
                text("$width,$height\r\n")
            } else {
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"image_content\"\r\n\r\n\r\n")
            }
            text("--$boundary--\r\n")
            body.flush()
        }

        val responseCode = connection.responseCode
        var location = connection.getHeaderField("Location").orEmpty().trim()
        if (location.isBlank()) {
            val stream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            val html = runCatching { stream?.bufferedReader()?.use { it.readText().take(160_000) } }.getOrNull().orEmpty()
            location = Regex("(?i)href=[\\\"']([^\\\"']+)").find(html)?.groupValues?.getOrNull(1).orEmpty()
                .replace("&amp;", "&")
        }
        connection.disconnect()

        if (location.startsWith("//")) location = "https:$location"
        if (location.startsWith("/")) {
            val origin = Uri.parse(endpoint)
            location = "${origin.scheme}://${origin.host}$location"
        }
        return location.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }
'''
source = replace_once(source, lens_anchor, lens_helpers, "actual image upload visual search")

source = replace_once(
    source,
    """                val result = if (encoded.isNullOrBlank()) {
                    Result.failure<AiResponse>(IllegalStateException("Foto kamera gagal disiapkan."))
                } else {
                    AiClient.visionProduct(aiSettings(), encoded, localHint)
                }
""",
    """                val visualUrl = uploadBitmapForVisualSearch(prepared, "")
                val result = if (encoded.isNullOrBlank()) {
                    Result.failure<AiResponse>(IllegalStateException("Foto kamera gagal disiapkan."))
                } else {
                    AiClient.visionProduct(aiSettings(), encoded, localHint)
                }
""",
    "camera real-image visual search upload",
)

source = replace_once(
    source,
    """                    val response = result.getOrNull()
                    val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                    if (query.isBlank()) {
""",
    """                    val response = result.getOrNull()
                    val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                    if (!visualUrl.isNullOrBlank()) {
                        scannerSelectedQuery = query
                        scannerSelectedUrl = visualUrl
                        scannerResultText?.text = query.ifBlank { "Pencarian visual dari foto kamera" }
                        scannerStatusText?.text = if (query.isBlank()) "Mencari kecocokan visual dari foto asli…" else "AI Vision: $query"
                        searchQuery = query
                        searchUrl = refineLensResultUrl(visualUrl, query)
                        stopEmbeddedScanner()
                        showSearchWebPanel()
                        scannerSearchButton?.text = "Cari"
                        scannerSearchButton?.isEnabled = true
                        return@post
                    }
                    if (query.isBlank()) {
""",
    "prefer actual camera image visual-search result",
)

source = replace_once(
    source,
    """            val frame = decodeGalleryBitmap(uri, 1400)
            if (frame == null || frame.width < 40 || frame.height < 40) {
""",
    """            val decodedFrame = decodeGalleryBitmap(uri, 1800)
            if (decodedFrame == null || decodedFrame.width < 40 || decodedFrame.height < 40) {
""",
    "gallery decode before zoom crop",
)

source = replace_once(
    source,
    """                return@thread
            }

            val prepared = scaleBitmapForAiVision(frame, 1024)
            val encoded = runCatching {
""",
    """                return@thread
            }

            val frame = cropGalleryForCurrentZoom(decodedFrame)
            if (frame !== decodedFrame && !decodedFrame.isRecycled) decodedFrame.recycle()
            val prepared = scaleBitmapForAiVision(frame, 1024)
            val encoded = runCatching {
""",
    "crop gallery search to the zoomed area",
)

source = replace_once(
    source,
    """            val result = if (encoded.isNullOrBlank()) {
                Result.failure<AiResponse>(IllegalStateException("Gambar galeri gagal disiapkan."))
            } else {
                AiClient.visionProduct(aiSettings(), encoded, "")
            }
""",
    """            val visualUrl = uploadBitmapForVisualSearch(prepared, "")
            val result = if (encoded.isNullOrBlank()) {
                Result.failure<AiResponse>(IllegalStateException("Gambar galeri gagal disiapkan."))
            } else {
                AiClient.visionProduct(aiSettings(), encoded, "")
            }
""",
    "gallery real-image visual search upload",
)

# The gallery handler has the same query prelude as the camera handler. Replace the second occurrence only.
gallery_handler_old = """                val response = result.getOrNull()
                val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                if (query.isBlank()) {
"""
gallery_handler_new = """                val response = result.getOrNull()
                val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                if (!visualUrl.isNullOrBlank()) {
                    scannerSelectedQuery = query
                    scannerSelectedUrl = visualUrl
                    scannerResultText?.text = query.ifBlank { "Pencarian visual dari foto galeri" }
                    scannerStatusText?.text = if (query.isBlank()) "Mencari kecocokan visual dari foto galeri…" else "AI Vision: $query"
                    searchQuery = query
                    searchUrl = refineLensResultUrl(visualUrl, query)
                    stopEmbeddedScanner()
                    showSearchWebPanel()
                    scannerSearchButton?.text = "Cari"
                    scannerSearchButton?.isEnabled = true
                    return@post
                }
                if (query.isBlank()) {
"""
# At this point the camera occurrence has already been replaced, so exactly one plain occurrence remains.
source = replace_once(source, gallery_handler_old, gallery_handler_new, "prefer actual gallery image visual-search result")

SERVICE.write_text(source)


gallery = GALLERY.read_text()
gallery = replace_once(
    gallery,
    "import java.util.concurrent.atomic.AtomicInteger\n",
    "import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicInteger\n",
    "gallery selection atomic import",
)
gallery = replace_once(
    gallery,
    """    private val generation = AtomicInteger(0)
    @Volatile private var released = false
""",
    """    private val generation = AtomicInteger(0)
    private val selectionGate = AtomicBoolean(false)
    @Volatile private var released = false
""",
    "gallery single-selection gate",
)
gallery = replace_once(
    gallery,
    """        released = false
        val token = generation.incrementAndGet()
""",
    """        released = false
        selectionGate.set(false)
        val token = generation.incrementAndGet()
""",
    "reset gallery selection gate",
)
gallery = replace_once(
    gallery,
    """                        setOnClickListener {
                            if (!released && token == generation.get()) onSelected(uri)
                        }
""",
    """                        setOnClickListener {
                            if (!released && token == generation.get() && selectionGate.compareAndSet(false, true)) {
                                isEnabled = false
                                alpha = 0.72f
                                onSelected(uri)
                            }
                        }
""",
    "single tap selects exactly one gallery photo",
)
GALLERY.write_text(gallery)

print("Applied scanner pinch zoom, zoom-aware gallery search, real-image visual search, and gallery selection fixes.")
