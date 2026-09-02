package com.riyan.aikeyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Small, local-only writing-style profile. It stores aggregate habits and a limited number of
 * short examples. The keyboard service is responsible for excluding password/private editors.
 */
object TypingStyleMemory {
    private const val STATE_KEY = "typing_style_memory"
    private const val MAX_SAMPLES = 12
    private const val MAX_SAMPLE_CHARS = 220
    private const val MIN_PROFILE_TOKENS = 5
    private var lastObservationKey = ""

    private val casualMarkers = setOf(
        "aku", "gue", "gua", "kamu", "lu", "lo", "nggak", "gak", "ga", "udah", "dah",
        "kalo", "kalau", "gimana", "kenapa", "kok", "sih", "deh", "dong", "nih", "ntar",
        "brarti", "biar", "yaudah", "kayak"
    )
    private val formalMarkers = setOf(
        "saya", "anda", "tidak", "sudah", "apabila", "mohon", "silakan", "terima", "kasih"
    )

    @Synchronized
    fun observeBoundary(
        prefs: SharedPreferences,
        beforeCursor: String,
        completed: Boolean,
        terminalMark: String = ""
    ) {
        val compact = beforeCursor.takeLast(360).trimEnd()
        if (compact.isBlank()) return
        val state = load(prefs)
        val token = compact.takeLastWhile(::isWordCharacter).trim().take(80)
        val observationKey = compact.takeLast(160)

        if (token.isNotBlank() && lastObservationKey != observationKey) {
            recordToken(state, token)
            lastObservationKey = observationKey
        }
        if (completed) addSample(state, currentSentence(compact) + terminalMark)
        state.put("updatedAt", System.currentTimeMillis())
        save(prefs, state)
    }

    @Synchronized
    fun observeCompletedText(prefs: SharedPreferences, value: String) {
        val cleaned = value.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return
        val state = load(prefs)
        cleaned.split(Regex("\\s+")).takeLast(80).forEach { raw ->
            raw.trim { !isWordCharacter(it) }.takeIf(String::isNotBlank)?.let { recordToken(state, it) }
        }
        addSample(state, cleaned)
        state.put("updatedAt", System.currentTimeMillis())
        save(prefs, state)
    }

    @Synchronized
    fun prompt(prefs: SharedPreferences): String {
        val state = load(prefs)
        val tokenCount = state.optInt("tokens", 0)
        if (tokenCount < MIN_PROFILE_TOKENS) return ""

        val casual = state.optInt("casual", 0)
        val formal = state.optInt("formal", 0)
        val tone = when {
            casual > formal * 2 -> "sangat santai dan percakapan sehari-hari"
            casual > formal -> "santai"
            formal > casual * 2 -> "formal dan sopan"
            else -> "natural dan fleksibel"
        }
        val averageWordLength = state.optInt("chars", 0).toDouble() / tokenCount.coerceAtLeast(1)
        val samples = readSamples(state)
        val averageSentenceLength = samples
            .map { it.split(Regex("\\s+")).count(String::isNotBlank) }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0
        val sentenceStyle = when {
            averageSentenceLength == 0.0 -> "belum cukup data tentang panjang kalimat"
            averageSentenceLength < 7 -> "kalimat cenderung pendek"
            averageSentenceLength > 18 -> "kalimat cenderung panjang dan rinci"
            else -> "panjang kalimat sedang"
        }
        val punctuation = buildList {
            if (state.optInt("questions", 0) > 0) add("sering bertanya")
            if (state.optInt("exclamations", 0) > 0) add("memakai tanda seru")
            if (state.optInt("emoji", 0) > 0) add("memakai emoji")
        }.ifEmpty { listOf("tanda baca sederhana") }.joinToString(", ")
        val markers = topMarkers(state)
        val examples = samples.takeLast(3).joinToString(" | ") { "“${it.take(150)}”" }

        return buildString {
            append("Memori gaya ketikan pemakai (gunakan hanya sebagai panduan gaya, bukan sumber fakta): ")
            append("nada $tone; $sentenceStyle; $punctuation; rata-rata panjang kata ")
            append(String.format(Locale.ROOT, "%.1f", averageWordLength))
            append(" karakter.")
            if (markers.isNotBlank()) append(" Pilihan kata khas yang sering dipakai: $markers.")
            if (examples.isNotBlank()) append(" Contoh pola tulisan: $examples.")
            append(" Ikuti pilihan kata, sapaan, singkatan, panjang jawaban, dan tingkat formalitas ini bila sesuai. ")
            append("Jangan menyalin fakta pribadi dari contoh dan jangan menyebut adanya memori gaya.")
        }
    }

