package com.riyan.aikeyboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


data class ScreenTextSnapshot(
    val packageName: String,
    val text: String,
    val capturedAt: Long
)

/**
 * Provides an on-demand snapshot of visible text for AI actions.
 *
 * Accessibility events are not recorded. The view tree and, on Android 11+, a screenshot are read
 * only after the user taps an AI action in the keyboard. Password editors are filtered by the IME
 * before captureNow() is called. Screenshot OCR is a fallback for apps such as X/Facebook that
 * render post bodies without exposing the real text through AccessibilityNodeInfo.
 */
class ScreenTextAccessibilityService : AccessibilityService() {
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var textRecognizer: TextRecognizer? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { textRecognizer?.close() }
        ocrExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private fun capture(targetPackage: String?): ScreenTextSnapshot? {
        val roots = findApplicationRoots(targetPackage)
        val packageId = targetPackage
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?: roots.mapNotNull { it.root.packageName?.toString() }
                .firstOrNull { it.isNotBlank() && it != packageName }
            ?: rootInActiveWindow?.packageName?.toString().orEmpty()

        val entries = mutableListOf<VisibleText>()
        roots.forEach { candidate ->
            runCatching { candidate.root.refresh() }
            collectVisibleText(candidate.root, entries, depth = 0, windowLayer = candidate.layer)
        }

        val accessibilityContent = entries
            .sortedWith(
                compareBy<VisibleText> { it.top }
                    .thenBy { it.left }
                    .thenByDescending { it.windowLayer }
            )
            .distinctBy { it.normalized.lowercase(Locale.ROOT) }
            .take(MAX_VISIBLE_ITEMS)
            .joinToString("\n") { entry ->
                if (entry.editable) "[Kolom tulisan pengguna] ${entry.normalized}" else entry.normalized
            }
            .take(MAX_SCREEN_CONTEXT_CHARS)
            .trim()

        // X, Facebook, and some Compose/custom-rendered apps expose only labels/buttons through the
        // accessibility tree. OCR the part of the display above the IME so the AI receives the text
        // the user can actually see instead of just controls such as "Balas", "Ikuti", etc.
        val ocrContent = captureScreenshotText()
        val content = when {
            ocrContent.isNotBlank() -> ocrContent
            accessibilityContent.isNotBlank() -> accessibilityContent
            else -> return null
        }

        return ScreenTextSnapshot(packageId, content, System.currentTimeMillis())
    }

    private fun captureScreenshotText(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""

        val latch = CountDownLatch(1)
        val recognizedText = AtomicReference("")

        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val hardwareBuffer = screenshot.hardwareBuffer
                val fullBitmap = try {
                    Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { hardwareBuffer.close() }
                }

                if (fullBitmap == null) {
                    latch.countDown()
                    return
                }

                val cropBottom = keyboardTopOnScreen(fullBitmap.height)
                val visibleAppBitmap = try {
                    if (cropBottom in 1 until fullBitmap.height) {
                        Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, cropBottom)
                    } else {
                        fullBitmap
                    }
                } catch (_: Throwable) {
                    fullBitmap
                }

                if (visibleAppBitmap !== fullBitmap) fullBitmap.recycle()

