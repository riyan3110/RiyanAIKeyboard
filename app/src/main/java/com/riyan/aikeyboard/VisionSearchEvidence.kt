package com.riyan.aikeyboard

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit

/**
 * Grounded local evidence for image search.
 *
 * Product labels are often more reliable than generic vision tags. This helper reads OCR from the
 * exact camera/gallery crop and, only when the text looks like a real product identity, prefers
 * brand/model/spec tokens over noisy scene words such as bottle, couch, chair, or background.
 */
object VisionSearchEvidence {
    private val tokenRegex = Regex("[A-Za-z0-9][A-Za-z0-9+./-]*")
    private val specRegex = Regex(
        "(?i)^(?:\\d{1,2}w[- ]?\\d{2}|\\d+(?:\\.\\d+)?(?:ml|l|kg|g)|[24]t|vr\\d+|[a-z]*\\d+[a-z0-9-]*)$"
    )

    private val productWords = setOf(
        "oil", "oli", "lubricant", "pelumas", "shampoo", "conditioner", "serum", "lotion",
        "cream", "sabun", "soap", "detergent", "coffee", "kopi", "tea", "teh", "milk", "susu",
        "drink", "minuman", "snack", "makanan", "racing", "motorcycle", "motor"
    )

    private val noise = setOf(
        "anti", "slip", "on", "clutch", "fully", "synthetic", "for", "stroke", "motorcycle",
        "api", "sn", "jaso", "ma2", "team", "the", "and", "with", "of", "product", "objek",
        "object", "bottle", "botol", "couch", "sofa", "chair", "kursi", "gray", "grey", "abu-abu",
        "pengguna", "sedang", "memperbesar", "area", "target", "sekitar", "prioritaskan", "subjek",
        "utama", "pada", "ini", "dan", "detail", "kecil", "pembeda", "yang", "benar-benar", "terlihat",
        "abaikan", "latar", "tidak", "relevan", "teks", "lokal", "mungkin"
    )

    fun recognizeText(recognizer: TextRecognizer, bitmap: Bitmap): String {
        if (bitmap.width < 24 || bitmap.height < 24) return ""
        val result = Tasks.await(
            recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            4,
            TimeUnit.SECONDS
        )
        return result.textBlocks
            .flatMap { it.lines }
            .map { it.text.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .joinToString(" | ")
            .take(700)
    }

    fun refineQuery(visionQuery: String, localTextHint: String): String {
        val productIdentity = extractProductIdentity(localTextHint)
        return if (productIdentity.isNotBlank()) productIdentity else visionQuery
    }

    private fun extractProductIdentity(localTextHint: String): String {
        if (localTextHint.isBlank()) return ""

        var source = localTextHint
        if (source.contains("Teks lokal yang mungkin relevan:", ignoreCase = true)) {
            source = source.substringAfter("Teks lokal yang mungkin relevan:", source)
        }
        if (source.contains("Teks OCR lokal:", ignoreCase = true)) {
            source = source.substringAfter("Teks OCR lokal:", source)
        }

        val originalTokens = tokenRegex.findAll(source).map { it.value.trim() }.toList()
        if (originalTokens.isEmpty()) return ""

        val lowerTokens = originalTokens.map { it.lowercase() }
        val hasCategory = lowerTokens.any { it in productWords }
        val hasSpec = originalTokens.any { specRegex.matches(it) }
        val meaningfulAlpha = originalTokens.count { token ->
            token.length >= 3 && token.any(Char::isLetter) && token.lowercase() !in noise
        }
        if (meaningfulAlpha < 2 || (!hasCategory && !hasSpec)) return ""

        val chosen = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        for (token in originalTokens) {
            val clean = token.trim('-', '.', '/', ' ')
            val lower = clean.lowercase()
            if (clean.length < 2 || lower in noise) continue
            if (lower in setOf("www", "com", "http", "https")) continue
            if (lower.all { it.isDigit() } && clean.length > 6) continue
            if (!seen.add(lower)) continue
            chosen += normalizeSpec(clean)
            if (chosen.size >= 8) break
        }

        if (chosen.size < 2) return ""
        return chosen.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(104)
            .trim()
    }

    private fun normalizeSpec(token: String): String {
        return token
            .replace(Regex("(?i)^(\\d{1,2})w[ -]?(\\d{2})$"), "$1W-$2")
            .replace(Regex("(?i)^(\\d+(?:\\.\\d+)?)(ml|kg|g|l)$")) { match ->
                match.groupValues[1] + match.groupValues[2].uppercase()
            }
            .replace(Regex("(?i)^([24])t$")) { match -> match.groupValues[1] + "T" }
    }
}
