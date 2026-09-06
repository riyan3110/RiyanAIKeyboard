from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
service_path = root / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
vision_path = root / "app/src/main/java/com/riyan/aikeyboard/AiHordeAlchemyVision.kt"
client_path = root / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new, 1)


def regex_replace_once(text: str, pattern: str, new: str, label: str) -> str:
    updated, count = re.subn(pattern, new, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"Regex patch target not found: {label} (count={count})")
    return updated


# ---------------------------------------------------------------------------
# Camera + gallery zoom/pan
# ---------------------------------------------------------------------------
service = service_path.read_text()

service = replace_once(
    service,
    """    private var scannerCameraZoomRatio = 1f\n    private var scannerGalleryZoom = 1f\n""",
    """    private var scannerCameraZoomRatio = 1f\n    private var scannerCameraPanX = 0f\n    private var scannerCameraPanY = 0f\n    private var scannerGalleryZoom = 1f\n    private var scannerGalleryPanX = 0f\n    private var scannerGalleryPanY = 0f\n""",
    "scanner pan state",
)

service = replace_once(
    service,
    """        scannerGalleryImageView = ImageView(this).apply {\n            scaleType = ImageView.ScaleType.CENTER_CROP\n""",
    """        scannerGalleryImageView = ImageView(this).apply {\n            // FIT_CENTER is the neutral 1x state: the complete gallery photo is visible.\n            // Users can zoom out below 1x for extra breathing room, zoom in, then drag to pan.\n            scaleType = ImageView.ScaleType.FIT_CENTER\n""",
    "gallery scale type",
)

camera_methods = r'''    private fun installScannerCameraGestures(view: PreviewView) {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragged = false
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
                    lastX = event.x
                    lastY = event.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress && scannerCameraZoomRatio > 1.03f) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > dpFloat(1f) || abs(dy) > dpFloat(1f)) {
                            // Dragging the picture left reveals the right side, just like a normal
                            // zoomable photo. Pan is normalized so Camera2 can move the real crop,
                            // not merely translate the preview overlay.
                            scannerCameraPanX = (scannerCameraPanX - (dx / view.width.coerceAtLeast(1)) * 2f)
                                .coerceIn(-1f, 1f)
                            scannerCameraPanY = (scannerCameraPanY - (dy / view.height.coerceAtLeast(1)) * 2f)
                                .coerceIn(-1f, 1f)
                            applyScannerCameraZoom(scannerCameraZoomRatio, showStatus = false)
                            scannerStatusText?.text = "Zoom %.1fx · geser untuk pindah area".format(scannerCameraZoomRatio)
                            dragged = true
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!scaleDetector.isInProgress && !dragged && moved <= dpFloat(12f)) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt <= 290L) {
                            val target = if (scannerCameraZoomRatio > 1.35f) 1f else 2.5f
                            if (target <= 1.01f) {
                                scannerCameraPanX = 0f
                                scannerCameraPanY = 0f
                            }
                            applyScannerCameraZoom(target, showStatus = true)
                            lastTapAt = 0L
                        } else {
                            focusScannerAt(event.x, event.y)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            lastTapAt = now
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragged = false
            }
            true
        }
    }

    private fun applyScannerCameraZoom(requested: Float, showStatus: Boolean) {
        val camera = scannerCamera ?: return
        val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
        val activeArray = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
        ) ?: return
        val maxDigitalZoom = (camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
        ) ?: 1f).coerceIn(1f, 12f)

        val target = requested.coerceIn(1f, maxDigitalZoom)
        scannerCameraZoomRatio = target
        if (target <= 1.01f) {
            scannerCameraPanX = 0f
            scannerCameraPanY = 0f
        }

        val cropWidth = (activeArray.width() / target).toInt().coerceIn(2, activeArray.width())
        val cropHeight = (activeArray.height() / target).toInt().coerceIn(2, activeArray.height())
        val maxShiftX = ((activeArray.width() - cropWidth) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((activeArray.height() - cropHeight) / 2f).coerceAtLeast(0f)

        // scannerCameraPanX/Y are expressed in display coordinates. Camera2 crop coordinates are
        // sensor coordinates, so rotate the pan vector back into sensor space.
        val displayRotation = scannerPreviewView?.display?.rotation ?: android.view.Surface.ROTATION_0
        val sensorRotation = camera.cameraInfo.getSensorRotationDegrees(displayRotation)
        val displayPanX = scannerCameraPanX.coerceIn(-1f, 1f)
        val displayPanY = scannerCameraPanY.coerceIn(-1f, 1f)
        val sensorPan = when (sensorRotation) {
            90 -> displayPanY to -displayPanX
            180 -> -displayPanX to -displayPanY
            270 -> -displayPanY to displayPanX
            else -> displayPanX to displayPanY
        }

        val centerX = (activeArray.centerX() + sensorPan.first * maxShiftX)
            .toInt().coerceIn(activeArray.left + cropWidth / 2, activeArray.right - cropWidth / 2)
        val centerY = (activeArray.centerY() + sensorPan.second * maxShiftY)
            .toInt().coerceIn(activeArray.top + cropHeight / 2, activeArray.bottom - cropHeight / 2)
        val left = (centerX - cropWidth / 2).coerceIn(activeArray.left, activeArray.right - cropWidth)
        val top = (centerY - cropHeight / 2).coerceIn(activeArray.top, activeArray.bottom - cropHeight)
        val crop = Rect(left, top, left + cropWidth, top + cropHeight)

        val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, crop)
            .build()
        androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
            .setCaptureRequestOptions(options)

        if (showStatus) {
            scannerStatusText?.text = if (target > 1.03f) {
                "Zoom %.1fx · geser untuk pindah area".format(target)
            } else {
                "Zoom 1.0x · cubit untuk dekat/jauh"
            }
        }
    }

'''
service = regex_replace_once(
    service,
    r"    private fun installScannerCameraGestures\(view: PreviewView\) \{.*?(?=    private fun installScannerGalleryGestures\(view: ImageView\))",
    camera_methods,
    "camera gesture and zoom methods",
)

