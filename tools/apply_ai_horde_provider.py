from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


default_models = "aphrodite/TheDrummer/Cydonia-24B-v4.3,koboldcpp/DarkIdol-Llama-3.1-8B-Instruct-1.2-Uncensored.Q8_0"

# AiClient.kt
path = Path("app/src/main/java/com/riyan/aikeyboard/AiClient.kt")
text = path.read_text()
if 'AIHORDE("aihorde", "AI Horde")' not in text:
    text = replace_once(
        text,
        '    ORCAROUTER("orcarouter", "OrcaRouter");',
        '    ORCAROUTER("orcarouter", "OrcaRouter"),\n    AIHORDE("aihorde", "AI Horde");',
        "provider enum",
    )
    text = replace_once(
        text,
        '    val orcaRouterApiKey: String,\n    val orcaRouterBaseUrl: String,\n    val orcaRouterModel: String,\n    val fallbackEnabled: Boolean,',
        '    val orcaRouterApiKey: String,\n    val orcaRouterBaseUrl: String,\n    val orcaRouterModel: String,\n    val aiHordeApiKey: String,\n    val aiHordeModel: String,\n    val fallbackEnabled: Boolean,',
        "settings fields",
    )
    text = replace_once(
        text,
        '                    AiProvider.ORCAROUTER -> requestCompatibleVision(settings.orcaRouterApiKey, settings.orcaRouterBaseUrl, settings.orcaRouterModel, "OrcaRouter", jpegBase64, localTextHint)\n',
        '                    AiProvider.ORCAROUTER -> requestCompatibleVision(settings.orcaRouterApiKey, settings.orcaRouterBaseUrl, settings.orcaRouterModel, "OrcaRouter", jpegBase64, localTextHint)\n                    AiProvider.AIHORDE -> throw IllegalStateException("AI Horde saat ini dipakai untuk chat; Vision otomatis mencoba provider vision berikutnya.")\n',
        "vision provider branch",
    )
    text = replace_once(
        text,
        '                    AiProvider.ORCAROUTER -> requestCompatibleChat(settings.orcaRouterApiKey, settings.orcaRouterBaseUrl, settings.orcaRouterModel, "OrcaRouter", personalizedInstruction, text, temperature, maxTokens)\n',
        '                    AiProvider.ORCAROUTER -> requestCompatibleChat(settings.orcaRouterApiKey, settings.orcaRouterBaseUrl, settings.orcaRouterModel, "OrcaRouter", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.AIHORDE -> requestAiHordeChat(settings, personalizedInstruction, text, temperature, maxTokens)\n',
        "chat provider branch",
    )

    horde_function = r'''
    private fun requestAiHordeChat(
        settings: AiSettings,
        systemInstruction: String,
        text: String,
        temperature: Double,
        maxTokens: Int
    ): String {
        val apiKey = settings.aiHordeApiKey.trim().ifBlank { "0000000000" }
        val modelNames = settings.aiHordeModel
            .split(',', '\n')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        require(modelNames.isNotEmpty()) { "Model AI Horde belum diisi." }

        val prompt = buildString {
            append("[System]\n")
            append(systemInstruction)
            append("\n\n[User]\n")
            append(text.takeLast(24_000))
            append("\n\n[Assistant]\n")
        }
        val models = JSONArray().apply { modelNames.forEach(::put) }
        val params = JSONObject()
            .put("max_length", maxTokens.coerceIn(64, 1024))
            .put("max_context_length", 8192)
            .put("temperature", temperature.coerceIn(0.1, 1.5))
            .put("top_p", 0.95)
            .put("n", 1)
        val body = JSONObject()
            .put("prompt", prompt)
            .put("params", params)
            .put("models", models)
            .put("slow_workers", true)
            .put("trusted_workers", false)

        val headers = mapOf(
            "apikey" to apiKey,
            "Client-Agent" to "AI-Ads-Keyboard:0.21:https://github.com/riyan3110/RiyanAIKeyboard"
        )
        val submitted = postJson(
            url = "https://aihorde.net/api/v2/generate/text/async",
            headers = headers,
            body = body
        )
        val requestId = submitted.optString("id").trim()
        require(requestId.isNotBlank()) {
            submitted.optString("message").ifBlank { "AI Horde tidak mengembalikan ID antrean." }
        }

        val deadline = System.currentTimeMillis() + 120_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1_500L)
            val status = getJson(
                "https://aihorde.net/api/v2/generate/text/status/$requestId",
                headers
            )
            if (status.optBoolean("faulted", false)) {
                error("AI Horde gagal memproses permintaan.")
            }
            if (!status.optBoolean("is_possible", true) &&
                status.optInt("processing", 0) == 0 &&
                status.optInt("waiting", 0) == 0
            ) {
                error("Model AI Horde sedang tidak tersedia di worker komunitas.")
            }
            if (status.optBoolean("done", false)) {
                val generations = status.optJSONArray("generations")
                if (generations != null) {
                    for (index in 0 until generations.length()) {
                        val generation = generations.optJSONObject(index) ?: continue
                        val output = generation.optString("text").trim()
                        if (output.isNotBlank()) return output
                    }
                }
                error("AI Horde selesai tetapi tidak mengembalikan teks.")
            }
        }
        error("AI Horde masih antre terlalu lama. Coba lagi atau aktifkan fallback provider.")
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
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = extractError(raw)
                error(message ?: "Permintaan AI Horde gagal ($status).")
            }
            require(raw.isNotBlank()) { "AI Horde mengembalikan respons kosong." }
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

'''
    text = replace_once(
        text,
        '    private fun requestOpenRouterVision(\n',
        horde_function + '    private fun requestOpenRouterVision(\n',
        "AI Horde request functions",
    )
    path.write_text(text)

