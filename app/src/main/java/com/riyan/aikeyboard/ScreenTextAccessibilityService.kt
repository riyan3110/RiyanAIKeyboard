package com.riyan.aikeyboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicBoolean


data class ScreenTextSnapshot(
    val packageName: String,
    val text: String,
    val capturedAt: Long,
    val primaryText: String = text,
    val latestIncomingText: String = ""
)

/**
 * Provides a complete, on-demand snapshot of post and conversation text for AI actions.
 *
 * Recent non-password view text is kept briefly in memory so opening the keyboard or its fullscreen
 * AI panel does not erase the application context underneath it. Nothing is persisted to storage.
 * On Android 11+, screenshot OCR is combined with the accessibility tree for apps such as X or
 * Facebook that render only part of a post through AccessibilityNodeInfo.
 */
class ScreenTextAccessibilityService : AccessibilityService() {
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val snapshotExecutor = Executors.newSingleThreadExecutor()
    private val eventHandler = Handler(Looper.getMainLooper())
    @Volatile private var textRecognizer: TextRecognizer? = null
    @Volatile private var pendingCachePackage = ""
    @Volatile private var pendingOcrCachePackage = ""
    @Volatile private var lastAcceptedEventPackage = ""
    @Volatile private var lastAcceptedEventAt = 0L
    private val textCacheBusy = AtomicBoolean(false)
    private val ocrCacheBusy = AtomicBoolean(false)

    private val cacheVisibleOcr = Runnable {
        val targetPackage = pendingOcrCachePackage
        if (
            targetPackage.isBlank() ||
            targetPackage == packageName ||
            isInputMethodVisible() ||
            !isPackageVisible(targetPackage) ||
            !ocrCacheBusy.compareAndSet(false, true)
        ) return@Runnable

        snapshotExecutor.execute {
            try {
                val entries = captureScreenshotEntries(cropAboveIme = false)
                if (entries.any { !it.editable && isMeaningfulContent(it.normalized) }) {
                    rememberSnapshot(targetPackage, entries)
                }
            } finally {
                ocrCacheBusy.set(false)
            }
        }
    }

