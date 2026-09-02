package com.riyan.aikeyboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
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
        val roots = findApplicationRoots(targetPackage)
        if (roots.isEmpty()) return null

        val packageId = roots
            .mapNotNull { it.root.packageName?.toString() }
            .firstOrNull { it.isNotBlank() && it != packageName }
            .orEmpty()
        if (packageId.isBlank()) return null

        val entries = mutableListOf<VisibleText>()
        roots.forEach { candidate ->
            runCatching { candidate.root.refresh() }
            collectVisibleText(candidate.root, entries, depth = 0, windowLayer = candidate.layer)
        }

        val content = entries
            .sortedWith(
                compareBy<VisibleText> { it.top }
                    .thenBy { it.left }
                    .thenByDescending { it.windowLayer }
            )
            .distinctBy { it.normalized.lowercase(Locale.ROOT) }
            // Keep text from the top of the screen too. The previous takeLast() could discard the
            // actual post/message when a modern app exposed many toolbar/menu accessibility nodes.
            .take(MAX_VISIBLE_ITEMS)
            .joinToString("\n") { entry ->
                if (entry.editable) "[Kolom tulisan pengguna] ${entry.normalized}" else entry.normalized
            }
            .take(MAX_SCREEN_CONTEXT_CHARS)
            .trim()

        if (content.isBlank()) return null
        return ScreenTextSnapshot(packageId, content, System.currentTimeMillis())
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
    }
}