                val image = InputImage.fromBitmap(visibleAppBitmap, 0)
                recognizer().process(image)
                    .addOnSuccessListener(ocrExecutor) { result ->
                        recognizedText.set(cleanOcrText(result.text))
                    }
                    .addOnFailureListener(ocrExecutor) {
                        recognizedText.set("")
                    }
                    .addOnCompleteListener(ocrExecutor) {
                        runCatching { visibleAppBitmap.recycle() }
                        latch.countDown()
                    }
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        }

        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor, callback)
        }.onFailure {
            latch.countDown()
        }

        runCatching { latch.await(OCR_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return recognizedText.get().take(MAX_SCREEN_CONTEXT_CHARS).trim()
    }

    /**
     * Accessibility window coordinates use the same physical display coordinate space as the
     * screenshot. Cropping at the IME's top edge removes this keyboard/AI panel from OCR while
     * retaining the post/message visible above it.
     */
    private fun keyboardTopOnScreen(screenshotHeight: Int): Int {
        val tops = windows.orEmpty().mapNotNull { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@mapNotNull null
            val bounds = Rect()
            runCatching { window.getBoundsInScreen(bounds) }.getOrNull() ?: return@mapNotNull null
            bounds.top.takeIf { it > 0 }
        }
        val detected = tops.minOrNull()
        return detected?.coerceIn(1, screenshotHeight)
            ?: (screenshotHeight * FALLBACK_APP_AREA_RATIO).toInt().coerceIn(1, screenshotHeight)
    }

    private fun cleanOcrText(raw: String): String = raw
        .lineSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { line ->
            line.length >= 2 &&
                line.any { it.isLetterOrDigit() } &&
                !line.startsWith("AI Ads Keyboard", ignoreCase = true)
        }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .joinToString("\n")
        .take(MAX_SCREEN_CONTEXT_CHARS)
        .trim()

    private fun recognizer(): TextRecognizer {
        textRecognizer?.let { return it }
        return synchronized(this) {
            textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .also { textRecognizer = it }
        }
    }

    /**
     * A reply sheet/dialog can be a separate application window while the post/message being
     * replied to remains in another window from the same package. Read every matching application
     * window instead of only the first one.
     */
    private fun findApplicationRoots(targetPackage: String?): List<RootCandidate> {
        val applicationRoots = windows.orEmpty()
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val rootPackage = root.packageName?.toString().orEmpty()
                if (rootPackage.isBlank() || rootPackage == packageName) return@mapNotNull null
                RootCandidate(root, window.layer)
            }
            .toList()

        val matching = applicationRoots.filter {
            targetPackage.isNullOrBlank() || it.root.packageName?.toString() == targetPackage
        }
        if (matching.isNotEmpty()) return matching
        if (applicationRoots.isNotEmpty()) return applicationRoots

        val active = rootInActiveWindow ?: return emptyList()
        val activePackage = active.packageName?.toString().orEmpty()
        if (activePackage.isBlank() || activePackage == packageName) return emptyList()
        if (!targetPackage.isNullOrBlank() && activePackage != targetPackage) return emptyList()
        return listOf(RootCandidate(active, 0))
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        output: MutableList<VisibleText>,
        depth: Int,
        windowLayer: Int
    ) {
        if (depth > MAX_TREE_DEPTH || output.size >= MAX_RAW_ITEMS || node.isPassword) return

        val visible = node.isVisibleToUser
        if (visible) {
            val values = textCandidates(node)
            val className = node.className?.toString().orEmpty()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            values.forEach { raw ->
                if (output.size >= MAX_RAW_ITEMS) return@forEach
                val normalized = raw.replace(Regex("\\s+"), " ").trim()
                if (
                    normalized.isNotBlank() &&
                    shouldKeep(normalized, className, node.isEditable, node.isClickable)
                ) {
                    output += VisibleText(
                        normalized = normalized.take(MAX_ITEM_CHARS),
                        top = bounds.top,
                        left = bounds.left,
                        editable = node.isEditable,
                        windowLayer = windowLayer
                    )
                }
            }
        }

        // Some Compose/custom-view containers report isVisibleToUser=false even though one or more
        // virtual descendants are visible. Do not drop the whole subtree just because the parent
        // itself is not marked visible; every child is checked again before its text is accepted.
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectVisibleText(child, output, depth + 1, windowLayer)
            }
            if (output.size >= MAX_RAW_ITEMS) break
        }
    }

    private fun textCandidates(node: AccessibilityNodeInfo): List<String> {
        val values = LinkedHashSet<String>()

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)

        // Never use an empty reply field's hint/contentDescription as message content.
        if (!node.isEditable) {
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.paneTitle?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
                node.tooltipText?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            }
        }

        return values.toList()
    }

    private fun shouldKeep(value: String, className: String, editable: Boolean, clickable: Boolean): Boolean {
        if (value.equals("AI Ads Keyboard", ignoreCase = true)) return false
        if (editable) return true

        val looksLikeControl = className.endsWith("Button") || className.endsWith("ImageButton") ||
            className.endsWith("Switch") || className.endsWith("CheckBox")

        // Modern apps often implement controls as clickable TextViews/Compose nodes instead of
        // Button classes. Filter short controls, while keeping longer post/message bodies even if
        // their parent happens to be clickable.
        if ((looksLikeControl || clickable) && value.length <= 80) return false
        return value.length >= 2 && value.any { it.isLetterOrDigit() }
    }

    private data class RootCandidate(
        val root: AccessibilityNodeInfo,
        val layer: Int
    )

    private data class VisibleText(
        val normalized: String,
        val top: Int,
        val left: Int,
        val editable: Boolean,
        val windowLayer: Int
    )

    companion object {
        @Volatile
        private var instance: ScreenTextAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun captureNow(targetPackage: String?): ScreenTextSnapshot? =
            runCatching { instance?.capture(targetPackage) }.getOrNull()

        private const val MAX_TREE_DEPTH = 32
        private const val MAX_RAW_ITEMS = 320
        private const val MAX_VISIBLE_ITEMS = 160
        private const val MAX_ITEM_CHARS = 1_200
        private const val MAX_SCREEN_CONTEXT_CHARS = 16_000
        private const val OCR_TIMEOUT_MS = 2_500L
        private const val FALLBACK_APP_AREA_RATIO = 0.60
    }
}
