from pathlib import Path
import re

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/ScreenTextAccessibilityService.kt")


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new, 1)


service = SERVICE.read_text(encoding="utf-8")

# OCR callbacks already have their own executor. Use a second executor for the blocking wait so the
# ML Kit callback can always complete without deadlocking the accessibility service/main thread.
service = replace_required(
    service,
    '''    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val eventHandler = Handler(Looper.getMainLooper())''',
    '''    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val snapshotExecutor = Executors.newSingleThreadExecutor()
    private val eventHandler = Handler(Looper.getMainLooper())''',
    "snapshot executor",
)

service = replace_required(
    service,
    '''    @Volatile private var textRecognizer: TextRecognizer? = null
    @Volatile private var pendingCachePackage = ""''',
    '''    @Volatile private var textRecognizer: TextRecognizer? = null
    @Volatile private var pendingCachePackage = ""
    @Volatile private var pendingOcrCachePackage = ""''',
    "pending OCR package",
)

# Capture a full OCR snapshot while the source app is still unobscured. This is intentionally
# skipped once an IME window is visible; the normal on-demand capture then handles the currently
# visible region above the keyboard. Only recognized text is kept, never bitmap pixels.
if "private val cacheVisibleOcr = Runnable" not in service:
    anchor = '''    private val cacheVisibleText = Runnable {'''
    if anchor not in service:
        raise RuntimeError("Patch target not found: OCR cache runnable anchor")
    block = '''    private val cacheVisibleOcr = Runnable {
        val targetPackage = pendingOcrCachePackage
        if (
            targetPackage.isBlank() ||
            targetPackage == packageName ||
            isInputMethodVisible() ||
            !isPackageVisible(targetPackage)
        ) return@Runnable

        snapshotExecutor.execute {
            val entries = captureScreenshotEntries(cropAboveIme = false)
            if (entries.none { !it.editable && isMeaningfulContent(it.normalized) }) return@execute
            rememberSnapshot(targetPackage, entries)
        }
    }

'''
    service = service.replace(anchor, block + anchor, 1)

service = replace_required(
    service,
    '''        pendingCachePackage = eventPackage
        eventHandler.removeCallbacks(cacheVisibleText)
        eventHandler.postDelayed(cacheVisibleText, SCREEN_CACHE_DEBOUNCE_MS)''',
    '''        pendingCachePackage = eventPackage
        eventHandler.removeCallbacks(cacheVisibleText)
        eventHandler.postDelayed(cacheVisibleText, SCREEN_CACHE_DEBOUNCE_MS)

        // Cache OCR only for source-screen changes, never for the user's editable text changes.
        // This gives Balas/Terjemah the unobscured post even after the reply sheet and IME open.
        if (safeEvent.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            pendingOcrCachePackage = eventPackage
            eventHandler.removeCallbacks(cacheVisibleOcr)
            eventHandler.postDelayed(cacheVisibleOcr, OCR_CACHE_DEBOUNCE_MS)
        }''',
    "schedule background OCR cache",
)

service = replace_required(
    service,
    '''        runCatching { textRecognizer?.close() }
        ocrExecutor.shutdownNow()
        super.onDestroy()''',
    '''        runCatching { textRecognizer?.close() }
        ocrExecutor.shutdownNow()
        snapshotExecutor.shutdownNow()
        super.onDestroy()''',
    "shutdown snapshot executor",
)

# The on-demand path keeps cropping above the keyboard. Background caching explicitly requests the
# full screen because it only runs while no IME window is visible.
service = replace_required(
    service,
    '''    private fun captureScreenshotEntries(): List<VisibleText> {''',
    '''    private fun captureScreenshotEntries(cropAboveIme: Boolean = true): List<VisibleText> {''',
    "OCR capture parameter",
)

service = replace_required(
    service,
    '''                val cropBottom = keyboardTopOnScreen(fullBitmap.height)''',
    '''                val cropBottom = if (cropAboveIme) keyboardTopOnScreen(fullBitmap.height) else fullBitmap.height''',
    "conditional screenshot crop",
)

# If Android does not expose an IME window there is no safe reason to discard the lower 40% of the
# screen. Keeping the full image prevents long posts from being silently truncated.
service = replace_required(
    service,
    '''        return detected?.coerceIn(1, screenshotHeight)
            ?: (screenshotHeight * FALLBACK_APP_AREA_RATIO).toInt().coerceIn(1, screenshotHeight)''',
    '''        return detected?.coerceIn(1, screenshotHeight) ?: screenshotHeight''',
    "no-IME screenshot fallback",
)

helpers = '''    private fun isInputMethodVisible(): Boolean =
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

'''
if "private fun isInputMethodVisible()" not in service:
    marker = '''    private fun cleanOcrText(raw: String): String = raw'''
    if marker not in service:
        raise RuntimeError("Patch target not found: OCR visibility helpers")
    service = service.replace(marker, helpers + marker, 1)

# Preserve the chronological order of OCR snapshots when the user scrolls through a long post.
# Overlapping text is still deduplicated later by mergeEntries().
pattern = re.compile(
    r'''    private fun recentEntriesFor\(packageId: String\): List<VisibleText> = synchronized\(recentSnapshots\) \{.*?\n    \}\n\n    private fun rememberSnapshot''',
    re.S,
)
if "SNAPSHOT_VERTICAL_STRIDE" in service and "flatMapIndexed" in service:
    pass
else:
    replacement = '''    private fun recentEntriesFor(packageId: String): List<VisibleText> = synchronized(recentSnapshots) {
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

    private fun rememberSnapshot'''
    service, count = pattern.subn(replacement, service, count=1)
    if count != 1:
        raise RuntimeError("Patch target not found: chronological cached OCR stitching")

# Keep enough short-lived text-only snapshots to cover several scroll positions of one post/chat.
service = service.replace(
    '''        private const val MAX_VISIBLE_ITEMS = 160''',
    '''        private const val MAX_VISIBLE_ITEMS = 240''',
    1,
)
service = replace_required(
    service,
    '''        private const val SCREEN_CACHE_DEBOUNCE_MS = 180L
        private const val SCREEN_CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        private const val MAX_CACHED_SNAPSHOTS = 6
        private const val MAX_CACHED_SNAPSHOTS_PER_PACKAGE = 3''',
    '''        private const val SCREEN_CACHE_DEBOUNCE_MS = 180L
        private const val OCR_CACHE_DEBOUNCE_MS = 260L
        private const val SCREEN_CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        private const val MAX_CACHED_SNAPSHOTS = 12
        private const val MAX_CACHED_SNAPSHOTS_PER_PACKAGE = 6
        private const val SNAPSHOT_VERTICAL_STRIDE = 10_000''',
    "OCR cache constants",
)

SERVICE.write_text(service, encoding="utf-8")
print("Applied full-screen pre-IME OCR cache and chronological multi-snapshot stitching.")