# Camera2 crop control is an experimental CameraX interop API in 1.4.x. Opt in at class scope.
service = replace_once(
    service,
    "class RiyanKeyboardService : InputMethodService() {",
    "@OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)\nclass RiyanKeyboardService : InputMethodService() {",
    "camera2 interop opt-in",
)

gallery_methods = r'''    private fun installScannerGalleryGestures(view: ImageView) {
        var lastTapAt = 0L
        var lastX = 0f
        var lastY = 0f
        var dragged = false
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = scannerGalleryZoom.coerceAtLeast(0.01f)
                val newZoom = (oldZoom * detector.scaleFactor).coerceIn(0.55f, 6f)
                if (view.width > 0 && view.height > 0 && oldZoom != newZoom) {
                    val centerX = view.width / 2f
                    val centerY = view.height / 2f
                    val contentX = (detector.focusX - centerX - scannerGalleryPanX) / oldZoom
                    val contentY = (detector.focusY - centerY - scannerGalleryPanY) / oldZoom
                    scannerGalleryPanX = detector.focusX - centerX - contentX * newZoom
                    scannerGalleryPanY = detector.focusY - centerY - contentY * newZoom
                }
                scannerGalleryZoom = newZoom
                applyScannerGalleryZoom(view)
                scannerStatusText?.text = "Foto galeri · zoom %.2fx · geser untuk pindah area".format(scannerGalleryZoom)
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > dpFloat(1f) || abs(dy) > dpFloat(1f)) {
                            scannerGalleryPanX += dx
                            scannerGalleryPanY += dy
                            applyScannerGalleryZoom(view)
                            dragged = true
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!scaleDetector.isInProgress && !dragged) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt <= 290L) {
                            val oldZoom = scannerGalleryZoom.coerceAtLeast(0.01f)
                            val newZoom = if (oldZoom > 1.35f) 1f else 2.5f
                            val centerX = view.width / 2f
                            val centerY = view.height / 2f
                            val contentX = (event.x - centerX - scannerGalleryPanX) / oldZoom
                            val contentY = (event.y - centerY - scannerGalleryPanY) / oldZoom
                            scannerGalleryPanX = event.x - centerX - contentX * newZoom
                            scannerGalleryPanY = event.y - centerY - contentY * newZoom
                            scannerGalleryZoom = newZoom
                            applyScannerGalleryZoom(view)
                            scannerStatusText?.text = "Foto galeri · zoom %.2fx · geser untuk pindah area".format(scannerGalleryZoom)
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragged = false
            }
            true
        }
    }

    private fun applyScannerGalleryZoom(view: ImageView) {
        val bitmap = scannerGalleryPreviewBitmap
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        if (bitmap == null || bitmap.isRecycled || view.width <= 1 || view.height <= 1) {
            view.scaleX = scannerGalleryZoom
            view.scaleY = scannerGalleryZoom
            view.translationX = scannerGalleryPanX
            view.translationY = scannerGalleryPanY
            return
        }

        val fitScale = minOf(
            view.width.toFloat() / bitmap.width.coerceAtLeast(1),
            view.height.toFloat() / bitmap.height.coerceAtLeast(1)
        )
        val displayedWidth = bitmap.width * fitScale * scannerGalleryZoom
        val displayedHeight = bitmap.height * fitScale * scannerGalleryZoom
        val maxPanX = ((displayedWidth - view.width) / 2f).coerceAtLeast(0f)
        val maxPanY = ((displayedHeight - view.height) / 2f).coerceAtLeast(0f)
        scannerGalleryPanX = scannerGalleryPanX.coerceIn(-maxPanX, maxPanX)
        scannerGalleryPanY = scannerGalleryPanY.coerceIn(-maxPanY, maxPanY)

        view.scaleX = scannerGalleryZoom
        view.scaleY = scannerGalleryZoom
        view.translationX = scannerGalleryPanX
        view.translationY = scannerGalleryPanY
    }

'''
service = regex_replace_once(
    service,
    r"    private fun installScannerGalleryGestures\(view: ImageView\) \{.*?(?=    private fun showInternalGalleryPanel\(\))",
    gallery_methods,
    "gallery gesture methods",
)

