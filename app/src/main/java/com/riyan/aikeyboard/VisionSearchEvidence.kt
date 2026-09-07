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
        "drink", "minuman", "snack", "makanan", "racing", "motorcycle", "motor", "phone", "smartphone",
        "laptop", "tablet", "camera", "headphone", "speaker", "watch", "perfume", "fragrance", "vitamin",
        "medicine", "cleaner", "toothpaste", "battery", "charger", "monitor", "keyboard", "mouse"
    )

    private val noise = setOf(
        "anti", "slip", "on", "clutch", "fully", "synthetic", "for", "stroke", "motorcycle",
        "api", "sn", "jaso", "ma2", "team", "the", "and", "with", "of", "product", "objek",
        "object", "bottle", "botol", "couch", "sofa", "chair", "kursi", "gray", "grey", "abu-abu",
        "background", "wall", "room", "table", "desk", "floor", "seat", "carpet",
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
            .take(900)
    }

    fun refineQuery(visionQuery: String, localTextHint: String): String {
        val productIdentity = extractProductIdentity(localTextHint)
        if (productIdentity.isNotBlank()) return productIdentity

        val clean = visionQuery.trim().replace(Regex("\\s+"), " ")
        return if (looksHuman(clean)) normalizeHumanSearch(clean) else clean
    }

    private fun looksHuman(query: String): Boolean {
        return Regex(
            "\\b(adult woman|adult man|woman|women|female|man|men|male|person|human figure|wanita|perempuan|pria|laki-laki)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(query)
    }

    /**
     * Keep every provider on the same readable English query format. Search-engine-specific
     * switches are intentionally NOT appended here; the browser owns those separately so the
     * visible prompt stays clean and can travel together with the source image attachment.
     */
    private fun normalizeHumanSearch(query: String): String {
        return query
            .replace(Regex("(?i)\\bwanita\\b"), "adult woman")
            .replace(Regex("(?i)\\bperempuan\\b"), "adult woman")
            .replace(Regex("(?i)\\bpria\\b"), "adult man")
            .replace(Regex("(?i)\\bdari belakang\\b"), "rear view")
            .replace(Regex("(?i)\\bdari depan\\b"), "front view")
            .replace(Regex("(?i)\\bdari samping\\b"), "side view")
            .replace(Regex("(?i)\\bcelana pendek ketat\\b"), "tight shorts")
            .replace(Regex("(?i)\\bcelana pendek\\b"), "shorts")
            .replace(Regex("(?i)\\blegging ketat\\b"), "tight leggings")
            .replace(Regex("(?i)\\brok mini ketat\\b"), "tight mini skirt")
            .replace(Regex("(?i)\\brok mini\\b"), "mini skirt")
            .replace(Regex("(?i)\\bbaju ketat\\b"), "fitted top")
            .replace(Regex("(?i)\\babu-abu\\b"), "gray")
            .replace(Regex("(?i)\\bputih\\b"), "white")
            .replace(Regex("(?i)\\bhitam\\b"), "black")
            .replace(Regex("(?i)\\bpakai\\b"), "wearing")
            .replace(Regex("(?i)\\breal\\s+photo(?:graphy)?\\b|\\bphotography\\b|-(?:AI(?:-generated)?|illustration|render|CGI|Midjourney|Stable-Diffusion)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', ';')
            .take(420)
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
        val hasBrandLikeToken = originalTokens.any { token ->
            val letters = token.filter(Char::isLetter)
            token.length in 3..24 && letters.length >= 2 &&
                (letters.count(Char::isUpperCase) >= 2 || token.firstOrNull()?.isUpperCase() == true) &&
                token.lowercase() !in noise
        }
        if (meaningfulAlpha < 2) return ""
        if (!hasCategory && !hasSpec && !(hasBrandLikeToken && meaningfulAlpha >= 3)) return ""

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
            if (chosen.size >= 10) break
        }

        if (chosen.size < 2) return ""
        return chosen.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(140)
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