# MainActivity.kt
path = Path("app/src/main/java/com/riyan/aikeyboard/MainActivity.kt")
text = path.read_text()
if 'sectionTitle("AI Horde")' not in text:
    orca_block = '''        root.addView(sectionTitle("OrcaRouter"))
        val orcaRouterKey = secretField("API key OrcaRouter", prefs.getString("orcarouter_api_key", ""))
        val orcaRouterBaseUrl = textField("Base URL OrcaRouter", prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1"))
        val orcaRouterModel = textField("Model OrcaRouter", prefs.getString("orcarouter_model", "orcarouter/free"))
        root.addView(orcaRouterKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(orcaRouterBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(orcaRouterModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("OpenAI-compatible · model bebas diganti sesuai katalog OrcaRouter."))
'''
    horde_block = orca_block + f'''
        root.addView(sectionTitle("AI Horde"))
        val aiHordeKey = secretField("API key AI Horde (kosong = anonim)", prefs.getString("aihorde_api_key", ""))
        val aiHordeModel = textField("Model AI Horde", prefs.getString("aihorde_model", "{default_models}"))
        root.addView(aiHordeKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(aiHordeModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("Gratis berbasis worker komunitas. API key boleh dikosongkan untuk akun anonim 0000000000; akun terdaftar mendapat prioritas lebih baik. Beberapa model dapat dipisahkan dengan koma sebagai fallback."))
'''
    text = replace_once(text, orca_block, horde_block, "MainActivity AI Horde fields")
    text = replace_once(
        text,
        '                    .putString("orcarouter_model", orcaRouterModel.text.toString().trim().ifBlank { "orcarouter/free" })\n                    .putString("reference_urls", referenceUrls.text.toString().trim())',
        f'                    .putString("orcarouter_model", orcaRouterModel.text.toString().trim().ifBlank {{ "orcarouter/free" }})\n                    .putString("aihorde_api_key", aiHordeKey.text.toString().trim())\n                    .putString("aihorde_model", aiHordeModel.text.toString().trim().ifBlank {{ "{default_models}" }})\n                    .putString("reference_urls", referenceUrls.text.toString().trim())',
        "MainActivity save prefs",
    )
    path.write_text(text)

