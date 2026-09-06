package com.riyan.aikeyboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI Horde image understanding through the Alchemy API.
 *
 * The same AI Horde API key is used for text and image interrogation. Alchemy captions/tags are
 * treated as noisy visual evidence, not as a ready-made search query. We compact them into a short,
 * grounded Indonesian query before handing the result back to AiClient.
 */
object AiHordeAlchemyVision {
    private const val BASE_URL = "https://aihorde.net/api/v2"
    private const val ANONYMOUS_KEY = "0000000000"
    private const val CLIENT_AGENT = "AI-Ads-Keyboard:0.21:https://github.com/riyan3110/RiyanAIKeyboard"

    fun request(settings: AiSettings, jpegBase64: String): String {
        require(jpegBase64.isNotBlank()) { "Gambar kamera kosong." }

        val apiKey = settings.aiHordeApiKey.trim().ifBlank { ANONYMOUS_KEY }
        val headers = mapOf(
            "apikey" to apiKey,
            "Client-Agent" to CLIENT_AGENT
        )

        val body = JSONObject()
            .put("source_image", jpegBase64)
            .put(
                "forms",
                JSONArray()
                    .put(JSONObject().put("name", "caption"))
                    .put(JSONObject().put("name", "interrogation"))
                    .put(JSONObject().put("name", "nsfw"))
            )
            .put("slow_workers", true)
            .put("extra_slow_workers", true)

        val submitted = postJson("$BASE_URL/interrogate/async", headers, body)
        val requestId = submitted.optString("id").trim()
        require(requestId.isNotBlank()) {
            submitted.optString("message").ifBlank {
                "AI Horde Alchemy tidak mengembalikan ID antrean."
            }
        }

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + 90_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1_500L)
            val status = getJson("$BASE_URL/interrogate/status/$requestId", headers)
            val state = status.optString("state").lowercase()
            if (state == "faulted" || state == "cancelled") {
                error("AI Horde Alchemy gagal membaca gambar.")
            }

            val parsed = parseForms(status.optJSONArray("forms"))
            val elapsed = System.currentTimeMillis() - startedAt
            val allDone = parsed.totalForms > 0 && parsed.completedForms == parsed.totalForms
            val enoughEvidence = parsed.caption.isNotBlank() || parsed.tags.isNotEmpty()

