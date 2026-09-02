package com.riyan.aikeyboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AiProvider(val id: String, val label: String) {
    OPENROUTER("openrouter", "OpenRouter"),
    TABIAI("tabiai", "TabiAI");

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: OPENROUTER
    }
}

data class AiSettings(
    val primaryProvider: AiProvider,
    val openRouterApiKey: String,
    val openRouterModel: String,
    val tabiApiKey: String,
    val tabiBaseUrl: String,
    val tabiModel: String,
    val fallbackEnabled: Boolean
)

data class AiResponse(val text: String, val provider: AiProvider)

object AiClient {
    fun transform(settings: AiSettings, action: String, text: String): Result<AiResponse> {
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Pilih, tempel, atau impor teks terlebih dahulu."))
        }

        return execute(settings, instruction(action), text)
    }

    fun chat(
        settings: AiSettings,
        prompt: String,
        context: String,
        history: String
    ): Result<AiResponse> {
        if (prompt.isBlank()) {
            return Result.failure(IllegalArgumentException("Tulis pesan untuk AI terlebih dahulu."))
        }
        val message = buildString {
            if (context.isNotBlank()) {
                append("Konteks dari kolom teks aplikasi (gunakan hanya jika relevan):\n")
                append(context.takeLast(1800))
                append("\n\n")
            }
            if (history.isNotBlank()) {
                append("Percakapan sebelumnya:\n")
                append(history.takeLast(2400))
                append("\n\n")
            }
            append("Pesan pengguna:\n")
            append(prompt)
        }
        return execute(
            settings,
            "Anda adalah asisten percakapan di AI Ads Kyboard. Jika pesan pengguna berupa instruksi, ikuti instruksinya. Jika pesan tampak seperti chat yang ditempel tanpa instruksi, buat balasan yang wajar. Deteksi bahasa, ragam formal atau santai, dan kebiasaan tutur dari pesan terbaru, lalu jawab memakai bahasa dan tingkat keformalan yang sama. Tulis seperti manusia, ringkas, hangat, tidak kaku, tidak menerjemahkan secara harfiah, dan jangan menjelaskan proses deteksi bahasa. Jangan mengaku telah melakukan tindakan yang tidak dilakukan.",
            message
        )
    }

    private fun execute(settings: AiSettings, systemInstruction: String, text: String): Result<AiResponse> {

        val providers = buildList {
            add(settings.primaryProvider)
            if (settings.fallbackEnabled) {
                add(if (settings.primaryProvider == AiProvider.OPENROUTER) AiProvider.TABIAI else AiProvider.OPENROUTER)
            }
        }

        var lastError: Throwable? = null
        providers.forEach { provider ->
            val attempt = runCatching {
                val output = when (provider) {
                    AiProvider.OPENROUTER -> requestOpenRouter(settings, systemInstruction, text)
                    AiProvider.TABIAI -> requestTabiAi(settings, systemInstruction, text)
                }
                AiResponse(output, provider)
            }
            attempt.getOrNull()?.let { return Result.success(it) }
            lastError = attempt.exceptionOrNull()
        }

        return Result.failure(lastError ?: IllegalStateException("AI gagal merespons."))
    }

    private fun requestOpenRouter(settings: AiSettings, systemInstruction: String, text: String): String {
        require(settings.openRouterApiKey.isNotBlank()) { "API key OpenRouter belum diisi." }
        require(settings.openRouterModel.isNotBlank()) { "Model OpenRouter belum diisi." }

        val body = JSONObject()
            .put("model", settings.openRouterModel.trim())
            .put("temperature", 0.55)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemInstruction))
                    .put(JSONObject().put("role", "user").put("content", text))
            )

        val response = postJson(
            url = "https://openrouter.ai/api/v1/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${settings.openRouterApiKey.trim()}",
                "X-Title" to "AI Ads Kyboard"
            ),
            body = body
        )
        return response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    private fun requestTabiAi(settings: AiSettings, systemInstruction: String, text: String): String {
        require(settings.tabiApiKey.isNotBlank()) { "API key TabiAI belum diisi." }
        require(settings.tabiModel.isNotBlank()) { "Model TabiAI belum diisi." }

        val endpoint = tabiMessagesUrl(settings.tabiBaseUrl)
        require(URL(endpoint).protocol.equals("https", ignoreCase = true)) {
            "Base URL TabiAI harus memakai HTTPS."
        }
        val body = JSONObject()
            .put("model", settings.tabiModel.trim())
            .put("max_tokens", 1024)
            .put("temperature", 0.55)
            .put("system", systemInstruction)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", text)
                )
            )

        val response = postJson(
            url = endpoint,
            headers = mapOf(
                "x-api-key" to settings.tabiApiKey.trim(),
                "anthropic-version" to "2023-06-01"
            ),
            body = body
        )
        val blocks = response.getJSONArray("content")
        val output = buildString {
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
        require(output.isNotBlank()) { "TabiAI mengembalikan respons tanpa teks." }
        return output
    }

    private fun tabiMessagesUrl(baseUrl: String): String {
        val clean = baseUrl.trim().ifBlank { "https://tabitoken.com" }.trimEnd('/')
        return when {
            clean.endsWith("/v1/messages") -> clean
            clean.endsWith("/v1") -> "$clean/messages"
            else -> "$clean/v1/messages"
        }
    }

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = extractError(raw)
                error(message ?: "Permintaan AI gagal ($status).")
            }
            require(raw.isNotBlank()) { "Layanan AI mengembalikan respons kosong." }
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractError(raw: String): String? = runCatching {
        val json = JSONObject(raw)
        when (val error = json.opt("error")) {
            is JSONObject -> error.optString("message").ifBlank { null }
            is String -> error.ifBlank { null }
            else -> json.optString("message").ifBlank { null }
        }
    }.getOrNull()

    private fun instruction(action: String) = when (action) {
        "Perbaiki" -> "Deteksi bahasa teks. Perbaiki typo, pilihan kata, dan tata bahasa secara natural dalam bahasa yang sama. Pertahankan arti, nama, angka, dan gaya penulis. Keluarkan hanya teks hasil."
        "Balas" -> "Baca konteks dan fokus pada pesan terbaru yang disalin, dipilih, atau dibagikan. Deteksi bahasa serta tingkat formalitas pesan terbaru. Buat satu balasan chat yang natural seperti penutur asli, memakai bahasa dan gaya yang sama, sesuai hubungan dan konteks, tidak kaku, tidak berlebihan, dan tidak menyebut proses penerjemahan. Keluarkan hanya balasannya."
        "Santai" -> "Deteksi bahasa teks lalu ubah menjadi gaya yang lebih santai dan natural dalam bahasa yang sama tanpa mengubah arti. Keluarkan hanya hasil."
        "Sopan" -> "Deteksi bahasa teks lalu ubah menjadi lebih sopan dan natural dalam bahasa yang sama. Keluarkan hanya hasil."
        "Ringkas" -> "Deteksi bahasa teks lalu ringkas dalam bahasa yang sama tanpa menghilangkan informasi penting. Keluarkan hanya hasil."
        "Terjemah" -> "Deteksi bahasa sumber secara otomatis. Jika sumber berbahasa Indonesia, terjemahkan secara natural ke bahasa Inggris. Jika sumber bukan bahasa Indonesia, terjemahkan secara natural ke bahasa Indonesia. Pertahankan maksud, nama, angka, dan nada. Keluarkan hanya terjemahan."
        else -> "Bantu tulis ulang teks dengan jelas. Keluarkan hanya hasil."
    }
}
