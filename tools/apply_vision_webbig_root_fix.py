from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"
SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"


def splice(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f"start marker not found: {label}")
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError(f"end marker not found: {label}")
    return text[:start] + replacement + text[end:]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"marker not found: {label}")
    return text.replace(old, new, 1)


def patch_ai() -> None:
    text = AI.read_text()

    vision_product = r'''    fun visionProduct(
        settings: AiSettings,
        jpegBase64: String,
        localTextHint: String = ""
    ): Result<AiResponse> {
        if (jpegBase64.isBlank()) {
            return Result.failure(IllegalArgumentException("Gambar kamera kosong."))
        }

        // Vision is a capability route, not a text-provider route. Never trust a non-empty
        // answer just because an endpoint returned 200: some text/coding models silently ignore
        // image_url and then describe only the local OCR/color hint. Try every configured vision
        // endpoint and accept only a structured result that proves the model classified the image.
        val providers = buildList {
            add(settings.primaryProvider)
            AiProvider.entries.filter { it != settings.primaryProvider }.forEach(::add)
        }.distinct()

        var lastError: Throwable? = null
        providers.forEach { provider ->
            val attempt = runCatching {
                val raw = when (provider) {
                    AiProvider.OPENROUTER -> requestOpenRouterVision(settings, jpegBase64, "")
                    AiProvider.TABIAI -> requestTabiAiVision(settings, jpegBase64, "")
                    AiProvider.NINEROUTER -> request9RouterVision(settings, jpegBase64, "")
                }
                val query = normalizeVisionResult(raw)
                    ?: throw IllegalStateException("Model ${provider.label} tidak membuktikan bahwa gambar benar-benar dibaca.")
                AiResponse(query, provider)
            }
            attempt.getOrNull()?.let { return Result.success(it) }
            lastError = attempt.exceptionOrNull()
        }

        return Result.failure(lastError ?: IllegalStateException("Tidak ada model vision yang berhasil membaca gambar."))
    }

'''
    text = splice(text, "    fun visionProduct(\n", "    private fun execute(\n", vision_product, "visionProduct")

    vision_helpers = r'''    private fun visionInstruction(localTextHint: String): String = buildString {
        append(
            "Analisis isi gambar yang benar-benar diterima, bukan tebakan dari warna atau teks pendamping. " +
                "LANGKAH PERTAMA wajib menentukan BENTUK/SUBJEK utama: manusia nyata, figur manusia/humanoid, hewan, kendaraan, produk, makanan, tanaman, teks/dokumen, ilustrasi, objek lain, atau adegan. " +
                "Gambar kartun, gambar tangan, poster, mainan, patung, atau karakter bergaya yang jelas berbentuk manusia harus diklasifikasikan sebagai human_figure, bukan sekadar warna/pola. " +
                "Jika manusia nyata terlihat, cukup klasifikasikan sebagai person dan jelaskan ciri visual non-sensitif yang relevan; jangan mencoba menentukan identitas orang. " +
                "Setelah jenis subjek benar, tulis query pencarian visual yang menyebut bentuk/subjek dulu, lalu detail pembeda seperti pose, bagian tubuh/objek, warna, pola, pakaian, logo, tulisan yang benar-benar terbaca, bahan, tekstur, dan konteks. " +
                "Jangan mengarang merek, nama karakter, identitas, atau tulisan yang tidak terlihat. " +
                "Balas HANYA JSON minified tanpa markdown dengan format: " +
                "{\"subject_type\":\"person|human_figure|animal|vehicle|product|food|plant|text|illustration|object|scene|unknown\",\"confidence\":0.0,\"query\":\"...\",\"evidence\":\"...\"}. " +
                "Gunakan unknown jika gambar memang tidak dapat dilihat atau subjek tidak dapat ditentukan."
        )
    }

    private fun normalizeVisionResult(raw: String): String? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        val lower = clean.lowercase()
        if (listOf(
                "cannot see", "can't see", "unable to view", "unable to see",
                "tidak dapat melihat", "tidak bisa melihat", "gambar tidak tersedia",
                "image is not available", "no image"
            ).any(lower::contains)
        ) return null

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        val subject = obj.optString("subject_type").trim().lowercase()
        val confidence = obj.optDouble("confidence", 0.0)
        var query = obj.optString("query").trim().replace(Regex("\\s+"), " ")
        if (subject.isBlank() || subject == "unknown" || query.isBlank() || confidence < 0.30) return null

        val prefix = when (subject) {
            "person" -> "orang/manusia"
            "human_figure" -> "figur manusia/humanoid"
            "animal" -> "hewan"
            "vehicle" -> "kendaraan"
            "product" -> "produk"
            "food" -> "makanan"
            "plant" -> "tanaman"
            "text" -> "teks/dokumen"
            "illustration" -> "ilustrasi"
            "object" -> "objek"
            "scene" -> "adegan"
            else -> return null
        }

        // Reject the exact failure mode seen in the scanner: a supposed vision result that only
        // lists colors/background/frame without naming a semantic subject.
        val generic = setOf(
            "merah", "biru", "hijau", "hitam", "putih", "krem", "abu", "warna",
            "latar", "background", "bingkai", "frame", "tebal", "tipis", "pola",
            "red", "blue", "green", "black", "white", "cream", "gray", "colour", "color"
        )
        val semanticTokens = query.lowercase()
            .split(Regex("[^a-z0-9À-ÿ_/-]+"))
            .filter { it.length >= 3 && it !in generic }
        if (subject !in setOf("text", "scene") && semanticTokens.size < 2) return null

        val subjectWords = prefix.lowercase().split('/', ' ')
        if (subjectWords.none { it.length >= 4 && query.lowercase().contains(it) }) {
            query = "$prefix $query"
        }
        return query.take(280)
    }

'''
    text = splice(text, "    private fun visionInstruction(localTextHint: String): String = buildString {\n", "    private fun requestOpenRouterVision(\n", vision_helpers, "vision instruction")

    # Give the strict JSON response a little more room while remaining inexpensive.
    text = text.replace('.put("max_tokens", 160)', '.put("max_tokens", 220)')
    AI.write_text(text)


