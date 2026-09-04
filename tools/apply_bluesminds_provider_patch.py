from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_CLIENT = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/riyan/aikeyboard/MainActivity.kt"
KEYBOARD_SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"BluesMinds patch marker not found: {label}")
    return text.replace(old, new, 1)


def patch_ai_client() -> None:
    text = AI_CLIENT.read_text()

    text = replace_once(
        text,
        '    NINEROUTER("9router", "9Router");',
        '    NINEROUTER("9router", "9Router"),\n    BLUESMINDS("bluesminds", "BluesMinds");',
        "provider enum",
    )

    text = replace_once(
        text,
        '    val nineRouterModel: String,\n    val fallbackEnabled: Boolean,',
        '    val nineRouterModel: String,\n    val bluesMindsApiKey: String,\n    val bluesMindsBaseUrl: String,\n    val bluesMindsModel: String,\n    val fallbackEnabled: Boolean,',
        "settings fields",
    )

    text = replace_once(
        text,
        '                    AiProvider.NINEROUTER -> request9RouterVision(settings, jpegBase64, localTextHint)\n',
        '                    AiProvider.NINEROUTER -> request9RouterVision(settings, jpegBase64, localTextHint)\n                    AiProvider.BLUESMINDS -> requestBluesMindsVision(settings, jpegBase64, localTextHint)\n',
        "vision provider branch",
    )

    text = replace_once(
        text,
        '                    AiProvider.NINEROUTER -> request9Router(settings, personalizedInstruction, text, temperature, maxTokens)\n',
        '                    AiProvider.NINEROUTER -> request9Router(settings, personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.BLUESMINDS -> requestBluesMinds(settings, personalizedInstruction, text, temperature, maxTokens)\n',
        "text provider branch",
    )

    marker = '    private fun request9Router(\n'
    if 'private fun requestBluesMinds(' not in text:
        if marker not in text:
            raise RuntimeError("BluesMinds patch marker not found: request9Router")
        helper = r'''    private fun requestBluesMinds(
        settings: AiSettings,
        systemInstruction: String,
        text: String,
        temperature: Double,
        maxTokens: Int
    ): String {
        require(settings.bluesMindsApiKey.isNotBlank()) { "API key BluesMinds belum diisi." }
        require(settings.bluesMindsModel.isNotBlank()) { "Model BluesMinds belum diisi." }

        val body = JSONObject()
            .put("model", settings.bluesMindsModel.trim())
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemInstruction))
                    .put(JSONObject().put("role", "user").put("content", text))
            )

        val response = postJson(
            url = bluesMindsChatUrl(settings.bluesMindsBaseUrl),
            headers = mapOf("Authorization" to "Bearer ${settings.bluesMindsApiKey.trim()}"),
            body = body
        )
        return response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    private fun requestBluesMindsVision(
        settings: AiSettings,
        jpegBase64: String,
        localTextHint: String
    ): String {
        require(settings.bluesMindsApiKey.isNotBlank()) { "API key BluesMinds belum diisi." }
        require(settings.bluesMindsModel.isNotBlank()) { "Model BluesMinds belum diisi." }

        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", visionInstruction(localTextHint)))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64"))
            )

        val body = JSONObject()
            .put("model", settings.bluesMindsModel.trim())
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
            url = bluesMindsChatUrl(settings.bluesMindsBaseUrl),
            headers = mapOf("Authorization" to "Bearer ${settings.bluesMindsApiKey.trim()}"),
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
        require(output.isNotBlank()) { "Model BluesMinds tidak mengembalikan hasil vision." }
        return output
    }

    private fun bluesMindsChatUrl(baseUrl: String): String {
        val clean = baseUrl.trim().ifBlank { "https://api.bluesminds.com/v1" }.trimEnd('/')
        require(URL(clean).protocol.equals("https", ignoreCase = true)) {
            "Base URL BluesMinds harus memakai HTTPS."
        }
        return when {
            clean.endsWith("/chat/completions", ignoreCase = true) -> clean
            clean.endsWith("/v1", ignoreCase = true) -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

'''
        text = text.replace(marker, helper + marker, 1)

    AI_CLIENT.write_text(text)


def patch_main_activity() -> None:
    text = MAIN_ACTIVITY.read_text()

    text = replace_once(
        text,
        '                    AiProvider.NINEROUTER -> "9Router (OpenAI-compatible)"\n                    else -> it.label',
        '                    AiProvider.NINEROUTER -> "9Router (OpenAI-compatible)"\n                    AiProvider.BLUESMINDS -> "BluesMinds (OpenAI-compatible)"\n                    else -> it.label',
        "provider label",
    )

    marker = '        val fallback = CheckBox(this).apply {\n'
    if 'sectionTitle("BluesMinds")' not in text:
        if marker not in text:
            raise RuntimeError("BluesMinds patch marker not found: fallback")
        section = '''        root.addView(sectionTitle("BluesMinds"))
        val bluesMindsKey = secretField("API key BluesMinds", prefs.getString("bluesminds_api_key", ""))
        val bluesMindsBaseUrl = textField("Base URL BluesMinds", prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1"))
        val bluesMindsModel = textField("Model BluesMinds", prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash"))
        root.addView(bluesMindsKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(bluesMindsBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(bluesMindsModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("OpenAI-compatible · /v1/chat/completions · Authorization: Bearer. Model harus sesuai nama model yang tersedia di akun BluesMinds."))

'''
        text = text.replace(marker, section + marker, 1)

    text = replace_once(
        text,
        '                    .putString("9router_model", nineRouterModel.text.toString().trim().ifBlank { "cc/claude-sonnet-4-20250514" })\n                    .putString("reference_urls", referenceUrls.text.toString().trim())',
        '                    .putString("9router_model", nineRouterModel.text.toString().trim().ifBlank { "cc/claude-sonnet-4-20250514" })\n                    .putString("bluesminds_api_key", bluesMindsKey.text.toString().trim())\n                    .putString("bluesminds_base_url", bluesMindsBaseUrl.text.toString().trim().ifBlank { "https://api.bluesminds.com/v1" })\n                    .putString("bluesminds_model", bluesMindsModel.text.toString().trim().ifBlank { "deepseek-ai/deepseek-v4-flash" })\n                    .putString("reference_urls", referenceUrls.text.toString().trim())',
        "save settings",
    )

    MAIN_ACTIVITY.write_text(text)


def patch_keyboard_service() -> None:
    text = KEYBOARD_SERVICE.read_text()
    text = replace_once(
        text,
        '            nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),\n            fallbackEnabled = prefs.getBoolean("fallback_enabled", false),',
        '            nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),\n            bluesMindsApiKey = prefs.getString("bluesminds_api_key", "").orEmpty(),\n            bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),\n            bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),\n            fallbackEnabled = prefs.getBoolean("fallback_enabled", false),',
        "service settings",
    )
    KEYBOARD_SERVICE.write_text(text)


def main() -> None:
    patch_ai_client()
    patch_main_activity()
    patch_keyboard_service()
    print("BluesMinds provider patch applied")


if __name__ == "__main__":
    main()
