package com.riyan.aikeyboard

/** Retains a successful answer separately from insert-once pending text and UI labels. */
internal class PrivateAiTextState {
    var answer: String = ""
        private set
    var busy = false
        private set
    private var generation = 0L

    fun source(action: String, draft: String): String = draft.trim().ifBlank {
        if (action in setOf("Ringkas", "Perbaiki", "Santai", "Inggris")) answer else ""
    }

    fun begin(): Long {
        busy = true
        return ++generation
    }

    fun finish(id: Long, text: String?): Boolean {
        if (id != generation) return false
        busy = false
        if (!text.isNullOrBlank()) answer = text
        return true
    }

    fun clear() {
        generation++
        busy = false
        answer = ""
    }
}