    private val cacheVisibleText = Runnable {
        val targetPackage = pendingCachePackage
        if (
            targetPackage.isBlank() ||
            targetPackage == packageName ||
            isInputMethodVisible() ||
            !textCacheBusy.compareAndSet(false, true)
        ) return@Runnable

        snapshotExecutor.execute {
            try {
                val entries = collectApplicationEntries(targetPackage)
                if (entries.none { !it.editable && isMeaningfulContent(it.normalized) }) return@execute
                synchronized(recentSnapshots) {
                    recentSnapshots.removeAll {
                        it.packageName == targetPackage && sameEntrySet(it.entries, entries)
                    }
                    recentSnapshots.add(
                        CachedScreenText(
                            packageName = targetPackage,
                            entries = entries,
                            capturedAt = System.currentTimeMillis()
                        )
                    )
                    trimRecentSnapshotsLocked()
                }
            } finally {
                textCacheBusy.set(false)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately passive. Never traverse the app tree, take screenshots, run OCR,
        // or mutate caches from background accessibility events while the user is typing.
        // AI actions call captureNow() explicitly when screen context is actually needed.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        eventHandler.removeCallbacksAndMessages(null)
        synchronized(recentSnapshots) { recentSnapshots.clear() }
        runCatching { textRecognizer?.close() }
        ocrExecutor.shutdownNow()
        snapshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        synchronized(recentSnapshots) { recentSnapshots.clear() }
        return super.onUnbind(intent)
    }

    private fun capture(targetPackage: String?): ScreenTextSnapshot? {
        val roots = findApplicationRoots(targetPackage)
        val packageId = targetPackage
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?: roots.mapNotNull { it.root.packageName?.toString() }
                .firstOrNull { it.isNotBlank() && it != packageName }
            ?: rootInActiveWindow?.packageName?.toString().orEmpty()

        val accessibilityEntries = mutableListOf<VisibleText>()
        roots.forEach { candidate ->
            runCatching { candidate.root.refresh() }
            collectVisibleText(candidate.root, accessibilityEntries, depth = 0, windowLayer = candidate.layer)
        }

        // X, Facebook, and some Compose/custom-rendered apps expose only labels/buttons through the
        // accessibility tree. OCR the part of the display above the IME so the AI receives the text
        // the user can actually see instead of just controls such as "Balas", "Ikuti", etc.
        val ocrEntries = captureScreenshotEntries()
        val cachedEntries = emptyList<VisibleText>()
        val currentEntries = mergeEntries(accessibilityEntries, ocrEntries)
        val allEntries = mergeEntries(currentEntries, cachedEntries)
        val primaryText = contentText(allEntries, includeEditable = false)
        val fullContext = contentText(allEntries, includeEditable = true)
        if (primaryText.isBlank() && fullContext.isBlank()) return null

        val rawLatestIncoming = latestIncomingText(
            packageId = packageId,
            currentEntries = currentEntries,
            fallbackEntries = cachedEntries,
            primaryText = primaryText
        )
        // Long posts/articles are commonly split into several OCR/accessibility blocks. In that
        // situation the old "latest incoming" heuristic incorrectly selected only the last paragraph.
        // Use the complete visible post instead. Short chat bubbles still keep latest-message priority.
        val substantialBlocks = currentEntries.count {
            !it.editable && isMeaningfulContent(it.normalized) && it.normalized.length >= 80
        }
        val mainScreenText = if (primaryText.length >= 260 && substantialBlocks >= 2) {
            primaryText
        } else {
            rawLatestIncoming.ifBlank { primaryText }
        }
        val structuredText = buildString {
            if (mainScreenText.isNotBlank()) {
                append("[POSTINGAN ATAU PESAN UTAMA SAAT INI]\n")
                append(mainScreenText)
            }
            if (primaryText.isNotBlank() && !equivalentText(primaryText, mainScreenText)) {
                if (isNotEmpty()) append("\n\n")
                append("[KONTEKS TEKS LAYAR LENGKAP]\n")
                append(primaryText)
            }
            val editableText = allEntries
                .asSequence()
                .filter { it.editable && it.normalized.isNotBlank() }
                .map { it.normalized }
                .distinctBy { normalizeForComparison(it) }
                .joinToString("\n")
                .take(MAX_EDITABLE_CONTEXT_CHARS)
            if (editableText.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("[DRAF PENGGUNA — JANGAN DIANGGAP PESAN MASUK]\n")
                append(editableText)
            }
        }.take(MAX_SCREEN_CONTEXT_CHARS).trim()

        return ScreenTextSnapshot(
            packageName = packageId,
            text = structuredText.ifBlank { fullContext },
            capturedAt = System.currentTimeMillis(),
            primaryText = primaryText.ifBlank { fullContext },
            latestIncomingText = mainScreenText
        )
    }

    private fun captureScreenshotEntries(cropAboveIme: Boolean = true): List<VisibleText> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()

        val latch = CountDownLatch(1)
        val recognizedEntries = AtomicReference<List<VisibleText>>(emptyList())

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

                val cropBottom = if (cropAboveIme) keyboardTopOnScreen(fullBitmap.height) else fullBitmap.height
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
                        recognizedEntries.set(
                            result.textBlocks.mapNotNull { block ->
                                val normalized = cleanOcrText(block.text)
                                val bounds = block.boundingBox ?: return@mapNotNull null
                                normalized.takeIf(::isMeaningfulContent)?.let {
                                    VisibleText(
                                        normalized = it.take(MAX_ITEM_CHARS),
                                        top = bounds.top,
                                        left = bounds.left,
                                        right = bounds.right,
                                        bottom = bounds.bottom,
                                        editable = false,
                                        windowLayer = Int.MAX_VALUE,
                                        source = TextSource.OCR
                                    )
                                }
                            }
                        )
                    }
                    .addOnFailureListener(ocrExecutor) {
                        recognizedEntries.set(emptyList())
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
        return recognizedEntries.get()
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
        return detected?.coerceIn(1, screenshotHeight) ?: screenshotHeight
    }

    private fun isInputMethodVisible(): Boolean =
        windows.orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun isPackageVisible(targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        val matchingWindow = windows.orEmpty().any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
            window.root?.packageName?.toString() == targetPackage
        }
        if (matchingWindow) return true
        return rootInActiveWindow?.packageName?.toString() == targetPackage
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

    private fun collectApplicationEntries(targetPackage: String?): List<VisibleText> {
        val entries = mutableListOf<VisibleText>()
        findApplicationRoots(targetPackage).forEach { candidate ->
            runCatching { candidate.root.refresh() }
            collectVisibleText(candidate.root, entries, depth = 0, windowLayer = candidate.layer)
        }
        return entries
    }

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
                        right = bounds.right,
                        bottom = bounds.bottom,
                        editable = node.isEditable,
                        windowLayer = windowLayer,
                        source = TextSource.ACCESSIBILITY
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

    private fun mergeEntries(primary: List<VisibleText>, secondary: List<VisibleText>): List<VisibleText> {
        val merged = mutableListOf<VisibleText>()
        (primary + secondary).forEach { candidate ->
            val candidateKey = normalizeForComparison(candidate.normalized)
            if (candidateKey.isBlank()) return@forEach
            val duplicateIndex = merged.indexOfFirst { existing ->
                val existingKey = normalizeForComparison(existing.normalized)
                existingKey == candidateKey ||
                    (candidateKey.length >= MIN_SUBSTRING_DEDUP_CHARS && existingKey.contains(candidateKey)) ||
                    (existingKey.length >= MIN_SUBSTRING_DEDUP_CHARS && candidateKey.contains(existingKey))
            }
            if (duplicateIndex < 0) {
                merged += candidate
            } else if (candidate.normalized.length > merged[duplicateIndex].normalized.length) {
                merged[duplicateIndex] = candidate
            }
        }
        return merged
            .sortedWith(compareBy<VisibleText> { it.top }.thenBy { it.left }.thenByDescending { it.windowLayer })
            .take(MAX_VISIBLE_ITEMS)
    }

    private fun contentText(entries: List<VisibleText>, includeEditable: Boolean): String = entries
        .asSequence()
        .filter { includeEditable || !it.editable }
        .filter { it.editable || isMeaningfulContent(it.normalized) }
        .map { entry ->
            if (entry.editable) "[Kolom tulisan pengguna] ${entry.normalized}" else entry.normalized
        }
        .distinctBy { normalizeForComparison(it) }
        .joinToString("\n")
        .take(MAX_SCREEN_CONTEXT_CHARS)
        .trim()

    private fun latestIncomingText(
        packageId: String,
        currentEntries: List<VisibleText>,
        fallbackEntries: List<VisibleText>,
        primaryText: String
    ): String {
        val entries = currentEntries.ifEmpty { fallbackEntries }
        val bodyEntries = entries.filter {
            !it.editable && isMessageBody(it.normalized) && it.bottom > 0 && it.right > it.left
        }
        if (bodyEntries.isEmpty()) return primaryText

        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val incoming = bodyEntries.filter { entry ->
            val center = (entry.left + entry.right) / 2f
            center <= screenWidth * INCOMING_CENTER_RATIO && entry.right < screenWidth * OUTER_EDGE_RATIO
        }
        val outgoing = bodyEntries.filter { entry ->
            val center = (entry.left + entry.right) / 2f
            center >= screenWidth * OUTGOING_CENTER_RATIO && entry.left > screenWidth * INNER_EDGE_RATIO
        }

        val looksLikeChat = isChatPackage(packageId) || (incoming.isNotEmpty() && outgoing.isNotEmpty())
        if (!looksLikeChat && isPostOrFeedPackage(packageId)) return primaryText

        val lastOutgoingBottom = outgoing.maxOfOrNull { it.bottom } ?: Int.MIN_VALUE
        val unansweredIncoming = incoming.filter { it.bottom > lastOutgoingBottom + MESSAGE_VERTICAL_TOLERANCE_PX }
        val selected = (unansweredIncoming.ifEmpty { incoming }).maxWithOrNull(
            compareBy<VisibleText> { it.bottom }
                .thenBy { it.normalized.length }
        ) ?: bodyEntries.maxWithOrNull(
            compareBy<VisibleText> { it.bottom }
                .thenBy { it.normalized.length }
        )
        return selected?.normalized.orEmpty().ifBlank { primaryText }
    }

    private fun isPostOrFeedPackage(packageId: String): Boolean {
        val lower = packageId.lowercase(Locale.ROOT)
        return POST_OR_FEED_PACKAGE_HINTS.any(lower::contains)
    }

    private fun isChatPackage(packageId: String): Boolean {
        val lower = packageId.lowercase(Locale.ROOT)
        return CHAT_PACKAGE_HINTS.any(lower::contains)
    }

    private fun isMessageBody(value: String): Boolean {
        val clean = value.trim()
        if (!isMeaningfulContent(clean)) return false
        if (clean.startsWith("@") && !clean.contains(' ')) return false
        if (clean.matches(Regex("^[0-9:. /+-]{2,20}$"))) return false
        return clean.length >= MIN_MESSAGE_BODY_CHARS || clean.count(Char::isLetterOrDigit) >= 4
    }

    private fun isMeaningfulContent(value: String): Boolean {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 2 || clean.none { it.isLetterOrDigit() }) return false
        val comparison = clean.lowercase(Locale.ROOT)
        if (comparison in UI_NOISE_EXACT) return false
        if (comparison.startsWith("ai ads keyboard")) return false
        if (STATUS_BAR_PATTERN.matches(comparison)) return false
        return true
    }

