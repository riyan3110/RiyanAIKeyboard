from pathlib import Path

service_path = Path('app/src/main/java/com/riyan/aikeyboard/ScreenTextAccessibilityService.kt')
xml_path = Path('app/src/main/res/xml/screen_accessibility_service.xml')
keyboard_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')

s = service_path.read_text(encoding='utf-8')
x = xml_path.read_text(encoding='utf-8')
k = keyboard_path.read_text(encoding='utf-8')

# Imports for throttling and queue protection.
if 'import android.os.SystemClock\n' not in s:
    s = s.replace('import android.os.Looper\n', 'import android.os.Looper\nimport android.os.SystemClock\n', 1)
if 'import java.util.concurrent.atomic.AtomicBoolean\n' not in s:
    s = s.replace('import java.util.concurrent.atomic.AtomicReference\n', 'import java.util.concurrent.atomic.AtomicReference\nimport java.util.concurrent.atomic.AtomicBoolean\n', 1)

# State used to prevent event storms from continuously traversing the UI tree / scheduling OCR.
marker = '    @Volatile private var pendingOcrCachePackage = ""\n'
addition = marker + '''    @Volatile private var lastAcceptedEventPackage = ""
    @Volatile private var lastAcceptedEventAt = 0L
    private val textCacheBusy = AtomicBoolean(false)
    private val ocrCacheBusy = AtomicBoolean(false)
'''
if 'private val textCacheBusy = AtomicBoolean(false)' not in s:
    if marker not in s:
        raise RuntimeError('accessibility state marker not found')
    s = s.replace(marker, addition, 1)

# OCR cache: never run while the IME is visible, and never queue more than one heavy screenshot/OCR job.
start = s.find('    private val cacheVisibleOcr = Runnable {')
end = s.find('\n\n    private val cacheVisibleText = Runnable {', start)
if start < 0 or end < 0:
    raise RuntimeError('cacheVisibleOcr block not found')
new_ocr = '''    private val cacheVisibleOcr = Runnable {
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
    }'''
s = s[:start] + new_ocr + s[end:]

# Accessibility-tree cache used to run recursively on the accessibility/main thread every ~180 ms.
# Move it to the worker and skip it entirely while the keyboard is visible.
start = s.find('    private val cacheVisibleText = Runnable {')
end = s.find('\n\n    override fun onServiceConnected()', start)
if start < 0 or end < 0:
    raise RuntimeError('cacheVisibleText block not found')
new_text = '''    private val cacheVisibleText = Runnable {
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
    }'''
s = s[:start] + new_text + s[end:]

# Event handler: drop typing/focus events immediately, coalesce content storms, and only OCR
# after the UI has been quiet. This is the critical fix for TikTok/Compose apps.
start = s.find('    override fun onAccessibilityEvent(event: AccessibilityEvent?) {')
end = s.find('\n\n    override fun onInterrupt()', start)
if start < 0 or end < 0:
    raise RuntimeError('onAccessibilityEvent block not found')
new_event = '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val type = safeEvent.eventType

        // Typing into any editable field can generate one accessibility event per character.
        // The keyboard already owns that text through InputConnection, so these events must never
        // trigger a full accessibility-tree walk or screenshot OCR.
        if (
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) return

        if (
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return

        val eventPackage = safeEvent.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName) return

        val now = SystemClock.uptimeMillis()
        val minGap = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> 0L
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> SCROLL_EVENT_THROTTLE_MS
            else -> CONTENT_EVENT_THROTTLE_MS
        }
        if (
            eventPackage == lastAcceptedEventPackage &&
            now - lastAcceptedEventAt < minGap
        ) return
        lastAcceptedEventPackage = eventPackage
        lastAcceptedEventAt = now

        pendingCachePackage = eventPackage
        eventHandler.removeCallbacks(cacheVisibleText)
        eventHandler.postDelayed(cacheVisibleText, SCREEN_CACHE_DEBOUNCE_MS)

        // Screenshot OCR is much heavier than the accessibility tree. Only schedule one after
        // meaningful screen/navigation changes and only after the app has settled.
        pendingOcrCachePackage = eventPackage
        eventHandler.removeCallbacks(cacheVisibleOcr)
        eventHandler.postDelayed(cacheVisibleOcr, OCR_CACHE_DEBOUNCE_MS)
    }'''
s = s[:start] + new_event + s[end:]

# Replace aggressive debounce values from the old cache implementation.
s = s.replace('private const val SCREEN_CACHE_DEBOUNCE_MS = 180L', 'private const val SCREEN_CACHE_DEBOUNCE_MS = 900L')
s = s.replace('private const val OCR_CACHE_DEBOUNCE_MS = 260L', 'private const val OCR_CACHE_DEBOUNCE_MS = 2_500L')
const_marker = '        private const val OCR_CACHE_DEBOUNCE_MS = 2_500L\n'
if 'private const val CONTENT_EVENT_THROTTLE_MS' not in s:
    if const_marker not in s:
        raise RuntimeError('accessibility constants marker not found')
    s = s.replace(const_marker, const_marker + '''        private const val CONTENT_EVENT_THROTTLE_MS = 700L
        private const val SCROLL_EVENT_THROTTLE_MS = 450L
''', 1)

# Accessibility service declaration: stop subscribing to every typed character/focus change and
# stop requesting not-important/enhanced-web nodes globally. On-demand screenshot OCR remains.
x = x.replace(
    'android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled|typeViewFocused|typeViewTextChanged"',
    'android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled"'
)
x = x.replace(
    'android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|flagRequestEnhancedWebAccessibility"',
    'android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"'
)
x = x.replace('android:canRequestEnhancedWebAccessibility="true"', 'android:canRequestEnhancedWebAccessibility="false"')
x = x.replace('android:notificationTimeout="120"', 'android:notificationTimeout="500"')

# Extra keyboard-side relief: let predictions wait until the user actually pauses. v16 already
# made key commits immediate, so this removes one more source of main-thread contention.
k = k.replace('handler.postDelayed(suggestionRefreshRunnable, 140L)', 'handler.postDelayed(suggestionRefreshRunnable, 260L)')

service_path.write_text(s, encoding='utf-8')
xml_path.write_text(x, encoding='utf-8')
keyboard_path.write_text(k, encoding='utf-8')
print('Applied v0.18 v17: low-overhead AccessibilityService + typing-safe cache throttling.')