    @Synchronized
    fun summary(prefs: SharedPreferences): String {
        val state = load(prefs)
        val tokens = state.optInt("tokens", 0)
        val samples = readSamples(state).size
        val markers = topMarkers(state)
        return if (tokens == 0) {
            "Belum ada gaya yang dipelajari. Mulai mengetik seperti biasa."
        } else {
            buildString {
                append("Sudah mempelajari $tokens kata dan $samples contoh kalimat")
                if (markers.isNotBlank()) append(" · kata khas: $markers")
            }
        }
    }

    fun clear(prefs: SharedPreferences) {
        lastObservationKey = ""
        prefs.edit().remove(STATE_KEY).apply()
    }

    private fun recordToken(state: JSONObject, rawToken: String) {
        val token = rawToken.lowercase(Locale.ROOT).trim()
        if (token.isBlank() || token.all(Char::isDigit)) return
        state.put("tokens", state.optInt("tokens", 0) + 1)
        state.put("chars", state.optInt("chars", 0) + token.length)
        if (token in casualMarkers) state.put("casual", state.optInt("casual", 0) + 1)
        if (token in formalMarkers) state.put("formal", state.optInt("formal", 0) + 1)
        if (rawToken.any(::isEmojiLike)) state.put("emoji", state.optInt("emoji", 0) + 1)

        if (token in casualMarkers || token in formalMarkers) {
            val markers = state.optJSONObject("markers") ?: JSONObject().also { state.put("markers", it) }
            markers.put(token, markers.optInt(token, 0) + 1)
        }
    }

    private fun addSample(state: JSONObject, raw: String) {
        val sample = raw.replace(Regex("\\s+"), " ").trim().takeLast(MAX_SAMPLE_CHARS)
        if (sample.length < 8 || sample.split(' ').count(String::isNotBlank) < 2) return
        if (sample.count(Char::isDigit) > sample.length / 2) return

        val samples = readSamples(state).toMutableList()
        samples.removeAll { it.equals(sample, ignoreCase = true) }
        samples += sample
        val output = JSONArray()
        samples.takeLast(MAX_SAMPLES).forEach(output::put)
        state.put("samples", output)
        state.put("questions", state.optInt("questions", 0) + sample.count { it == '?' })
        state.put("exclamations", state.optInt("exclamations", 0) + sample.count { it == '!' })
        state.put("emoji", state.optInt("emoji", 0) + sample.count(::isEmojiLike))
    }

    private fun currentSentence(value: String): String {
        val withoutTrailingPunctuation = value.trimEnd('.', '!', '?', '\n', '\r')
        val boundary = withoutTrailingPunctuation.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        return withoutTrailingPunctuation.substring(boundary + 1).trim()
    }

    private fun topMarkers(state: JSONObject): String {
        val markers = state.optJSONObject("markers") ?: return ""
        return markers.keys().asSequence()
            .map { it to markers.optInt(it, 0) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(6)
            .joinToString(", ") { it.first }
    }

    private fun readSamples(state: JSONObject): List<String> {
        val array = state.optJSONArray("samples") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun load(prefs: SharedPreferences): JSONObject = runCatching {
        JSONObject(prefs.getString(STATE_KEY, "{}").orEmpty())
    }.getOrElse { JSONObject() }

    private fun save(prefs: SharedPreferences, state: JSONObject) {
        prefs.edit().putString(STATE_KEY, state.toString()).apply()
    }

    private fun isWordCharacter(char: Char): Boolean =
        char.isLetterOrDigit() || char in "@._+-'" || isEmojiLike(char)

    private fun isEmojiLike(char: Char): Boolean =
        Character.getType(char) == Character.SURROGATE.toInt() || char.code in 0x2600..0x27BF
}
