package com.riyan.aikeyboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale

data class ScreenTextSnapshot(
    val packageName: String,
    val text: String,
    val capturedAt: Long
)

/**
 * Provides an on-demand snapshot of visible text for AI actions.
 * Accessibility events are not recorded. The view tree is traversed only after the user taps an
 * AI action in the keyboard, and password nodes are always excluded.
 */
class ScreenTextAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private fun capture(targetPackage: String?): ScreenTextSnapshot? {
        val root = findApplicationRoot(targetPackage) ?: return null
        val packageId = root.packageName?.toString().orEmpty()
        if (packageId.isBlank() || packageId == packageName) return null

        val entries = mutableListOf<VisibleText>()
        collectVisibleText(root, entries, depth = 0)
        val content = entries
            .sortedWith(compareBy<VisibleText> { it.top }.thenBy { it.left })
            .distinctBy { it.normalized.lowercase(Locale.ROOT) }
            .takeLast(MAX_VISIBLE_ITEMS)
            .joinToString("\n") { entry ->
                if (entry.editable) "[Kolom tulisan pengguna] ${entry.normalized}" else entry.normalized
            }
            .takeLast(MAX_SCREEN_CONTEXT_CHARS)
            .trim()
        if (content.isBlank()) return null
        return ScreenTextSnapshot(packageId, content, System.currentTimeMillis())
    }

    private fun findApplicationRoot(targetPackage: String?): AccessibilityNodeInfo? {
        val applicationRoots = windows.orEmpty()
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() != packageName }
            .toList()
        return applicationRoots.firstOrNull {
            targetPackage.isNullOrBlank() || it.packageName?.toString() == targetPackage
        } ?: applicationRoots.firstOrNull() ?: rootInActiveWindow
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        output: MutableList<VisibleText>,
        depth: Int
    ) {
        if (depth > MAX_TREE_DEPTH || output.size >= MAX_RAW_ITEMS || !node.isVisibleToUser || node.isPassword) return

        // Do not treat an editable field's hint/content description as user text. Empty reply
        // composers commonly expose strings such as "Write a reply", which previously became the
        // translation source even though the user had not typed anything.
        val raw = node.text?.toString().orEmpty().ifBlank {
            if (node.isEditable) "" else node.contentDescription?.toString().orEmpty()
        }
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        val className = node.className?.toString().orEmpty()
        if (normalized.isNotBlank() && shouldKeep(normalized, className, node.isEditable, node.isClickable)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            output += VisibleText(normalized.take(MAX_ITEM_CHARS), bounds.top, bounds.left, node.isEditable)
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> collectVisibleText(child, output, depth + 1) }
            if (output.size >= MAX_RAW_ITEMS) break
        }
    }

    private fun shouldKeep(value: String, className: String, editable: Boolean, clickable: Boolean): Boolean {
        if (value.equals("AI Ads Keyboard", ignoreCase = true)) return false
        if (editable) return true
        val looksLikeControl = className.endsWith("Button") || className.endsWith("ImageButton") ||
            className.endsWith("Switch") || className.endsWith("CheckBox")
        // Modern apps often implement controls as clickable TextViews/Compose nodes instead of
        // Button classes. Filtering short clickable nodes keeps Back/Post/menu labels out while
        // retaining the actual long post or message body.
        if ((looksLikeControl || clickable) && value.length <= 80) return false
        return value.length >= 2 && value.any { it.isLetterOrDigit() }
    }

    private data class VisibleText(
        val normalized: String,
        val top: Int,
        val left: Int,
        val editable: Boolean
    )

    companion object {
        @Volatile
        private var instance: ScreenTextAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun captureNow(targetPackage: String?): ScreenTextSnapshot? =
            runCatching { instance?.capture(targetPackage) }.getOrNull()

        private const val MAX_TREE_DEPTH = 24
        private const val MAX_RAW_ITEMS = 160
        private const val MAX_VISIBLE_ITEMS = 80
        private const val MAX_ITEM_CHARS = 700
        private const val MAX_SCREEN_CONTEXT_CHARS = 12_000
    }
}
