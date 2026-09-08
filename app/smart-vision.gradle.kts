// Smart product recognition + detailed human Vision patch kept separate so existing keyboard features remain untouched.
val patchSmartProductVision = tasks.register("patchSmartProductVision") {
    doLast {
        val aiFile = file("src/main/java/com/riyan/aikeyboard/AiClient.kt")
        var ai = aiFile.readText()

        ai = ai.replace(
            "requestOpenRouterVision(settings, jpegBase64, \"\")",
            "requestOpenRouterVision(settings, jpegBase64, localTextHint)"
        )
        ai = ai.replace(
            "requestTabiAiVision(settings, jpegBase64, \"\")",
            "requestTabiAiVision(settings, jpegBase64, localTextHint)"
        )
        ai = ai.replace(
            "request9RouterVision(settings, jpegBase64, \"\")",
            "request9RouterVision(settings, jpegBase64, localTextHint)"
        )

        val oldNormalize = """                val query = normalizeVisionResult(raw)
                    ?: throw IllegalStateException("Model ${'$'}{provider.label} tidak membuktikan bahwa gambar benar-benar dibaca.")
                AiResponse(query, provider)"""
        val newNormalize = """                val normalizedQuery = normalizeVisionResult(raw)
                    ?: throw IllegalStateException("Model ${'$'}{provider.label} tidak membuktikan bahwa gambar benar-benar dibaca.")
                val query = VisionSearchEvidence.refineQuery(normalizedQuery, localTextHint)
                if (!HumanVisionValidator.isAcceptable(query)) {
                    throw IllegalStateException("Model ${'$'}{provider.label} memberi deskripsi manusia terlalu generik; coba provider Vision berikutnya.")
                }
                AiResponse(query, provider)"""
        when {
            ai.contains("HumanVisionValidator.isAcceptable(query)") -> Unit
            ai.contains("VisionSearchEvidence.refineQuery(it, localTextHint)") -> Unit
            ai.contains(oldNormalize) -> ai = ai.replace(oldNormalize, newNormalize, ignoreCase = false)
            else -> error("Smart vision normalize patch did not match AiClient.kt")
        }

        val oldHumanRule = "Buat query pencarian visual yang pendek, natural, dan faktual: bahasa Indonesia, 3–12 kata, maksimal sekitar 112 karakter. Query hanya boleh berisi subjek utama dan 2–3 ciri paling jelas yang benar-benar terlihat pada foto saat ini. "
        val newHumanRule = "Untuk subject_type person atau human_figure, field query WAJIB berupa satu kalimat English visual-search prompt yang natural, spesifik, dan menggambarkan foto saat ini secara unik: idealnya 28–52 kata, maksimal sekitar 520 karakter. Jangan menghasilkan daftar keyword yang patah-patah, jangan memakai susunan generik yang sama untuk foto berbeda, dan jangan menambahkan kata pencarian teknis seperti real photo, photography, -AI, -AI-generated, -Midjourney, -Stable-Diffusion, -render, -CGI, atau filter mesin pencari lain ke field query. Untuk manusia yang JELAS DEWASA, gunakan adult woman atau adult man dan jangan berhenti pada deskripsi generik. Jelaskan ciri yang benar-benar terlihat: camera/view angle dan framing, pose/movement, hair, facial expression jika tampak, upper garment dan lower garment secara terpisah, warna, material/fabric, fit/cut, accessories, bentuk tubuh dan bagian tubuh yang tampak seperti shoulders, arms, breasts, cleavage, abdomen, waist, hips, buttocks, thighs, legs, atau feet, lalu scene/background. Jika foto seksual orang dewasa memang menampilkan anatomi atau nudity, gunakan istilah langsung dan tepat seperti breasts, nipples, buttocks, vulva, penis, nude, atau topless HANYA jika benar-benar terlihat dan istilah yang sama ada di evidence; jangan menyensor fakta visual. Jika tubuh hanya terbentuk melalui pakaian, jelaskan pakaian dan siluetnya, bukan menganggapnya telanjang. Jangan mengarang hidden anatomy, sexual acts, ukuran/bentuk tubuh yang tidak terlihat, identitas, atau etnisitas. Jika usia tidak jelas atau mungkin di bawah 18 tahun, wajib gunakan deskripsi netral tanpa sexualized wording. Untuk subject product, query idealnya 22–45 kata dan wajib mempertahankan kategori, merek, lini, model/varian, bentuk/bahan/warna kemasan, ukuran/volume, spesifikasi, label, sudut, kondisi, dan latar yang benar-benar terbaca atau terlihat. Evidence harus berisi 6–12 fakta visual konkret dan konsisten dengan query. "
        when {
            ai.contains("ONE shared adult-human contract") -> Unit
            ai.contains("Untuk subjek yang PASTI dewasa (18+)") -> Unit
            ai.contains(oldHumanRule) -> {
                ai = ai.replace(oldHumanRule, newHumanRule, ignoreCase = false)
                ai = ai.replace(
                    "Analisis isi gambar yang benar-benar diterima, bukan tebakan dari warna atau teks pendamping. ",
                    "Analisis isi gambar yang benar-benar diterima, bukan tebakan dari warna atau teks pendamping. ONE shared adult-human contract. "
                )
            }
            else -> error("Detailed human Vision instruction patch did not match AiClient.kt")
        }

        val oldPersonNormalizer = """        if (subject == "person") query = normalizePersonQueryLabel(query)"""
        val newPersonNormalizer = """        if (subject == "person" || subject == "human_figure") query = query.replace(Regex("\\s+"), " ").trim()"""
        when {
            ai.contains(newPersonNormalizer) -> Unit
            ai.contains(oldPersonNormalizer) -> ai = ai.replace(oldPersonNormalizer, newPersonNormalizer, ignoreCase = false)
            else -> error("Person query normalizer patch did not match AiClient.kt")
        }

        ai = ai.replace("            \"person\" -> preferredPersonPrefix(query)", "            \"person\" -> \"\"")
        ai = ai.replace("            \"human_figure\" -> \"figur manusia/humanoid\"", "            \"human_figure\" -> \"\"")

        val oldSemanticThreshold = """        if (subject !in setOf("text", "scene") && semanticTokens.size < 2) return null"""
        val newSemanticThreshold = """        if (subject in setOf("person", "human_figure") && semanticTokens.size < 8) return null
        if (subject !in setOf("text", "scene", "person", "human_figure") && semanticTokens.size < 2) return null"""
        when {
            ai.contains("semanticTokens.size < 8") -> Unit
            ai.contains(oldSemanticThreshold) -> ai = ai.replace(oldSemanticThreshold, newSemanticThreshold, ignoreCase = false)
            else -> error("Human semantic threshold patch did not match AiClient.kt")
        }

        val oldLimits = """        val maxWords = if (subject == "person") 12 else 7
        val maxChars = if (subject == "person") 112 else 64"""
        val newLimits = """        val personLike = subject == "person" || subject == "human_figure"
        val maxWords = if (personLike) 52 else 45
        val maxChars = if (personLike) 520 else 460"""
        when {
            ai.contains("val maxWords = if (personLike) 52 else 45") -> Unit
            ai.contains("personLike -> 52") && ai.contains("subject == \"product\" -> 45") -> Unit
            ai.contains(oldLimits) -> ai = ai.replace(oldLimits, newLimits, ignoreCase = false)
            else -> error("Human query length patch did not match AiClient.kt")
        }

        val instructionMarker = """                "Gunakan unknown jika gambar memang tidak dapat dilihat atau subjek tidak dapat ditentukan."
        )
    }"""
        val instructionReplacement = """                "Gunakan unknown jika gambar memang tidak dapat dilihat atau subjek tidak dapat ditentukan."
        )
        if (localTextHint.isNotBlank()) {
            append(" Teks OCR lokal berikut hanya evidence tambahan dari gambar yang sama: ")
            append(localTextHint.take(700))
            append(". Untuk produk berlabel, prioritaskan merek, nama/model, kode varian, ukuran, dan spesifikasi yang terbaca jelas pada label; jangan biarkan latar seperti kursi/sofa mengalahkan identitas produk. Untuk foto manusia, abaikan OCR latar yang tidak menempel pada pakaian/objek utama. Jangan mengarang teks yang tidak ada.")
        }
    }"""
        when {
            ai.contains("Teks OCR lokal berikut hanya evidence tambahan") -> Unit
            ai.contains("Bukti OCR/konteks lokal dari gambar") -> Unit
            ai.contains(instructionMarker) -> ai = ai.replace(instructionMarker, instructionReplacement, ignoreCase = false)
            else -> error("Smart vision instruction patch did not match AiClient.kt")
        }
        aiFile.writeText(ai)

        val hordeFile = file("src/main/java/com/riyan/aikeyboard/AiHordeAlchemyVision.kt")
        var horde = hordeFile.readText()
        horde = horde.replace(
            "buildPersonQuery(rawCaption, parsed.tags, ageUnclear)",
            "HumanVisionPrompt.fromAlchemy(rawCaption, parsed.tags, ageUnclear)"
        )
        hordeFile.writeText(horde)

        val serviceFile = file("src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
        var service = serviceFile.readText()
        val oldGallery = """            val visualUrl: String? = null // Brave Search remains the embedded search surface.
            val result = if (encoded.isNullOrBlank()) {
                Result.failure<AiResponse>(IllegalStateException("Gambar galeri gagal disiapkan."))
            } else {
                AiClient.visionProduct(aiSettings(), encoded, "")
            }"""
        val newGallery = """            val galleryLocalHint = runCatching {
                VisionSearchEvidence.recognizeText(scannerTextRecognizer, prepared)
            }.getOrDefault("")
            val visualUrl: String? = null // Brave Search remains the embedded search surface.
            val result = if (encoded.isNullOrBlank()) {
                Result.failure<AiResponse>(IllegalStateException("Gambar galeri gagal disiapkan."))
            } else {
                AiClient.visionProduct(aiSettings(), encoded, galleryLocalHint)
            }"""
        when {
            service.contains("VisionSearchEvidence.recognizeText(scannerTextRecognizer, prepared)") -> Unit
            service.contains(oldGallery) -> service = service.replace(oldGallery, newGallery, ignoreCase = false)
            else -> error("Gallery OCR patch did not match RiyanKeyboardService.kt")
        }

        val cleanerMarker = "    private fun cleanAiVisionSearchQuery(raw: String): String {"
        val cleanerStart = service.indexOf(cleanerMarker)
        if (cleanerStart < 0) error("AI Vision cleaner start marker not found")
        val cleanerEnd = service.indexOf("\n    private fun ", cleanerStart + cleanerMarker.length)
        if (cleanerEnd < 0) error("AI Vision cleaner end marker not found")
        val currentCleaner = service.substring(cleanerStart, cleanerEnd)
        val relaxedCleaner = currentCleaner
            .replace(
                """val filler = setOf("the", "a", "an", "and", "in", "with", "setting", "of", "at", "on", "yang", "sedang", "terlihat")""",
                """val filler = setOf("yang", "sedang", "terlihat")"""
            )
            .replace(".take(7)", ".take(52)")
            .replace(".take(64)", ".take(520)")
        service = service.substring(0, cleanerStart) + relaxedCleaner + service.substring(cleanerEnd)

        // Keep the prompt readable and strip every technical suffix before opening image search.
        val imageMarker = "private fun selectedImageSearchUrl(query: String): String {"
        val imageStart = service.indexOf(imageMarker)
        if (imageStart < 0) error("Image search URL function start marker not found")
        val imageEnd = service.indexOf("\n    private fun ", imageStart + imageMarker.length)
        .takeIf { it >= 0 } ?: service.indexOf("\nprivate fun ", imageStart + imageMarker.length)
        if (imageEnd < 0) error("Image search URL function end marker not found")
        val replacementImageFunction = """private fun selectedImageSearchUrl(query: String): String {
    val clean = query
        .replace(Regex("(?i)\\breal\\s+photo(?:graphy)?\\b|\\bphotography\\b|-(?:AI(?:-generated)?|illustration|render|CGI|Midjourney|Stable-Diffusion)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', ',', '.', ';')
    val human = Regex(
        "\\b(adult woman|adult man|woman|women|female|man|men|male|person|human figure)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(clean)
    val searchText = if (human) "${'$'}clean photo" else clean
    val encoded = Uri.encode(searchText)
    return when (selectedSearchEngineId()) {
        "google" -> "https://www.google.com/search?tbm=isch&q=${'$'}encoded"
        "bing" -> "https://www.bing.com/images/search?q=${'$'}encoded"
        "ddg" -> "https://duckduckgo.com/?q=${'$'}encoded&iax=images&ia=images"
        else -> "https://search.brave.com/images?q=${'$'}encoded&source=web"
    }
}"""
        service = service.substring(0, imageStart) + replacementImageFunction + service.substring(imageEnd)

        service = service.replace("AI Ads Keyboard · v0.21.7 test", "AI Ads Keyboard · v0.21.13 test")
        service = service.replace("AI Ads Keyboard · v0.21.8 test", "AI Ads Keyboard · v0.21.13 test")
        service = service.replace("AI Ads Keyboard · v0.21.9 test", "AI Ads Keyboard · v0.21.13 test")
        service = service.replace("AI Ads Keyboard · v0.21.10 test", "AI Ads Keyboard · v0.21.13 test")
        service = service.replace("AI Ads Keyboard · v0.21.11 test", "AI Ads Keyboard · v0.21.13 test")
        service = service.replace("AI Ads Keyboard · v0.21.12 test", "AI Ads Keyboard · v0.21.13 test")
        serviceFile.writeText(service)
    }
}

patchSmartProductVision.configure {
    mustRunAfter("patchDynamicImeAction")
    mustRunAfter("patchBluesMindsProvider")
    mustRunAfter("patchSettingsInputAndGallery")
    mustRunAfter("patchReferenceBrandingAndKeyCentering")
    mustRunAfter("patchVisibleVersionLabel")
}

tasks.named("preBuild").configure {
    dependsOn(patchSmartProductVision)
}
