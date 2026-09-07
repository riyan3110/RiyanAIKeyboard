// Smart product recognition + detailed human Vision patch kept separate so existing keyboard features remain untouched.
val patchSmartProductVision = tasks.register("patchSmartProductVision") {
    doLast {
        val aiFile = file("src/main/java/com/riyan/aikeyboard/AiClient.kt")
        var ai = aiFile.readText()

        // All native Vision providers receive the same OCR evidence from the exact crop.
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
                AiResponse(query, provider)"""
        when {
            ai.contains("VisionSearchEvidence.refineQuery(normalizedQuery, localTextHint)") -> Unit
            ai.contains(oldNormalize) -> ai = ai.replace(oldNormalize, newNormalize, ignoreCase = false)
            else -> error("Smart vision normalize patch did not match AiClient.kt")
        }

        // Human images use one shared contract on every multimodal provider: detailed English prompt.
        val oldHumanRule = "Buat query pencarian visual yang pendek, natural, dan faktual: bahasa Indonesia, 3–12 kata, maksimal sekitar 112 karakter. Query hanya boleh berisi subjek utama dan 2–3 ciri paling jelas yang benar-benar terlihat pada foto saat ini. "
        val newHumanRule = "Untuk subject_type person atau human_figure, field query WAJIB berupa detailed English visual-search prompt yang natural, spesifik, dan deskriptif: 8–28 kata, maksimal sekitar 240 karakter. Untuk manusia yang jelas dewasa, gunakan adult woman atau adult man dan jelaskan sebanyak mungkin ciri visual yang benar-benar terlihat: front/rear/side view, pose, hair length/color/texture, outfit color/type/material/fabric/fit, neckline, straps/sleeves, accessories, serta scene/background utama. Bila benar-benar terlihat pada orang dewasa, sertakan visible body silhouette details seperti bare shoulders, visible cleavage, defined waist, curvy hips, exposed thigh through a slit, atau long legs; istilah sexy atau glamorous hanya boleh dipakai bila styling jelas mendukungnya. Gunakan kata detail pakaian seperti satin, silk, leather, denim, lace, bodycon, fitted, tight, slip dress, mini skirt, leggings, shorts, atau thigh-high slit hanya bila tampak pada gambar. Jangan menebak anatomi di balik pakaian dan jangan mengarang bagian tubuh, ukuran, tindakan seksual, identitas, etnisitas, atau detail yang tidak terlihat. Jika usia tidak jelas atau mungkin di bawah 18 tahun, gunakan deskripsi netral tanpa sexy/sexualized wording. Untuk subject non-manusia, tetap gunakan query pendek dan faktual serta pertahankan merek/model/spesifikasi OCR bila benar-benar terbaca. Evidence harus berupa fakta visual konkret; untuk person/human_figure tulis evidence dalam bahasa Inggris. "
        when {
            ai.contains("DETAILED ENGLISH image-search prompt") -> Unit
            ai.contains("detailed English visual-search prompt") -> Unit
            ai.contains(oldHumanRule) -> ai = ai.replace(oldHumanRule, newHumanRule, ignoreCase = false)
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
        val newSemanticThreshold = """        if (subject in setOf("person", "human_figure") && semanticTokens.size < 6) return null
        if (subject !in setOf("text", "scene", "person", "human_figure") && semanticTokens.size < 2) return null"""
        when {
            ai.contains("semanticTokens.size < 6") -> Unit
            ai.contains("detailedEnough = semanticTokens.size >= 6") -> Unit
            ai.contains(oldSemanticThreshold) -> ai = ai.replace(oldSemanticThreshold, newSemanticThreshold, ignoreCase = false)
            else -> error("Human semantic threshold patch did not match AiClient.kt")
        }

        val oldLimits = """        val maxWords = if (subject == "person") 12 else 7
        val maxChars = if (subject == "person") 112 else 64"""
        val newLimits = """        val personLike = subject == "person" || subject == "human_figure"
        val maxWords = if (personLike) 32 else 9
        val maxChars = if (personLike) 320 else 96"""
        when {
            ai.contains("val maxWords = if (personLike) 32 else 9") -> Unit
            ai.contains("val maxWords = if (subject == \"person\") 32 else 7") -> Unit
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
            ai.contains("OCR hint from this same image crop") -> Unit
            ai.contains("Teks OCR lokal berikut hanya evidence tambahan") -> Unit
            ai.contains(instructionMarker) -> ai = ai.replace(instructionMarker, instructionReplacement, ignoreCase = false)
            else -> error("Smart vision instruction patch did not match AiClient.kt")
        }
        aiFile.writeText(ai)

        // AI Horde has no full multimodal LLM, so convert its Alchemy caption/tags with the same
        // detailed English human-prompt contract instead of the older Indonesian compact query.
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

        service = service.replace("AI Ads Keyboard · v0.21.7 test", "AI Ads Keyboard · v0.21.10 test")
        service = service.replace("AI Ads Keyboard · v0.21.8 test", "AI Ads Keyboard · v0.21.10 test")
        service = service.replace("AI Ads Keyboard · v0.21.9 test", "AI Ads Keyboard · v0.21.10 test")
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
