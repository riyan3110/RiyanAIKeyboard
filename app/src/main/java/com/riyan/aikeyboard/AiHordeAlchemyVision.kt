package com.riyan.aikeyboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI Horde image understanding through the Alchemy API.
 *
 * Alchemy is intentionally kept separate from the text-generation endpoint: the same AI Horde
 * provider/API key is used, but images are routed to caption/interrogation workers. The returned
 * value is converted into the same grounded JSON shape consumed by AiClient.normalizeVisionResult.
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
            // Slow workers are friendlier to zero-kudos/anonymous usage.
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

            // Interrogation tags can be slower than BLIP captioning. Prefer all forms, but do not
            // make the scanner wait indefinitely once real visual evidence has already arrived.
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
                .take(8),
            nsfw = nsfw,
            completedForms = completed,
            totalForms = forms.length()
        )
    }

    private fun buildGroundedResult(parsed: ParsedForms): String {
        val translatedCaption = translateVisualText(parsed.caption)
        val translatedTags = parsed.tags.map { translateVisualText(it.first) }
        val ageUnclear = isAgeUnclear(parsed.caption, parsed.tags.map { it.first })

        val query = compactQuery(translatedCaption, translatedTags, ageUnclear)
        require(query.isNotBlank()) {
            "AI Horde Alchemy tidak menemukan ciri visual yang cukup jelas."
        }

        var evidenceCaption = translatedCaption
        var evidenceTags = translatedTags
        if (ageUnclear) {
            evidenceCaption = neutralizeSensitiveTerms(evidenceCaption)
            evidenceTags = evidenceTags.map(::neutralizeSensitiveTerms)
        }

        val evidence = buildString {
            if (evidenceCaption.isNotBlank()) append(evidenceCaption)
            val usefulTags = evidenceTags.filter { it.isNotBlank() }.take(4)
            if (usefulTags.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(usefulTags.joinToString(", "))
            }
            // The coarse NSFW boolean is metadata only. It must never create anatomy/exposure
            // claims that caption/interrogation did not visually support.
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
            .put("subject_type", inferSubjectType(translatedCaption, translatedTags))
            .put("confidence", confidence)
            .put("query", query)
            .put("evidence", evidence)
            .toString()
    }

    private fun translateVisualText(raw: String): String {
        var text = raw.trim().lowercase()
        if (text.isBlank()) return ""

        // Phrase replacements first so specific clothing/anatomy descriptions are preserved.
        val phrases = listOf(
            "tight leggings" to "legging ketat",
            "yoga pants" to "legging",
            "mini skirt" to "rok mini",
            "miniskirt" to "rok mini",
            "tight skirt" to "rok ketat",
            "satin skirt" to "rok satin",
            "tight shorts" to "celana pendek ketat",
            "short shorts" to "celana pendek",
            "crop top" to "crop-top",
            "tight shirt" to "baju ketat",
            "tight top" to "baju ketat",
            "tank top" to "tank-top",
            "sports bra" to "bra olahraga",
            "underwear" to "pakaian dalam",
            "bathing suit" to "baju renang",
            "swimsuit" to "baju renang",
            "bare breasts" to "payudara terbuka",
            "bare breast" to "payudara terbuka",
            "large breasts" to "payudara besar",
            "large breast" to "payudara besar",
            "female breasts" to "payudara",
            "female breast" to "payudara",
            "bare buttocks" to "bokong terbuka",
            "bare butt" to "bokong terbuka"
        )
        phrases.forEach { (from, to) -> text = text.replace(from, to) }

        val words = listOf(
            "women" to "wanita", "woman" to "wanita", "female" to "wanita",
            "men" to "pria", "man" to "pria", "male" to "pria",
            "people" to "orang", "person" to "orang",
            "leggings" to "legging", "pants" to "celana", "trousers" to "celana",
            "shorts" to "celana pendek", "skirt" to "rok", "dress" to "gaun",
            "shirt" to "baju", "bra" to "bra",
            "breasts" to "payudara", "breast" to "payudara",
            "nipples" to "puting", "nipple" to "puting",
            "buttocks" to "bokong", "buttock" to "bokong", "butt" to "bokong",
            "vulva" to "vulva", "vagina" to "vagina", "penis" to "penis",
            "scrotum" to "skrotum", "anus" to "anus", "clitoris" to "klitoris",
            "nude" to "telanjang", "naked" to "telanjang",
            "brown" to "cokelat", "black" to "hitam", "white" to "putih",
            "red" to "merah", "blue" to "biru", "green" to "hijau",
            "pink" to "merah muda", "gray" to "abu-abu", "grey" to "abu-abu",
            "outdoor" to "luar ruangan", "outdoors" to "luar ruangan"
        )
        words.forEach { (from, to) ->
            text = text.replace(Regex("(?i)\\b${Regex.escape(from)}\\b"), to)
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun isAgeUnclear(caption: String, rawTags: List<String>): Boolean {
        val source = (caption + " " + rawTags.joinToString(" ")).lowercase()
        return Regex(
            "\\b(girl|boy|child|kid|teen|teenager|minor|baby|young girl|young boy)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(source)
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

    private fun compactQuery(caption: String, tags: List<String>, ageUnclear: Boolean): String {
        val source = listOf(caption)
            .plus(tags.take(5))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (source.isBlank()) return ""

        val stopWords = setOf(
            "a", "an", "the", "is", "are", "was", "were", "with", "and", "or", "of", "to", "from",
            "in", "on", "at", "near", "by", "for", "wearing", "wears", "standing", "sitting", "posing",
            "this", "that", "there", "image", "photo", "picture", "shows", "showing",
            "di", "dan", "yang", "dengan", "dari", "untuk", "sedang", "memakai", "mengenakan",
            "berdiri", "duduk", "berpose", "sebuah", "foto", "gambar", "terlihat"
        )
        val sensitiveWhenAgeUnclear = setOf(
            "payudara", "puting", "vulva", "vagina", "penis", "skrotum", "bokong", "anus",
            "klitoris", "telanjang", "seksi", "sexual", "sex"
        )

        val tokens = source
            .replace(Regex("[^a-z0-9À-ÿ_-]+", RegexOption.IGNORE_CASE), " ")
            .split(Regex("\\s+"))
            .map { it.trim('-', '_').lowercase() }
            .filter { it.length >= 2 && it !in stopWords }
            .filterNot { ageUnclear && it in sensitiveWhenAgeUnclear }
            .distinct()
            .toMutableList()

        val lower = source.lowercase()
        val prefix = when {
            ageUnclear -> "orang"
            Regex("\\bwanita\\b").containsMatchIn(lower) -> "wanita"
            Regex("\\bpria\\b").containsMatchIn(lower) -> "pria"
            Regex("\\borang\\b").containsMatchIn(lower) -> "orang"
            else -> ""
        }
        if (prefix.isNotBlank()) {
            tokens.removeAll { it == "wanita" || it == "pria" || it == "orang" }
            tokens.add(0, prefix)
        }

        return tokens.take(7).joinToString(" ").take(64).trim()
    }

    private fun inferSubjectType(caption: String, tags: List<String>): String {
        val text = (caption + " " + tags.joinToString(" ")).lowercase()
        return when {
            Regex("\\b(wanita|pria|orang|woman|women|man|men|person|people|girl|boy|child|human)\\b")
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
}
