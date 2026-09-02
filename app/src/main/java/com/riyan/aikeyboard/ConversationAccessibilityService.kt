package com.riyan.aikeyboard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads visible conversation text only when the keyboard's Balas button asks for it.
 * No accessibility events or screen text are stored in the background.
 */
class ConversationAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    private fun collectVisibleConversation(): String {
        val collected = LinkedHashSet<String>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || visited >= MAX_NODES) return
            visited += 1
            val nodePackage = node.packageName?.toString().orEmpty()
            if (nodePackage != packageName && !node.isPassword) {
                val value = node.text?.toString()?.trim().orEmpty()
                if (value.isNotBlank() && value.length <= MAX_SINGLE_TEXT_LENGTH) collected += value
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }

        runCatching {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedBy { it.layer }
                .forEach { window ->
                    val root = window.root
                    if (root?.packageName?.toString() != packageName) visit(root)
                }
        }

        if (collected.isEmpty()) runCatching { visit(rootInActiveWindow) }
        return collected.joinToString("\n").takeLast(MAX_CONTEXT_LENGTH)
    }

    companion object {
        private const val MAX_NODES = 1200
        private const val MAX_SINGLE_TEXT_LENGTH = 1200
        private const val MAX_CONTEXT_LENGTH = 6000

        @Volatile
        private var instance: ConversationAccessibilityService? = null

        fun readVisibleConversation(): String = instance?.collectVisibleConversation().orEmpty()

        fun isConnected(): Boolean = instance != null
    }
}