def patch_service() -> None:
    text = SERVICE.read_text()

    text = replace_once(
        text,
        "    private var searchWebComposeActive = false\n    private var scannerActive = false",
        "    private var searchWebComposeActive = false\n    private var searchWebBigMode = false\n    private var scannerActive = false",
        "web big state",
    )

    height_block = r'''    private fun searchSurfaceHeightDp(): Int =
        if (searchWebBigMode) webBigSurfaceHeightDp() else scannerSurfaceHeightDp()

    private fun scannerSurfaceHeightDp(): Int {
        val density = resources.displayMetrics.density
        val screenHeightDp = (resources.displayMetrics.heightPixels / density).toInt()
        val screenWidthDp = (resources.displayMetrics.widthPixels / density).toInt()
        return if (isLandscape()) {
            (screenHeightDp * 0.42f).toInt().coerceIn(118, 205)
        } else {
            val proportionalHeight = (screenHeightDp * 0.50f).toInt().coerceIn(270, 480)
            val portraitViewportHeight = screenWidthDp + searchHeaderHeightDp() + 14
            maxOf(proportionalHeight, portraitViewportHeight).coerceAtMost(480)
        }
    }

    private fun webBigSurfaceHeightDp(): Int {
        val density = resources.displayMetrics.density
        val screenHeightDp = (resources.displayMetrics.heightPixels / density).toInt()
        if (isLandscape()) {
            return (screenHeightDp * 0.58f).toInt().coerceIn(155, 285)
        }
        // Web Big is intentionally taller than the camera panel. Keep the keyboard usable while
        // giving the result page most of the remaining portrait screen.
        val target = (screenHeightDp * 0.66f).toInt()
        val maxFit = (screenHeightDp - baseKeyboardHeightDp - brandBarHeightDp() - 10).coerceAtLeast(300)
        return minOf(target, maxFit).coerceIn(300, 560)
    }

'''
    text = splice(text, "    private fun searchSurfaceHeightDp(): Int {\n", "    private fun aiHeaderHeightDp(): Int", height_block, "search surface heights")

    text = replace_once(
        text,
        "    private fun showEmbeddedCameraPanel(resetCandidate: Boolean) {\n        internalGalleryPanel?.release()",
        "    private fun showEmbeddedCameraPanel(resetCandidate: Boolean) {\n        searchWebBigMode = false\n        internalGalleryPanel?.release()",
        "camera switches out of Web Big",
    )
    text = replace_once(
        text,
        "    private fun showInternalGalleryPanel() {\n        if (!::searchSurfaceContent.isInitialized) return",
        "    private fun showInternalGalleryPanel() {\n        searchWebBigMode = false\n        if (!::searchSurfaceContent.isInitialized) return",
        "gallery switches out of Web Big",
    )
    text = replace_once(
        text,
        "    private fun showSearchWebPanel() {\n        internalGalleryPanel?.release()",
        "    private fun showSearchWebPanel() {\n        searchWebBigMode = true\n        internalGalleryPanel?.release()",
        "Web Big mode",
    )
    text = replace_once(
        text,
        "    private fun closeSearchSurface() {\n        searchComposeActive = false",
        "    private fun closeSearchSurface() {\n        searchWebBigMode = false\n        searchComposeActive = false",
        "close Web Big",
    )

    # Use the normal Android Chrome UA. The custom suffix can make Google visual-search serve a
    # degraded/unsupported page inside WebView.
    text = replace_once(
        text,
        '            settings.userAgentString = settings.userAgentString + " AIAdsKeyboard/0.20"',
        '            settings.userAgentString = WebSettings.getDefaultUserAgent(this@RiyanKeyboardService)',
        "webview user agent",
    )

    # Configure cookie continuity BEFORE loading the Lens result. HttpURLConnection performs the
    # image upload, while WebView renders the result; without copying upload cookies the Lens page
    # loses its visual-search session and shows a broken image / generic Google page.
    old_load = '''            loadUrl(searchUrl)\n        }\n        searchWebView = webView\n'''
    new_load = '''        }\n        searchWebView = webView\n        runCatching {\n            val cookieManager = android.webkit.CookieManager.getInstance()\n            cookieManager.setAcceptCookie(true)\n            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {\n                cookieManager.setAcceptThirdPartyCookies(webView, true)\n            }\n        }\n        webView.loadUrl(searchUrl)\n'''
    text = replace_once(text, old_load, new_load, "load Web Big after cookies")

    old_response = '''        val responseCode = connection.responseCode\n        var location = connection.getHeaderField("Location").orEmpty().trim()\n        if (location.isBlank()) {'''
    new_response = '''        val responseCode = connection.responseCode\n        val responseCookies = connection.headerFields.entries\n            .filter { (key, _) -> key?.equals("Set-Cookie", ignoreCase = true) == true }\n            .flatMap { it.value.orEmpty() }\n            .filter { it.isNotBlank() }\n        var location = connection.getHeaderField("Location").orEmpty().trim()\n        if (location.isBlank()) {'''
    text = replace_once(text, old_response, new_response, "capture visual-search cookies")

    old_disconnect = '''        connection.disconnect()\n        if (location.startsWith("/")) {'''
    new_disconnect = '''        val cookieTarget = location.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: endpoint\n        if (responseCookies.isNotEmpty()) {\n            runCatching {\n                val cookieManager = android.webkit.CookieManager.getInstance()\n                cookieManager.setAcceptCookie(true)\n                responseCookies.forEach { cookie -> cookieManager.setCookie(cookieTarget, cookie) }\n                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cookieManager.flush()\n            }\n        }\n        connection.disconnect()\n        if (location.startsWith("/")) {'''
    text = replace_once(text, old_disconnect, new_disconnect, "persist visual-search cookies")

    SERVICE.write_text(text)


def main() -> None:
    patch_ai()
    patch_service()
    print("vision + Web Big root refactor applied")


if __name__ == "__main__":
    main()
