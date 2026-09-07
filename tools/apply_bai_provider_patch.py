from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_CLIENT = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"
KEYBOARD_SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
SETTINGS_OVERLAY = ROOT / "app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/riyan/aikeyboard/MainActivity.kt"


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    if old not in source:
        raise RuntimeError(f"B.AI provider patch marker not found: {label}")
    return source.replace(old, new, 1)


def patch_ai_client() -> None:
    text = AI_CLIENT.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '    BLUESMINDS("bluesminds", "BluesMinds"),\n    XKIRO("xkiro", "xKiro"),',
        '    BLUESMINDS("bluesminds", "BluesMinds"),\n    BAI("bai", "B.AI"),\n    XKIRO("xkiro", "xKiro"),',
        "AiProvider enum",
    )

    text = replace_once(
        text,
        '''    val bluesMindsApiKey: String,\n    val bluesMindsBaseUrl: String,\n    val bluesMindsModel: String,\n    val xKiroApiKey: String,''',
        '''    val bluesMindsApiKey: String,\n    val bluesMindsBaseUrl: String,\n    val bluesMindsModel: String,\n    val bAiApiKey: String,\n    val bAiBaseUrl: String,\n    val bAiModel: String,\n    val xKiroApiKey: String,''',
        "AiSettings fields",
    )

    text = replace_once(
        text,
        '''                    AiProvider.BLUESMINDS -> requestCompatibleVision(settings.bluesMindsApiKey, settings.bluesMindsBaseUrl, settings.bluesMindsModel, "BluesMinds", jpegBase64, localTextHint)\n                    AiProvider.XKIRO ->''',
        '''                    AiProvider.BLUESMINDS -> requestCompatibleVision(settings.bluesMindsApiKey, settings.bluesMindsBaseUrl, settings.bluesMindsModel, "BluesMinds", jpegBase64, localTextHint)\n                    AiProvider.BAI -> requestCompatibleVision(settings.bAiApiKey, settings.bAiBaseUrl, settings.bAiModel, "B.AI", jpegBase64, localTextHint)\n                    AiProvider.XKIRO ->''',
        "vision dispatch",
    )

    text = replace_once(
        text,
        '''                    AiProvider.BLUESMINDS -> requestCompatibleChat(settings.bluesMindsApiKey, settings.bluesMindsBaseUrl, settings.bluesMindsModel, "BluesMinds", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.XKIRO ->''',
        '''                    AiProvider.BLUESMINDS -> requestCompatibleChat(settings.bluesMindsApiKey, settings.bluesMindsBaseUrl, settings.bluesMindsModel, "BluesMinds", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.BAI -> requestCompatibleChat(settings.bAiApiKey, settings.bAiBaseUrl, settings.bAiModel, "B.AI", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.XKIRO ->''',
        "chat dispatch",
    )

    AI_CLIENT.write_text(text, encoding="utf-8")