# Reset pan whenever a new gallery photo is selected/reset.
old_reset = """                scannerGalleryZoom = 1f\n                scannerGalleryFocusX = 0.5f\n                scannerGalleryFocusY = 0.5f\n"""
new_reset = """                scannerGalleryZoom = 1f\n                scannerGalleryFocusX = 0.5f\n                scannerGalleryFocusY = 0.5f\n                scannerGalleryPanX = 0f\n                scannerGalleryPanY = 0f\n"""
service = service.replace(old_reset, new_reset)

old_reset_8 = """            scannerGalleryZoom = 1f\n            scannerGalleryFocusX = 0.5f\n            scannerGalleryFocusY = 0.5f\n"""
new_reset_8 = """            scannerGalleryZoom = 1f\n            scannerGalleryFocusX = 0.5f\n            scannerGalleryFocusY = 0.5f\n            scannerGalleryPanX = 0f\n            scannerGalleryPanY = 0f\n"""
service = service.replace(old_reset_8, new_reset_8)

service = replace_once(
    service,
    """            scaleX = 1f\n            scaleY = 1f\n            pivotX = width / 2f\n            pivotY = height / 2f\n            setImageDrawable(null)\n""",
    """            scaleX = 1f\n            scaleY = 1f\n            translationX = 0f\n            translationY = 0f\n            pivotX = width / 2f\n            pivotY = height / 2f\n            setImageDrawable(null)\n""",
    "clear gallery transform",
)

