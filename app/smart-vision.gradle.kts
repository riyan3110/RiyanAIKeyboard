// Smart product recognition patch kept separate so existing keyboard features remain untouched.
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
                AiResponse(query, provider)"""
        when {
            ai.contains("VisionSearchEvidence.refineQuery(normalizedQuery, localTextHint)") -> Unit
            ai.contains(oldNormalize) -> ai = ai.replace(oldNormalize, newNormalize, ignoreCase = false)
            else -> error("Smart vision normalize patch did not match AiClient.kt")
        }

        val instructionMarker = """                "Gunakan unknown jika gambar memang tidak dapat dilihat atau subjek tidak dapat ditentukan."
        )
    }"""
        val instructionReplacement = """                "Gunakan unknown jika gambar memang tidak dapat dilihat atau subjek tidak dapat ditentukan."
        )
        if (localTextHint.isNotBlank()) {
            append(" Teks OCR lokal berikut hanya evidence tambahan dari gambar yang sama: ")
            append(localTextHint.take(700))
            append(". Untuk produk berlabel, prioritaskan merek, nama/model, kode varian, ukuran, dan spesifikasi yang terbaca jelas pada label; jangan biarkan latar seperti kursi/sofa mengalahkan identitas produk. Jangan mengarang teks yang tidak ada.")
        }
    }"""
        when {
            ai.contains("Teks OCR lokal berikut hanya evidence tambahan") -> Unit
            ai.contains(instructionMarker) -> ai = ai.replace(instructionMarker, instructionReplacement, ignoreCase = false)
            else -> error("Smart vision instruction patch did not match AiClient.kt")
        }
        aiFile.writeText(ai)

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

        service = service.replace("AI Ads Keyboard · v0.21.7 test", "AI Ads Keyboard · v0.21.8 test")
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