def patch_keyboard_service() -> None:
    text = KEYBOARD_SERVICE.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''            bluesMindsApiKey = prefs.getString("bluesminds_api_key", "").orEmpty(),\n            bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),\n            bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),\n            xKiroApiKey =''',
        '''            bluesMindsApiKey = prefs.getString("bluesminds_api_key", "").orEmpty(),\n            bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),\n            bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),\n            bAiApiKey = prefs.getString("bai_api_key", "").orEmpty(),\n            bAiBaseUrl = prefs.getString("bai_base_url", "https://api.b.ai/v1").orEmpty(),\n            bAiModel = prefs.getString("bai_model", "gpt-5.2").orEmpty(),\n            xKiroApiKey =''',
        "keyboard AiSettings",
    )
    KEYBOARD_SERVICE.write_text(text, encoding="utf-8")


def patch_settings_overlay() -> None:
    text = SETTINGS_OVERLAY.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '''        var bluesMindsKey: String,\n        var bluesMindsBaseUrl: String,\n        var bluesMindsModel: String,\n        var xKiroKey: String,''',
        '''        var bluesMindsKey: String,\n        var bluesMindsBaseUrl: String,\n        var bluesMindsModel: String,\n        var bAiKey: String,\n        var bAiBaseUrl: String,\n        var bAiModel: String,\n        var xKiroKey: String,''',
        "overlay Draft fields",
    )

    text = replace_once(
        text,
        '        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS, AiProvider.XKIRO, AiProvider.ORCAROUTER, AiProvider.AIHORDE)',
        '        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS, AiProvider.BAI, AiProvider.XKIRO, AiProvider.ORCAROUTER, AiProvider.AIHORDE)',
        "overlay provider grid",
    )

    text = replace_once(
        text,
        '''            AiProvider.BLUESMINDS -> {\n                config.addView(textInput("API Key BluesMinds", draft.bluesMindsKey, secret = true) { draft.bluesMindsKey = it })\n                config.addView(textInput("Base URL BluesMinds", draft.bluesMindsBaseUrl) { draft.bluesMindsBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.bluesMindsModel) { draft.bluesMindsModel = it })\n            }\n            AiProvider.XKIRO -> {''',
        '''            AiProvider.BLUESMINDS -> {\n                config.addView(textInput("API Key BluesMinds", draft.bluesMindsKey, secret = true) { draft.bluesMindsKey = it })\n                config.addView(textInput("Base URL BluesMinds", draft.bluesMindsBaseUrl) { draft.bluesMindsBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.bluesMindsModel) { draft.bluesMindsModel = it })\n            }\n            AiProvider.BAI -> {\n                config.addView(textInput("API Key B.AI", draft.bAiKey, secret = true) { draft.bAiKey = it })\n                config.addView(textInput("Base URL B.AI", draft.bAiBaseUrl) { draft.bAiBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.bAiModel) { draft.bAiModel = it })\n                config.addView(description("OpenAI-compatible · default https://api.b.ai/v1 · Authorization Bearer."))\n            }\n            AiProvider.XKIRO -> {''',
        "overlay B.AI config",
    )

    text = replace_once(
        text,
        '''            .putString("bluesminds_api_key", draft.bluesMindsKey.trim())\n            .putString("bluesminds_base_url", draft.bluesMindsBaseUrl.trim().ifBlank { "https://api.bluesminds.com/v1" })\n            .putString("bluesminds_model", draft.bluesMindsModel.trim().ifBlank { "deepseek-ai/deepseek-v4-flash" })\n            .putString("xkiro_api_key",''',
        '''            .putString("bluesminds_api_key", draft.bluesMindsKey.trim())\n            .putString("bluesminds_base_url", draft.bluesMindsBaseUrl.trim().ifBlank { "https://api.bluesminds.com/v1" })\n            .putString("bluesminds_model", draft.bluesMindsModel.trim().ifBlank { "deepseek-ai/deepseek-v4-flash" })\n            .putString("bai_api_key", draft.bAiKey.trim())\n            .putString("bai_base_url", draft.bAiBaseUrl.trim().ifBlank { "https://api.b.ai/v1" })\n            .putString("bai_model", draft.bAiModel.trim().ifBlank { "gpt-5.2" })\n            .putString("xkiro_api_key",''',
        "overlay save prefs",
    )

    text = replace_once(
        text,
        '''        bluesMindsKey = prefs.getString("bluesminds_api_key", "").orEmpty(),\n        bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),\n        bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),\n        xKiroKey =''',
        '''        bluesMindsKey = prefs.getString("bluesminds_api_key", "").orEmpty(),\n        bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),\n        bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),\n        bAiKey = prefs.getString("bai_api_key", "").orEmpty(),\n        bAiBaseUrl = prefs.getString("bai_base_url", "https://api.b.ai/v1").orEmpty(),\n        bAiModel = prefs.getString("bai_model", "gpt-5.2").orEmpty(),\n        xKiroKey =''',
        "overlay load prefs",
    )

    text = replace_once(
        text,
        '''        bluesMindsKey = draft.bluesMindsKey,\n        bluesMindsBaseUrl = "https://api.bluesminds.com/v1",\n        bluesMindsModel = "deepseek-ai/deepseek-v4-flash",\n        xKiroKey =''',
        '''        bluesMindsKey = draft.bluesMindsKey,\n        bluesMindsBaseUrl = "https://api.bluesminds.com/v1",\n        bluesMindsModel = "deepseek-ai/deepseek-v4-flash",\n        bAiKey = draft.bAiKey,\n        bAiBaseUrl = "https://api.b.ai/v1",\n        bAiModel = "gpt-5.2",\n        xKiroKey =''',
        "overlay defaults",
    )

    text = replace_once(
        text,
        '''        AiProvider.BLUESMINDS -> "BluesMinds"\n        AiProvider.XKIRO -> "xKiro"''',
        '''        AiProvider.BLUESMINDS -> "BluesMinds"\n        AiProvider.BAI -> "B.AI"\n        AiProvider.XKIRO -> "xKiro"''',
        "overlay provider label",
    )

    SETTINGS_OVERLAY.write_text(text, encoding="utf-8")


def patch_main_activity() -> None:
    text = MAIN_ACTIVITY.read_text(encoding="utf-8")

    marker = '''        root.addView(sectionTitle("xKiro"))\n        val xKiroKey = secretField("API key xKiro", prefs.getString("xkiro_api_key", ""))'''
    insertion = '''        root.addView(sectionTitle("B.AI"))\n        val bAiKey = secretField("API key B.AI", prefs.getString("bai_api_key", ""))\n        val bAiBaseUrl = textField("Base URL B.AI", prefs.getString("bai_base_url", "https://api.b.ai/v1"))\n        val bAiModel = textField("Model B.AI", prefs.getString("bai_model", "gpt-5.2"))\n        root.addView(bAiKey, ViewGroup.LayoutParams(-1, -2))\n        root.addView(bAiBaseUrl, ViewGroup.LayoutParams(-1, -2))\n        root.addView(bAiModel, ViewGroup.LayoutParams(-1, -2))\n        root.addView(description("OpenAI-compatible · endpoint /v1/chat/completions · Authorization Bearer."))\n\n        root.addView(sectionTitle("xKiro"))\n        val xKiroKey = secretField("API key xKiro", prefs.getString("xkiro_api_key", ""))'''
    text = replace_once(text, marker, insertion, "MainActivity B.AI fields")

    text = replace_once(
        text,
        '''                    .putString("9router_model", nineRouterModel.text.toString().trim().ifBlank { "cc/claude-sonnet-4-20250514" })\n                    .putString("xkiro_api_key",''',
        '''                    .putString("9router_model", nineRouterModel.text.toString().trim().ifBlank { "cc/claude-sonnet-4-20250514" })\n                    .putString("bai_api_key", bAiKey.text.toString().trim())\n                    .putString("bai_base_url", bAiBaseUrl.text.toString().trim().ifBlank { "https://api.b.ai/v1" })\n                    .putString("bai_model", bAiModel.text.toString().trim().ifBlank { "gpt-5.2" })\n                    .putString("xkiro_api_key",''',
        "MainActivity save prefs",
    )

    MAIN_ACTIVITY.write_text(text, encoding="utf-8")


def validate() -> None:
    ai_client = AI_CLIENT.read_text(encoding="utf-8")
    service = KEYBOARD_SERVICE.read_text(encoding="utf-8")
    overlay = SETTINGS_OVERLAY.read_text(encoding="utf-8")
    main = MAIN_ACTIVITY.read_text(encoding="utf-8")
    required = {
        "enum": 'BAI("bai", "B.AI")' in ai_client,
        "settings": "val bAiApiKey: String" in ai_client,
        "chat dispatch": "AiProvider.BAI -> requestCompatibleChat" in ai_client,
        "vision dispatch": "AiProvider.BAI -> requestCompatibleVision" in ai_client,
        "service prefs": 'bAiApiKey = prefs.getString("bai_api_key"' in service,
        "overlay provider": "AiProvider.BAI" in overlay,
        "overlay endpoint": "https://api.b.ai/v1" in overlay,
        "main activity": 'sectionTitle("B.AI")' in main,
    }
    missing = [name for name, ok in required.items() if not ok]
    if missing:
        raise RuntimeError("B.AI provider integration incomplete: " + ", ".join(missing))


patch_ai_client()
patch_keyboard_service()
patch_settings_overlay()
patch_main_activity()
validate()
print("B.AI provider patch applied: api.b.ai OpenAI-compatible endpoint + gpt-5.2 default")