            if ((state == "done" || allDone || (enoughEvidence && elapsed >= 25_000L)) && enoughEvidence) {
                return buildGroundedResult(parsed)
            }
        }
        error("AI Horde Alchemy masih antre terlalu lama. Coba lagi atau aktifkan fallback provider.")
    }

    private data class ParsedForms(
        val caption: String,
        val tags: List<Pair<String, Double>>,
        val nsfw: Boolean?,
        val completedForms: Int,
        val totalForms: Int
    )

    private fun parseForms(forms: JSONArray?): ParsedForms {
        if (forms == null) return ParsedForms("", emptyList(), null, 0, 0)

        var caption = ""
        var nsfw: Boolean? = null
        var completed = 0
        val tags = mutableListOf<Pair<String, Double>>()

        for (index in 0 until forms.length()) {
            val form = forms.optJSONObject(index) ?: continue
            if (form.optString("state").equals("done", ignoreCase = true)) completed++
            val result = form.optJSONObject("result") ?: continue

            when (form.optString("form").lowercase()) {
                "caption" -> {
                    val value = result.optString("caption").trim()
                    if (value.isNotBlank()) caption = value
                }
                "nsfw" -> if (result.has("nsfw")) {
                    nsfw = result.optBoolean("nsfw")
                }
                "interrogation" -> {
                    val details = result.optJSONObject("interrogation") ?: result
                    val tagArray = details.optJSONArray("tags") ?: continue
                    for (tagIndex in 0 until tagArray.length()) {
                        val item = tagArray.optJSONObject(tagIndex) ?: continue
                        val text = item.optString("text").trim()
                        val confidence = item.optDouble("confidence", 0.0)
                        if (text.isNotBlank() && confidence >= 0.28) {
                            tags += text to confidence
                        }
                    }
                }
            }
        }

        return ParsedForms(
            caption = caption,
            tags = tags
                .sortedByDescending { it.second }
                .distinctBy { it.first.lowercase() }
                .take(10),
            nsfw = nsfw,
            completedForms = completed,
            totalForms = forms.length()
        )
    }

    private fun buildGroundedResult(parsed: ParsedForms): String {
        val rawCaption = parsed.caption.trim()
        val rawTags = parsed.tags.map { it.first }
        val translatedCaption = translateVisualText(rawCaption)
        val translatedTags = parsed.tags.map { translateVisualText(it.first) to it.second }
        val ageUnclear = isAgeUnclear(rawCaption, rawTags)
        val subjectType = inferSubjectType(translatedCaption, translatedTags.map { it.first })

        val query = if (subjectType == "person") {
            buildPersonQuery(rawCaption, parsed.tags, ageUnclear)
        } else {
            compactGenericQuery(translatedCaption, translatedTags.map { it.first })
        }
        require(query.isNotBlank()) {
            "AI Horde Alchemy tidak menemukan ciri visual yang cukup jelas."
        }

        var evidenceCaption = sanitizeEvidence(translatedCaption)
        var evidenceTags = translatedTags
            .filter { it.second >= 0.34 }
            .map { sanitizeEvidence(it.first) }
            .filter { it.isNotBlank() }
        if (ageUnclear) {
            evidenceCaption = neutralizeSensitiveTerms(evidenceCaption)
            evidenceTags = evidenceTags.map(::neutralizeSensitiveTerms)
        }

        val evidence = buildString {
            if (evidenceCaption.isNotBlank()) append(evidenceCaption)
            val usefulTags = evidenceTags.take(4)
            if (usefulTags.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(usefulTags.joinToString(", "))
            }
            if (parsed.nsfw != null) {
                if (isNotEmpty()) append("; ")
                append(if (parsed.nsfw == true) "detektor NSFW positif" else "detektor NSFW negatif")
            }
        }.take(420).ifBlank { query }

        val confidence = when {
            parsed.caption.isNotBlank() && parsed.tags.isNotEmpty() -> 0.78
            parsed.caption.isNotBlank() -> 0.68
            else -> 0.58
        }

        return JSONObject()
            .put("subject_type", subjectType)
            .put("confidence", confidence)
            .put("query", query)
            .put("evidence", evidence)
            .toString()
    }

    /**
     * Person queries are built deliberately instead of concatenating every Alchemy tag. Caption
     * models sometimes output noisy phrases such as "girl", "her ass", "panties" or room names.
     * Those words must not leak straight into search, especially when clothing is clearly present.
     */
    private fun buildPersonQuery(
        rawCaption: String,
        rawTags: List<Pair<String, Double>>,
        ageUnclear: Boolean
    ): String {
        val rawCaptionLower = rawCaption.lowercase()
        val trustedTags = rawTags.filter { it.second >= 0.34 }
        val rawCombined = buildString {
            append(rawCaptionLower)
            trustedTags.forEach { append(' ').append(it.first.lowercase()) }
        }
        val translatedCaption = translateVisualText(rawCaption)
        val translatedTagTexts = trustedTags.map { translateVisualText(it.first) }
        val translatedCombined = (translatedCaption + " " + translatedTagTexts.joinToString(" "))
            .replace(Regex("\\s+"), " ")
            .trim()

        val subject = when {
            ageUnclear -> "orang"
            Regex("\\b(woman|women|female)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawCombined) -> "wanita"
            Regex("\\b(man|men|male)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawCombined) -> "pria"
            Regex("\\bgirl\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawCombined) -> "perempuan"
            Regex("\\bboy\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawCombined) -> "laki-laki"
            translatedCombined.contains("wanita") -> "wanita"
            translatedCombined.contains("pria") -> "pria"
            else -> "orang"
        }

        val clothing = bestClothingDescription(translatedCaption, translatedTagTexts)
        val color = bestColorDescription(translatedCaption, translatedTagTexts)
        val view = bestViewDescription(rawCaption, rawTags)
        val bodyShape = bestBodyShapeDescription(rawCaption, rawTags, clothing, view, ageUnclear)

        val tokens = mutableListOf<String>()
        tokens += subject
        if (view.isNotBlank()) tokens += view.split(Regex("\\s+"))
        if (clothing.isNotBlank()) {
            tokens += clothing.split(Regex("\\s+"))
        } else {
            // If Alchemy only returns noisy underwear/body tags, stay generic instead of turning
            // them into an explicit body-part search. This is safer and much closer to the pixels.
            val genericLowerWear = rawCombined.contains("panties") || rawCombined.contains("underwear") ||
                rawCombined.contains("briefs") || rawCombined.contains("lingerie")
            if (genericLowerWear) tokens += listOf("pakaian", "bawah")
        }
        if (bodyShape.isNotBlank()) tokens += bodyShape.split(Regex("\\s+"))
        if (color.isNotBlank() && color !in tokens && tokens.size <= 7) tokens += color

        // Direct exposure terms may only come from the caption, never from a tag by itself, and
        // never when clothing covering that area is present.
        val hasCoveringClothing = clothing.contains("celana") || clothing.contains("legging") ||
            clothing.contains("rok") || clothing.contains("gaun") || clothing.contains("baju renang") ||
            clothing.contains("bikini") || clothing.contains("pakaian bawah")
        if (!ageUnclear && !hasCoveringClothing) {
            val translatedCaptionLower = translatedCaption.lowercase()
            when {
                rawCaptionLower.contains("bare breasts") || rawCaptionLower.contains("topless") ->
                    tokens += listOf("payudara", "terbuka")
                rawCaptionLower.contains("nude") || rawCaptionLower.contains("naked") ||
                    translatedCaptionLower.contains("telanjang") -> tokens += "telanjang"
            }
        }

        return tokens
            .map { it.lowercase().trim() }
            .filter { it.isNotBlank() && it !in QUERY_NOISE }
            .distinct()
            .take(9)
            .joinToString(" ")
            .take(80)
            .trim()
    }

    private fun bestClothingDescription(caption: String, tags: List<String>): String {
        val captionLower = caption.lowercase()
        val tagLower = tags.joinToString(" ").lowercase()
        val combined = "$captionLower $tagLower"

        // Specific outer clothing beats noisy underwear/body tags from interrogation.
        val priorities = listOf(
            "celana pendek ketat",
            "legging ketat",
            "rok mini ketat",
            "rok mini",
            "rok ketat",
            "rok satin",
            "celana pendek",
            "legging",
            "baju renang",
            "bikini",
            "gaun",
            "celana",
            "rok",
            "crop-top",
            "tank-top",
            "baju ketat",
            "baju"
        )
        priorities.firstOrNull { captionLower.contains(it) }?.let { return it }
        priorities.firstOrNull { tagLower.contains(it) }?.let { return it }

        // If an English tag slipped through translation, normalize common visible outerwear here.
        return when {
            Regex("\\b(shorts|athletic shorts|gym shorts)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "celana pendek"
            Regex("\\b(leggings|yoga pants)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "legging"
            Regex("\\b(skirt|miniskirt|mini skirt)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "rok"
            Regex("\\b(pants|trousers)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "celana"
            else -> ""
        }
    }

    private fun bestViewDescription(rawCaption: String, rawTags: List<Pair<String, Double>>): String {
        val strongTags = rawTags
            .filter { it.second >= 0.42 }
            .joinToString(" ") { it.first.lowercase() }
        val source = (rawCaption.lowercase() + " " + strongTags).replace(Regex("\\s+"), " ")
        return when {
            Regex("\\b(from behind|seen from behind|back view|rear view|backside view|facing away)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(source) -> "dari belakang"
            Regex("\\b(from the side|side view|profile view)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(source) -> "dari samping"
            Regex("\\b(from the front|front view|facing camera)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(source) -> "dari depan"
            else -> ""
        }
    }

    private fun bestBodyShapeDescription(
        rawCaption: String,
        rawTags: List<Pair<String, Double>>,
        clothing: String,
        view: String,
        ageUnclear: Boolean
    ): String {
        if (ageUnclear) return ""
        val adultEvidence = Regex("\\b(adult woman|woman|women|female)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(rawCaption) || rawTags.any { (text, confidence) ->
                confidence >= 0.55 && Regex("\\b(woman|women|female)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            }
        if (!adultEvidence) return ""

        val strongTags = rawTags
            .filter { it.second >= 0.52 }
            .joinToString(" ") { it.first.lowercase() }
        val source = (rawCaption.lowercase() + " " + strongTags).replace(Regex("\\s+"), " ")
        val lowerBodyCovered = clothing.contains("celana") || clothing.contains("legging") ||
            clothing.contains("rok") || clothing.contains("gaun") || clothing.contains("baju renang") ||
            clothing.contains("bikini") || clothing.contains("pakaian bawah")

        val clearlyLargeButt = Regex(
            "\\b(big|large|prominent|full|curvy)\\s+(ass|butt|buttocks|booty)\\b|" +
                "\\b(ass|butt|buttocks|booty)\\s+(looks|appears|is)?\\s*(big|large|prominent|full)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(source)
        return if (view == "dari belakang" && lowerBodyCovered && clearlyLargeButt) {
            "bokong terlihat besar"
        } else ""
    }

    private fun bestColorDescription(caption: String, tags: List<String>): String {
        val captionLower = caption.lowercase()
        val tagLower = tags.joinToString(" ").lowercase()
        val colors = listOf(
            "abu-abu", "cokelat", "hitam", "putih", "merah muda", "merah", "biru", "hijau", "krem"
        )
        return colors.firstOrNull { captionLower.contains(it) }
            ?: colors.firstOrNull { tagLower.contains(it) }
            ?: ""
    }

    private fun translateVisualText(raw: String): String {
        var text = raw.trim().lowercase()
        if (text.isBlank()) return ""

        val phrases = listOf(
            "from behind" to "dari belakang",
            "seen from behind" to "dari belakang",
            "back view" to "dari belakang",
            "rear view" to "dari belakang",
            "big buttocks" to "bokong besar",
            "large buttocks" to "bokong besar",
            "big butt" to "bokong besar",
            "large butt" to "bokong besar",
            "big ass" to "bokong besar",
            "large ass" to "bokong besar",
            "form-fitting shorts" to "celana pendek ketat",
            "fitted shorts" to "celana pendek ketat",
            "athletic shorts" to "celana pendek",
            "gym shorts" to "celana pendek",
            "tight shorts" to "celana pendek ketat",
            "short shorts" to "celana pendek",
            "tight leggings" to "legging ketat",
            "yoga pants" to "legging",
            "mini skirt" to "rok mini",
            "miniskirt" to "rok mini",
            "tight skirt" to "rok ketat",
            "satin skirt" to "rok satin",
            "crop top" to "crop-top",
            "tight shirt" to "baju ketat",
            "tight top" to "baju ketat",
            "tank top" to "tank-top",
            "sports bra" to "bra olahraga",
            "bathing suit" to "baju renang",
            "swimsuit" to "baju renang",
            "bare breasts" to "payudara terbuka",
            "bare breast" to "payudara terbuka",
            "large breasts" to "payudara besar",
            "large breast" to "payudara besar",
            "female breasts" to "payudara",
            "female breast" to "payudara",
            "bare buttocks" to "bokong terbuka",
            "bare butt" to "bokong terbuka",
            "living room" to "ruang tamu"
        )
        phrases.forEach { (from, to) -> text = text.replace(from, to) }

        val words = listOf(
            "women" to "wanita", "woman" to "wanita", "female" to "wanita",
            "men" to "pria", "man" to "pria", "male" to "pria",
            "girl" to "perempuan", "boy" to "laki-laki",
            "people" to "orang", "person" to "orang",
            "leggings" to "legging", "pants" to "celana", "trousers" to "celana",
            "shorts" to "celana pendek", "skirt" to "rok", "dress" to "gaun",
            "shirt" to "baju", "bra" to "bra",
            "panties" to "pakaian bawah", "underwear" to "pakaian bawah", "briefs" to "pakaian bawah",
            "breasts" to "payudara", "breast" to "payudara",
            "nipples" to "puting", "nipple" to "puting",
            "buttocks" to "bokong", "buttock" to "bokong", "butt" to "bokong", "ass" to "bokong",
            "vulva" to "vulva", "vagina" to "vagina", "penis" to "penis",
            "scrotum" to "skrotum", "anus" to "anus", "clitoris" to "klitoris",
            "nude" to "telanjang", "naked" to "telanjang",
            "brown" to "cokelat", "black" to "hitam", "white" to "putih",
            "red" to "merah", "blue" to "biru", "green" to "hijau",
            "pink" to "merah muda", "gray" to "abu-abu", "grey" to "abu-abu",
            "cream" to "krem", "outdoor" to "luar ruangan", "outdoors" to "luar ruangan"
        )
        words.forEach { (from, to) ->
            text = text.replace(Regex("(?i)\\b${Regex.escape(from)}\\b"), to)
        }

        return text
            .replace(Regex("\\b(her|his|their|hers|him|she|he)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isAgeUnclear(caption: String, rawTags: List<String>): Boolean {
        val source = (caption + " " + rawTags.joinToString(" ")).lowercase()
        // Standalone "girl"/"boy" is common captioner wording even for adults, so it is kept
        // age-neutral in the query. Strong youth markers remain conservative.
        return Regex(
            "\\b(child|kid|teen|teenager|minor|baby|young girl|young boy|schoolgirl|schoolboy)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(source)
    }

    private fun sanitizeEvidence(raw: String): String {
        return raw
            .replace(Regex("\\b(her|his|their|hers|him|she|he)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun neutralizeSensitiveTerms(raw: String): String {
        val sensitive = setOf(
            "payudara", "puting", "vulva", "vagina", "penis", "skrotum", "bokong",
            "anus", "klitoris", "telanjang", "seksi", "sexual", "sex"
        )
        return raw
            .split(Regex("\\s+"))
            .filterNot { it.trim(',', '.', ';', ':').lowercase() in sensitive }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun compactGenericQuery(caption: String, tags: List<String>): String {
        val source = listOf(caption)
            .plus(tags.take(4))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (source.isBlank()) return ""

        return source
            .replace(Regex("[^a-z0-9À-ÿ_-]+", RegexOption.IGNORE_CASE), " ")
            .split(Regex("\\s+"))
            .map { it.trim('-', '_').lowercase() }
            .filter { it.length >= 2 && it !in QUERY_NOISE }
            .distinct()
            .take(7)
            .joinToString(" ")
            .take(64)
            .trim()
    }

    private fun inferSubjectType(caption: String, tags: List<String>): String {
        val text = (caption + " " + tags.joinToString(" ")).lowercase()
        return when {
            Regex("\\b(wanita|pria|orang|perempuan|laki-laki|woman|women|man|men|person|people|girl|boy|child|human)\\b")
                .containsMatchIn(text) -> "person"
            Regex("\\b(dog|cat|bird|horse|cow|animal|anjing|kucing|burung|kuda|hewan)\\b")
                .containsMatchIn(text) -> "animal"
            Regex("\\b(car|motorcycle|bike|bicycle|truck|bus|vehicle|mobil|motor|sepeda|kendaraan)\\b")
                .containsMatchIn(text) -> "vehicle"
            Regex("\\b(food|meal|dish|cake|drink|makanan|minuman)\\b").containsMatchIn(text) -> "food"
            Regex("\\b(plant|flower|tree|tanaman|bunga|pohon)\\b").containsMatchIn(text) -> "plant"
            Regex("\\b(text|document|sign|label|teks|dokumen)\\b").containsMatchIn(text) -> "text"
            else -> "object"
        }
    }

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(raw).optString("message") }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "Permintaan AI Horde Alchemy gagal ($status).")
        }
        require(raw.isNotBlank()) { "AI Horde Alchemy mengembalikan respons kosong." }
        return JSONObject(raw)
    }

    private val QUERY_NOISE = setOf(
        "a", "an", "the", "is", "are", "was", "were", "with", "and", "or", "of", "to", "from",
        "in", "on", "at", "near", "by", "for", "wearing", "wears", "standing", "sitting", "posing",
        "this", "that", "there", "image", "photo", "picture", "shows", "showing", "her", "his", "their",
        "hers", "him", "she", "he", "girl", "boy", "ass", "panties", "living", "room",
        "di", "dan", "yang", "dengan", "dari", "untuk", "sedang", "memakai", "mengenakan",
        "berdiri", "duduk", "berpose", "sebuah", "foto", "gambar", "terlihat", "ruang", "tamu"
    )
}