crop_method = r'''    private fun cropGalleryForCurrentZoom(source: Bitmap): Bitmap {
        val view = scannerGalleryImageView
        val viewWidth = view?.width ?: 0
        val viewHeight = view?.height ?: 0
        val zoom = scannerGalleryZoom.coerceIn(0.55f, 6f)

        // FIT_CENTER at 1x shows the complete photo. Zooming below 1x only adds margins, so AI
        // should still receive the complete source image. Above 1x, map the exact panned viewport
        // back into bitmap coordinates so "Cari" analyzes what the user is actually looking at.
        if (viewWidth <= 1 || viewHeight <= 1 || zoom <= 1.001f) return source

        val fitScale = minOf(
            viewWidth.toFloat() / source.width.coerceAtLeast(1),
            viewHeight.toFloat() / source.height.coerceAtLeast(1)
        )
        val displayScale = fitScale * zoom
        if (displayScale <= 0f) return source

        val displayedWidth = source.width * displayScale
        val displayedHeight = source.height * displayScale
        val imageLeft = (viewWidth - displayedWidth) / 2f + scannerGalleryPanX
        val imageTop = (viewHeight - displayedHeight) / 2f + scannerGalleryPanY

        val visibleLeftPx = (-imageLeft).coerceIn(0f, displayedWidth)
        val visibleTopPx = (-imageTop).coerceIn(0f, displayedHeight)
        val visibleRightPx = (viewWidth - imageLeft).coerceIn(0f, displayedWidth)
        val visibleBottomPx = (viewHeight - imageTop).coerceIn(0f, displayedHeight)

        val left = (visibleLeftPx / displayScale).toInt().coerceIn(0, source.width - 1)
        val top = (visibleTopPx / displayScale).toInt().coerceIn(0, source.height - 1)
        val right = (visibleRightPx / displayScale).toInt().coerceIn(left + 1, source.width)
        val bottom = (visibleBottomPx / displayScale).toInt().coerceIn(top + 1, source.height)

        if (left == 0 && top == 0 && right == source.width && bottom == source.height) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

'''
service = regex_replace_once(
    service,
    r"    private fun cropGalleryForCurrentZoom\(source: Bitmap\): Bitmap \{.*?(?=    private fun toggleScannerTorch\(\))",
    crop_method,
    "gallery crop mapping",
)

service_path.write_text(service)

# ---------------------------------------------------------------------------
# AI Horde vision query: preserve more useful visual evidence instead of reducing a
# rear-view photo to only "wanita pakai celana pendek abu-abu".
# ---------------------------------------------------------------------------
vision = vision_path.read_text()
vision = vision.replace("confidence >= 0.28", "confidence >= 0.15")
vision = vision.replace(".take(10),\n            nsfw = nsfw,", ".take(24),\n            nsfw = nsfw,")
vision = vision.replace("val trustedTags = rawTags.filter { it.second >= 0.34 }", "val trustedTags = rawTags.filter { it.second >= 0.20 }")
vision = vision.replace(".filter { it.second >= 0.30 }", ".filter { it.second >= 0.18 }", 1)
vision = vision.replace("confidence >= 0.34 && Regex(\"\\\\b(ass|butt|buttocks|booty|backside)\\\\b\"", "confidence >= 0.18 && Regex(\"\\\\b(ass|butt|buttocks|booty|backside)\\\\b\"")
vision = vision.replace("confidence >= 0.55 && Regex(\"\\\\b(woman|women|female)\\\\b\"", "confidence >= 0.35 && Regex(\"\\\\b(woman|women|female)\\\\b\"")
vision = vision.replace(".filter { it.second >= 0.34 }\n            .joinToString(\" \") { it.first.lowercase() }", ".filter { it.second >= 0.18 }\n            .joinToString(\" \") { it.first.lowercase() }", 1)

# Make clothing "ketat" work when Alchemy emits separate clothing/material/style tags.
vision = replace_once(
    vision,
    """        val tightShorts = Regex("\\\\b(tight|fitted|form-fitting|skin-tight|body-hugging|booty)\\\\s+shorts\\\\b", RegexOption.IGNORE_CASE)\n            .containsMatchIn(combined)\n        if (tightShorts) return "celana pendek ketat"\n\n        // If an English tag slipped through translation, normalize common visible outerwear here.\n""",
    """        val hasShorts = Regex("\\\\b(shorts|athletic shorts|gym shorts|celana pendek)\\\\b", RegexOption.IGNORE_CASE)\n            .containsMatchIn(combined)\n        val tightSignal = Regex(\n            "\\\\b(tight|fitted|form-fitting|skin-tight|body-hugging|close-fitting|stretch|stretchy|spandex|lycra|booty)\\\\b",\n            RegexOption.IGNORE_CASE\n        ).containsMatchIn(combined)\n        if (hasShorts && tightSignal) return "celana pendek ketat"\n\n        val hasLeggings = Regex("\\\\b(leggings|legging|yoga pants)\\\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)\n        if (hasLeggings && tightSignal) return "legging ketat"\n\n        // If an English tag slipped through translation, normalize common visible outerwear here.\n""",
    "separate tight clothing signals",
)

