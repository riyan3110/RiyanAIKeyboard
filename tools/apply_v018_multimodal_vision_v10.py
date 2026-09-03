from pathlib import Path

ai_path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
service_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
ai = ai_path.read_text(encoding='utf-8')
service = service_path.read_text(encoding='utf-8')


def insert_before(text: str, marker: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    pos = text.find(marker)
    if pos < 0:
        raise RuntimeError(f'{label}: marker not found')
    return text[:pos] + addition.rstrip() + '\n\n' + text[pos:]


def replace_block(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    end = text.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError(f'{label}: block not found')
    return text[:start] + replacement.rstrip() + '\n\n' + text[end:]


# -----------------------------------------------------------------------------
# AiClient: real multimodal image understanding using the providers already
# configured by the user. No new API service or key is introduced.
# -----------------------------------------------------------------------------
vision_public = r'''    fun visionProduct(
        settings: AiSettings,
        jpegBase64: String,
        localTextHint: String = ""
    ): Result<AiResponse> {
        if (jpegBase64.isBlank()) {
            return Result.failure(IllegalArgumentException("Gambar kamera kosong."))
        }

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
                    AiProvider.OPENROUTER -> requestOpenRouterVision(settings, jpegBase64, localTextHint)
                    AiProvider.TABIAI -> requestTabiAiVision(settings, jpegBase64, localTextHint)
                }
                AiResponse(output, provider)
            }
            attempt.getOrNull()?.let { return Result.success(it) }
            lastError = attempt.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("AI Vision gagal mengenali gambar."))
    }'''

ai = insert_before(ai, '    private fun execute(', vision_public, 'vision public API')

vision_private = r'''    private fun visionInstruction(localTextHint: String): String = buildString {
        append(
            "Lihat FOTO, bukan sekadar teks. Kenali SATU benda/produk fisik utama yang berada di tengah gambar. " +
                "Buat query pencarian gambar yang paling mungkin menemukan benda yang sama atau model yang sangat mirip. " +
                "Utamakan jenis/fungsi benda, konstruksi/bentuk khas, material, warna, dan ciri pembeda. " +
                "Sebut merek atau model HANYA jika benar-benar terbaca jelas pada gambar. Jangan menebak merek/model. " +
                "Abaikan benda latar belakang dan OCR kecil/noisy. Gunakan nama produk Indonesia atau Inggris yang umum dipakai di marketplace. " +
                "Jawab tepat SATU BARIS query pencarian, 4 sampai 18 kata, tanpa penjelasan, tanpa tanda kutip, tanpa awalan 'query:'."
        )
        val hint = localTextHint.trim().replace(Regex("\\s+"), " ").take(120)
        if (hint.isNotBlank()) {
            append("\nPetunjuk OCR lokal (boleh diabaikan jika bertentangan dengan foto): ")
            append(hint)
        }
    }

    private fun requestOpenRouterVision(
        settings: AiSettings,
        jpegBase64: String,
        localTextHint: String
    ): String {
        require(settings.openRouterApiKey.isNotBlank()) { "API key OpenRouter belum diisi." }
        require(settings.openRouterModel.isNotBlank()) { "Model OpenRouter belum diisi." }

        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", visionInstruction(localTextHint)))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                    )
            )

        val body = JSONObject()
            .put("model", settings.openRouterModel.trim())
            .put("temperature", 0.05)
            .put("max_tokens", 160)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userContent)
                )
            )

        val response = postJson(
            url = "https://openrouter.ai/api/v1/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${settings.openRouterApiKey.trim()}",
                "X-Title" to "AI Ads Keyboard Vision"
            ),
            body = body
        )
        val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = message.opt("content")
        val output = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
            else -> ""
        }.trim()
        require(output.isNotBlank()) { "Model OpenRouter tidak mengembalikan hasil vision." }
        return output
    }

    private fun requestTabiAiVision(
        settings: AiSettings,
        jpegBase64: String,
        localTextHint: String
    ): String {
        require(settings.tabiApiKey.isNotBlank()) { "API key TabiAI belum diisi." }
        require(settings.tabiModel.isNotBlank()) { "Model TabiAI belum diisi." }
        val endpoint = tabiMessagesUrl(settings.tabiBaseUrl)
        require(URL(endpoint).protocol.equals("https", ignoreCase = true)) {
            "Base URL TabiAI harus memakai HTTPS."
        }

        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", jpegBase64)
                    )
            )
            .put(JSONObject().put("type", "text").put("text", visionInstruction(localTextHint)))

        val body = JSONObject()
            .put("model", settings.tabiModel.trim())
            .put("max_tokens", 160)
            .put("temperature", 0.05)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

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
        require(output.isNotBlank()) { "Model TabiAI tidak mengembalikan hasil vision." }
        return output
    }'''

ai = insert_before(ai, '    private fun requestOpenRouter(', vision_private, 'vision provider requests')


# -----------------------------------------------------------------------------
# Camera UI: ML Kit still localizes/crops the main object, but its generic labels
# are no longer shown as product identity. They were the source of Chair/Dog/etc.
# -----------------------------------------------------------------------------
old_candidate = '''        setScannerCandidate(
            previewQuery,
            "",
            score,
            if (objectBounds != null) "Vision lokal mengenali objek utama" else "Vision lokal mengenali ciri objek"
        )'''
new_candidate = '''        scannerSelectedQuery = scannerMainProductText.ifBlank { "Objek fisik siap" }
        scannerSelectedUrl = ""
        scannerBestScore = score
        scannerLastCandidateAt = System.currentTimeMillis()
        handler.post {
            scannerStatusText?.text = "Objek siap · tekan Cari untuk AI Vision"
            scannerResultText?.text = scannerMainProductText.ifBlank { "AI Vision akan mengenali foto objek" }
            scannerSearchButton?.isEnabled = true
        }'''
if old_candidate in service:
    service = service.replace(old_candidate, new_candidate, 1)
elif new_candidate not in service:
    raise RuntimeError('safe camera candidate target not found')

old_empty_status = '''                scannerStatusText?.text = "Vision lokal melihat objek · arahkan lebih dekat lalu tekan Cari"
                scannerResultText?.text = "Objek fisik terdeteksi"'''
new_empty_status = '''                scannerStatusText?.text = "Objek siap · tekan Cari untuk AI Vision"
                scannerResultText?.text = "AI Vision akan mengenali foto objek"'''
if old_empty_status in service:
    service = service.replace(old_empty_status, new_empty_status, 1)


perform = r'''    private fun performScannerSearch() {
        // Barcode/QR/direct URL remains exact and never needs AI vision.
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }

        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
            return
        }
        scannerSearchButton?.isEnabled = false
        scannerSearchButton?.text = "…"
        scannerStatusText?.text = "Mengambil foto objek untuk AI Vision…"

        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar kamera belum siap · fokuskan objek lalu coba lagi"
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
                return@post
            }

            val crop = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForAiVision(crop, 896)
            val localHint = scannerMainProductText
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)

            thread {
                val encoded = runCatching {
                    val output = java.io.ByteArrayOutputStream()
                    check(prepared.compress(Bitmap.CompressFormat.JPEG, 80, output))
                    android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
                }.getOrNull()

                val result = if (encoded.isNullOrBlank()) {
                    Result.failure<AiResponse>(IllegalStateException("Foto kamera gagal disiapkan."))
                } else {
                    AiClient.visionProduct(aiSettings(), encoded, localHint)
                }

                if (prepared !== crop) prepared.recycle()
                if (crop !== frame) crop.recycle()
                frame.recycle()

                handler.post {
                    val response = result.getOrNull()
                    val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                    if (query.isBlank()) {
                        scannerSearchButton?.text = "Cari"
                        scannerSearchButton?.isEnabled = true
                        scannerStatusText?.text = aiVisionFailureMessage(result.exceptionOrNull())
                        scannerResultText?.text = "Foto tidak dikirim ke pencarian sampai AI mengenali objek dengan benar"
                        return@post
                    }

                    scannerSelectedQuery = query
                    scannerSelectedUrl = ""
                    scannerResultText?.text = query
                    scannerStatusText?.text = "AI Vision: $query"
                    searchQuery = query
                    searchUrl = "https://search.brave.com/images?q=${Uri.encode(query)}"
                    stopEmbeddedScanner()
                    showSearchWebPanel()
                    scannerSearchButton?.text = "Cari"
                    scannerSearchButton?.isEnabled = true
                }
            }
        }
    }

    private fun scaleBitmapForAiVision(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun cleanAiVisionSearchQuery(raw: String): String {
        val line = raw.lineSequence()
            .map { it.trim().trim('"', '\'', '`') }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return line
            .replace(Regex("(?i)^(?:query|search query|pencarian|hasil)\\s*:\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
    }

    private fun aiVisionFailureMessage(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains("API key", ignoreCase = true) -> "AI Vision belum bisa dipakai · isi API key di pengaturan"
            message.contains("model", ignoreCase = true) || message.contains("image", ignoreCase = true) ||
                message.contains("vision", ignoreCase = true) -> "Model AI yang dipilih belum mendukung gambar · pilih model vision/multimodal"
            else -> "AI Vision gagal mengenali foto · cek API/model lalu coba lagi"
        }
    }'''

service = replace_block(
    service,
    '    private fun performScannerSearch() {',
    '    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {',
    perform,
    'replace local label search with multimodal AI vision',
)

ai_path.write_text(ai, encoding='utf-8')
service_path.write_text(service, encoding='utf-8')
print('Applied v0.18 v10: camera crop -> configured multimodal AI -> Brave Images. Generic ML labels no longer identify products.')
