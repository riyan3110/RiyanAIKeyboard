package com.riyan.aikeyboard

object PublicSearchText {
    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")
        .findAll(text).map { it.value.lowercase() }.toList()

    // AI may format speech but cannot silently replace numbers, names, negation or intent.
    fun voiceQuery(transcript: String, corrected: String): String {
        val original = transcript.trim().replace(Regex("\\s+"), " ")
        val candidate = corrected.trim().replace(Regex("\\s+"), " ")
        return if (candidate.isNotBlank() && words(candidate) == words(original)) candidate else original
    }

    fun visualQuery(raw: String): String {
        val text = raw.trim().trim('"', '\'', '`')
            .replace(Regex("(?i)^(?:query|search query|pencarian|hasil)\\s*:\\s*"), "")
            .replace(Regex("\\s+"), " ").trim()
        // Reject runaway repetitive model output instead of sending a misleading search.
        val tokens = words(text)
        if (tokens.size > 8) {
            val triples = tokens.windowed(3)
            if (triples.groupingBy { it }.eachCount().values.any { it >= 3 }) return ""
        }
        return text
    }
}