    private fun normalizeForComparison(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun equivalentText(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        val a = normalizeForComparison(first)
        val b = normalizeForComparison(second)
        return a == b || (a.length >= MIN_SUBSTRING_DEDUP_CHARS && b.contains(a)) ||
            (b.length >= MIN_SUBSTRING_DEDUP_CHARS && a.contains(b))
    }

    private fun recentEntriesFor(packageId: String): List<VisibleText> = synchronized(recentSnapshots) {
        trimRecentSnapshotsLocked()
        val snapshots = recentSnapshots
            .filter { it.packageName == packageId }
            .takeLast(MAX_CACHED_SNAPSHOTS_PER_PACKAGE)

        snapshots.flatMapIndexed { index, snapshot ->
            val verticalOffset = index * SNAPSHOT_VERTICAL_STRIDE
            snapshot.entries.map { entry ->
                entry.copy(
                    top = entry.top + verticalOffset,
                    bottom = entry.bottom + verticalOffset
                )
            }
        }
    }

    private fun rememberSnapshot(packageId: String, entries: List<VisibleText>) {
        if (packageId.isBlank() || entries.isEmpty()) return
        synchronized(recentSnapshots) {
            recentSnapshots.removeAll { it.packageName == packageId && sameEntrySet(it.entries, entries) }
            recentSnapshots.add(CachedScreenText(packageId, entries, System.currentTimeMillis()))
            trimRecentSnapshotsLocked()
        }
    }

    private fun sameEntrySet(first: List<VisibleText>, second: List<VisibleText>): Boolean {
        val firstText = first.joinToString("\n") { normalizeForComparison(it.normalized) }
        val secondText = second.joinToString("\n") { normalizeForComparison(it.normalized) }
        return firstText == secondText
    }

    private fun trimRecentSnapshotsLocked() {
        val oldestAllowed = System.currentTimeMillis() - SCREEN_CACHE_MAX_AGE_MS
        recentSnapshots.removeAll { it.capturedAt < oldestAllowed }
        while (recentSnapshots.size > MAX_CACHED_SNAPSHOTS) recentSnapshots.removeAt(0)
    }

    private data class RootCandidate(
        val root: AccessibilityNodeInfo,
        val layer: Int
    )

    private data class VisibleText(
        val normalized: String,
        val top: Int,
        val left: Int,
        val right: Int,
        val bottom: Int,
        val editable: Boolean,
        val windowLayer: Int,
        val source: TextSource
    )

    private data class CachedScreenText(
        val packageName: String,
        val entries: List<VisibleText>,
        val capturedAt: Long
    )

    private enum class TextSource { ACCESSIBILITY, OCR }

    companion object {
        @Volatile
        private var instance: ScreenTextAccessibilityService? = null
        private val recentSnapshots = mutableListOf<CachedScreenText>()

        fun isRunning(): Boolean = instance != null

        fun captureNow(targetPackage: String?): ScreenTextSnapshot? =
            runCatching { instance?.capture(targetPackage) }.getOrNull()

        private const val MAX_TREE_DEPTH = 32
        private const val MAX_RAW_ITEMS = 320
        private const val MAX_VISIBLE_ITEMS = 240
        private const val MAX_ITEM_CHARS = 6_000
        private const val MAX_SCREEN_CONTEXT_CHARS = 24_000
        private const val MAX_EDITABLE_CONTEXT_CHARS = 2_000
        private const val OCR_TIMEOUT_MS = 2_500L
        private const val FALLBACK_APP_AREA_RATIO = 0.60
        private const val SCREEN_CACHE_DEBOUNCE_MS = 900L
        private const val OCR_CACHE_DEBOUNCE_MS = 2_500L
        private const val CONTENT_EVENT_THROTTLE_MS = 700L
        private const val SCROLL_EVENT_THROTTLE_MS = 450L
        private const val SCREEN_CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        private const val MAX_CACHED_SNAPSHOTS = 12
        private const val MAX_CACHED_SNAPSHOTS_PER_PACKAGE = 6
        private const val SNAPSHOT_VERTICAL_STRIDE = 10_000
        private const val MIN_SUBSTRING_DEDUP_CHARS = 18
        private const val MIN_MESSAGE_BODY_CHARS = 6
        private const val INCOMING_CENTER_RATIO = 0.49f
        private const val OUTGOING_CENTER_RATIO = 0.51f
        private const val OUTER_EDGE_RATIO = 0.94f
        private const val INNER_EDGE_RATIO = 0.06f
        private const val MESSAGE_VERTICAL_TOLERANCE_PX = 2

        private val POST_OR_FEED_PACKAGE_HINTS = listOf(
            "twitter", "instagram", "facebook", "threads", "linkedin", "reddit",
            "browser", "chrome", "firefox", "news", "medium"
        )
        private val CHAT_PACKAGE_HINTS = listOf(
            "whatsapp", "telegram", "messenger", "orca", "signal", "discord", "line",
            "messages", "messaging", "sms", "viber", "wechat", "kakao"
        )
        private val UI_NOISE_EXACT = setOf(
            "balas", "kirim", "post", "postingan", "kembali", "back", "hapus", "simpan",
            "bagikan", "share", "suka", "like", "ikuti", "follow", "selanjutnya", "next",
            "tampilkan yang asli", "show original", "gif", "foto", "galeri", "menu",
            "ketik pesan", "tulis balasan", "posting balasan anda"
        )
        private val STATUS_BAR_PATTERN = Regex(
            "^(?:[0-2]?\\d[:.]\\d{2})(?:\\s+.*(?:kb/s|mb/s|lte|4g|5g|wifi|%|\\d{1,3}))?$",
            RegexOption.IGNORE_CASE
        )
    }
}