# KeyboardSettingsOverlay.kt
path = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")
text = path.read_text()
if 'var aiHordeKey: String' not in text:
    text = replace_once(
        text,
        '        var orcaRouterKey: String,\n        var orcaRouterBaseUrl: String,\n        var orcaRouterModel: String,\n        var fallbackEnabled: Boolean,',
        '        var orcaRouterKey: String,\n        var orcaRouterBaseUrl: String,\n        var orcaRouterModel: String,\n        var aiHordeKey: String,\n        var aiHordeModel: String,\n        var fallbackEnabled: Boolean,',
        "overlay draft fields",
    )
    text = replace_once(
        text,
        '        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS, AiProvider.XKIRO, AiProvider.ORCAROUTER)',
        '        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS, AiProvider.XKIRO, AiProvider.ORCAROUTER, AiProvider.AIHORDE)',
        "overlay provider list",
    )
    text = replace_once(
        text,
        '''            AiProvider.ORCAROUTER -> {
                config.addView(textInput("API Key OrcaRouter", draft.orcaRouterKey, secret = true) { draft.orcaRouterKey = it })
                config.addView(textInput("Base URL OrcaRouter", draft.orcaRouterBaseUrl) { draft.orcaRouterBaseUrl = it })
                config.addView(textInput("Nama Model", draft.orcaRouterModel) { draft.orcaRouterModel = it })
            }
''',
        f'''            AiProvider.ORCAROUTER -> {{
                config.addView(textInput("API Key OrcaRouter", draft.orcaRouterKey, secret = true) {{ draft.orcaRouterKey = it }})
                config.addView(textInput("Base URL OrcaRouter", draft.orcaRouterBaseUrl) {{ draft.orcaRouterBaseUrl = it }})
                config.addView(textInput("Nama Model", draft.orcaRouterModel) {{ draft.orcaRouterModel = it }})
            }}
            AiProvider.AIHORDE -> {{
                config.addView(textInput("API Key AI Horde (kosong = anonim)", draft.aiHordeKey, secret = true) {{ draft.aiHordeKey = it }})
                config.addView(textInput("Model / fallback model", draft.aiHordeModel) {{ draft.aiHordeModel = it }})
                config.addView(description("Gratis berbasis worker komunitas. Pisahkan beberapa model dengan koma. Vision tetap memakai provider vision lain sebagai fallback."))
            }}
''',
        "overlay config branch",
    )
    text = replace_once(
        text,
        '            .putString("orcarouter_model", draft.orcaRouterModel.trim().ifBlank { "orcarouter/free" })\n            .putBoolean("fallback_enabled", draft.fallbackEnabled)',
        f'            .putString("orcarouter_model", draft.orcaRouterModel.trim().ifBlank {{ "orcarouter/free" }})\n            .putString("aihorde_api_key", draft.aiHordeKey.trim())\n            .putString("aihorde_model", draft.aiHordeModel.trim().ifBlank {{ "{default_models}" }})\n            .putBoolean("fallback_enabled", draft.fallbackEnabled)',
        "overlay save prefs",
    )
    text = replace_once(
        text,
        '        orcaRouterKey = prefs.getString("orcarouter_api_key", "").orEmpty(),\n        orcaRouterBaseUrl = prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1").orEmpty(),\n        orcaRouterModel = prefs.getString("orcarouter_model", "orcarouter/free").orEmpty(),\n        fallbackEnabled = prefs.getBoolean("fallback_enabled", false),',
        f'        orcaRouterKey = prefs.getString("orcarouter_api_key", "").orEmpty(),\n        orcaRouterBaseUrl = prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1").orEmpty(),\n        orcaRouterModel = prefs.getString("orcarouter_model", "orcarouter/free").orEmpty(),\n        aiHordeKey = prefs.getString("aihorde_api_key", "").orEmpty(),\n        aiHordeModel = prefs.getString("aihorde_model", "{default_models}").orEmpty(),\n        fallbackEnabled = prefs.getBoolean("fallback_enabled", false),',
        "overlay load draft",
    )
    text = replace_once(
        text,
        '        orcaRouterKey = draft.orcaRouterKey,\n        orcaRouterBaseUrl = "https://api.orcarouter.ai/v1",\n        orcaRouterModel = "orcarouter/free",\n        fallbackEnabled = false,',
        f'        orcaRouterKey = draft.orcaRouterKey,\n        orcaRouterBaseUrl = "https://api.orcarouter.ai/v1",\n        orcaRouterModel = "orcarouter/free",\n        aiHordeKey = draft.aiHordeKey,\n        aiHordeModel = "{default_models}",\n        fallbackEnabled = false,',
        "overlay reset draft",
    )
    text = replace_once(
        text,
        '        AiProvider.XKIRO -> "xKiro"\n        AiProvider.ORCAROUTER -> "OrcaRouter"\n',
        '        AiProvider.XKIRO -> "xKiro"\n        AiProvider.ORCAROUTER -> "OrcaRouter"\n        AiProvider.AIHORDE -> "AI Horde"\n',
        "overlay provider label",
    )
    path.write_text(text)

# RiyanKeyboardService.kt
path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = path.read_text()
if 'aiHordeApiKey =' not in text:
    text = replace_once(
        text,
        '            orcaRouterApiKey = prefs.getString("orcarouter_api_key", "").orEmpty(),\n            orcaRouterBaseUrl = prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1").orEmpty(),\n            orcaRouterModel = prefs.getString("orcarouter_model", "orcarouter/free").orEmpty(),\n            fallbackEnabled = prefs.getBoolean("fallback_enabled", false),',
        f'            orcaRouterApiKey = prefs.getString("orcarouter_api_key", "").orEmpty(),\n            orcaRouterBaseUrl = prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1").orEmpty(),\n            orcaRouterModel = prefs.getString("orcarouter_model", "orcarouter/free").orEmpty(),\n            aiHordeApiKey = prefs.getString("aihorde_api_key", "").orEmpty(),\n            aiHordeModel = prefs.getString("aihorde_model", "{default_models}").orEmpty(),\n            fallbackEnabled = prefs.getBoolean("fallback_enabled", false),',
        "service AiSettings",
    )
    path.write_text(text)