# Replace body-shape reasoning with a direct but evidence-bound version.
body_shape = r'''    private fun bestBodyShapeDescription(
        rawCaption: String,
        rawTags: List<Pair<String, Double>>,
        clothing: String,
        view: String,
        ageUnclear: Boolean
    ): String {
        if (ageUnclear) return ""
        val adultEvidence = Regex("\\b(adult woman|woman|women|female)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(rawCaption) || rawTags.any { (text, confidence) ->
                confidence >= 0.35 && Regex("\\b(woman|women|female)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            }
        if (!adultEvidence) return ""

        val usableTags = rawTags.filter { it.second >= 0.18 }
        val source = (rawCaption.lowercase() + " " + usableTags.joinToString(" ") { it.first.lowercase() })
            .replace(Regex("\\s+"), " ")
        val lowerBodyCovered = clothing.contains("celana") || clothing.contains("legging") ||
            clothing.contains("rok") || clothing.contains("gaun") || clothing.contains("baju renang") ||
            clothing.contains("bikini") || clothing.contains("pakaian bawah")

        val buttPattern = Regex("\\b(ass|butt|buttocks|booty|backside)\\b", RegexOption.IGNORE_CASE)
        val sizePattern = Regex("\\b(big|large|prominent|full|curvy|thick|thicc|round|voluptuous|shapely)\\b", RegexOption.IGNORE_CASE)
        val buttVisible = buttPattern.containsMatchIn(rawCaption) || usableTags.any { buttPattern.containsMatchIn(it.first) }
        val sizeEvidence = sizePattern.containsMatchIn(rawCaption) || usableTags.any { sizePattern.containsMatchIn(it.first) }
        val explicitlyLargeButt = Regex(
            "\\b(big|large|prominent|full|curvy|thick|thicc|round|voluptuous|shapely)\\s+(ass|butt|buttocks|booty|backside)\\b|" +
                "\\b(ass|butt|buttocks|booty|backside)\\s+(looks|appears|is)?\\s*(big|large|prominent|full|round|curvy|thick)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(source)

        return when {
            view == "dari belakang" && lowerBodyCovered && buttVisible && (explicitlyLargeButt || sizeEvidence) ->
                "bokong terlihat besar"
            view == "dari belakang" && lowerBodyCovered && buttVisible -> "bokong terlihat"
            else -> ""
        }
    }

'''
vision = regex_replace_once(
    vision,
    r"    private fun bestBodyShapeDescription\(.*?(?=    private fun bestColorDescription\()",
    body_shape,
    "body shape evidence logic",
)

vision = vision.replace(".take(10)\n            .joinToString(\" \")\n            .take(96)", ".take(12)\n            .joinToString(\" \")\n            .take(112)")
vision_path.write_text(vision)

# Keep the generic vision normalizer from cutting the richer AI Horde person query back down.
client = client_path.read_text()
client = client.replace(
    "Buat query pencarian visual yang pendek, natural, dan faktual: bahasa Indonesia, 3–10 kata, maksimal sekitar 96 karakter.",
    "Buat query pencarian visual yang pendek, natural, dan faktual: bahasa Indonesia, 3–12 kata, maksimal sekitar 112 karakter."
)
client = client.replace(
    "val maxWords = if (subject == \"person\") 10 else 7\n        val maxChars = if (subject == \"person\") 96 else 64",
    "val maxWords = if (subject == \"person\") 12 else 7\n        val maxChars = if (subject == \"person\") 112 else 64"
)
client_path.write_text(client)

print("Applied direct vision query + camera/gallery pan/zoom fixes")
