#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"
SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
OVERLAY = ROOT / "app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt"
MAIN = ROOT / "app/src/main/java/com/riyan/aikeyboard/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Private extra provider patch marker not found: {label}")
    return text.replace(old, new, 1)


# AiClient: all three are OpenAI Chat Completions compatible gateways.
a = AI.read_text(encoding="utf-8")
a = replace_once(
    a,
    '    BAI("bai", "B.AI"),\n    XKIRO("xkiro", "xKiro"),',
    '    BAI("bai", "B.AI"),\n    VYCEAI("vyceai", "VyceAI"),\n    AGENTROUTER("agentrouter", "AgentRouter"),\n    SEEKAI("seekai", "SeekAI"),\n    XKIRO("xkiro", "xKiro"),',
    "provider enum",
)
a = replace_once(
    a,
    '''    val bAiApiKey: String,\n    val bAiBaseUrl: String,\n    val bAiModel: String,\n    val xKiroApiKey: String,''',
    '''    val bAiApiKey: String,\n    val bAiBaseUrl: String,\n    val bAiModel: String,\n    val vyceAiApiKey: String,\n    val vyceAiBaseUrl: String,\n    val vyceAiModel: String,\n    val agentRouterApiKey: String,\n    val agentRouterBaseUrl: String,\n    val agentRouterModel: String,\n    val seekAiApiKey: String,\n    val seekAiBaseUrl: String,\n    val seekAiModel: String,\n    val xKiroApiKey: String,''',
    "settings fields",
)
a = replace_once(
    a,
    '''                    AiProvider.BAI -> requestCompatibleVision(settings.bAiApiKey, settings.bAiBaseUrl, settings.bAiModel, "B.AI", jpegBase64, localTextHint)\n                    AiProvider.XKIRO ->''',
    '''                    AiProvider.BAI -> requestCompatibleVision(settings.bAiApiKey, settings.bAiBaseUrl, settings.bAiModel, "B.AI", jpegBase64, localTextHint)\n                    AiProvider.VYCEAI -> requestCompatibleVision(settings.vyceAiApiKey, settings.vyceAiBaseUrl, settings.vyceAiModel, "VyceAI", jpegBase64, localTextHint)\n                    AiProvider.AGENTROUTER -> requestCompatibleVision(settings.agentRouterApiKey, settings.agentRouterBaseUrl, settings.agentRouterModel, "AgentRouter", jpegBase64, localTextHint)\n                    AiProvider.SEEKAI -> requestCompatibleVision(settings.seekAiApiKey, settings.seekAiBaseUrl, settings.seekAiModel, "SeekAI", jpegBase64, localTextHint)\n                    AiProvider.XKIRO ->''',
    "vision dispatch",
)
a = replace_once(
    a,
    '''                    AiProvider.BAI -> requestCompatibleChat(settings.bAiApiKey, settings.bAiBaseUrl, settings.bAiModel, "B.AI", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.XKIRO ->''',
    '''                    AiProvider.BAI -> requestCompatibleChat(settings.bAiApiKey, settings.bAiBaseUrl, settings.bAiModel, "B.AI", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.VYCEAI -> requestCompatibleChat(settings.vyceAiApiKey, settings.vyceAiBaseUrl, settings.vyceAiModel, "VyceAI", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.AGENTROUTER -> requestCompatibleChat(settings.agentRouterApiKey, settings.agentRouterBaseUrl, settings.agentRouterModel, "AgentRouter", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.SEEKAI -> requestCompatibleChat(settings.seekAiApiKey, settings.seekAiBaseUrl, settings.seekAiModel, "SeekAI", personalizedInstruction, text, temperature, maxTokens)\n                    AiProvider.XKIRO ->''',
    "chat dispatch",
)
AI.write_text(a, encoding="utf-8")

# Keyboard service: load three independent provider configs from existing SharedPreferences.
s = SERVICE.read_text(encoding="utf-8")
s = replace_once(
    s,
    '''            bAiApiKey = prefs.getString("bai_api_key", "").orEmpty(),\n            bAiBaseUrl = prefs.getString("bai_base_url", "https://api.b.ai/v1").orEmpty(),\n            bAiModel = prefs.getString("bai_model", "gpt-5.2").orEmpty(),\n            xKiroApiKey =''',
    '''            bAiApiKey = prefs.getString("bai_api_key", "").orEmpty(),\n            bAiBaseUrl = prefs.getString("bai_base_url", "https://api.b.ai/v1").orEmpty(),\n            bAiModel = prefs.getString("bai_model", "gpt-5.2").orEmpty(),\n            vyceAiApiKey = prefs.getString("vyceai_api_key", "").orEmpty(),\n            vyceAiBaseUrl = prefs.getString("vyceai_base_url", "https://vyceai.com/v1").orEmpty(),\n            vyceAiModel = prefs.getString("vyceai_model", "gpt-5.6-luna").orEmpty(),\n            agentRouterApiKey = prefs.getString("agentrouter_api_key", "").orEmpty(),\n            agentRouterBaseUrl = prefs.getString("agentrouter_base_url", "https://co.agentrouter.org/v1").orEmpty(),\n            agentRouterModel = prefs.getString("agentrouter_model", "glm-5.3").orEmpty(),\n            seekAiApiKey = prefs.getString("seekai_api_key", "").orEmpty(),\n            seekAiBaseUrl = prefs.getString("seekai_base_url", "https://seekai.cc/v1").orEmpty(),\n            seekAiModel = prefs.getString("seekai_model", "gpt-5.6-sol").orEmpty(),\n            xKiroApiKey =''',
    "service settings",
)
SERVICE.write_text(s, encoding="utf-8")

# In-keyboard settings overlay.
k = OVERLAY.read_text(encoding="utf-8")
k = replace_once(
    k,
    '''        var bAiKey: String,\n        var bAiBaseUrl: String,\n        var bAiModel: String,\n        var xKiroKey: String,''',
    '''        var bAiKey: String,\n        var bAiBaseUrl: String,\n        var bAiModel: String,\n        var vyceAiKey: String,\n        var vyceAiBaseUrl: String,\n        var vyceAiModel: String,\n        var agentRouterKey: String,\n        var agentRouterBaseUrl: String,\n        var agentRouterModel: String,\n        var seekAiKey: String,\n        var seekAiBaseUrl: String,\n        var seekAiModel: String,\n        var xKiroKey: String,''',
    "overlay draft fields",
)
k = replace_once(
    k,
    '        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS, AiProvider.BAI, AiProvider.XKIRO, AiProvider.ORCAROUTER, AiProvider.AIHORDE)',
    '        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS, AiProvider.BAI, AiProvider.VYCEAI, AiProvider.AGENTROUTER, AiProvider.SEEKAI, AiProvider.XKIRO, AiProvider.ORCAROUTER, AiProvider.AIHORDE)',
    "overlay provider grid",
)
k = replace_once(
    k,
    '''            AiProvider.BAI -> {\n                config.addView(textInput("API Key B.AI", draft.bAiKey, secret = true) { draft.bAiKey = it })\n                config.addView(textInput("Base URL B.AI", draft.bAiBaseUrl) { draft.bAiBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.bAiModel) { draft.bAiModel = it })\n                config.addView(description("OpenAI-compatible · default https://api.b.ai/v1 · Authorization Bearer."))\n            }\n            AiProvider.XKIRO -> {''',
    '''            AiProvider.BAI -> {\n                config.addView(textInput("API Key B.AI", draft.bAiKey, secret = true) { draft.bAiKey = it })\n                config.addView(textInput("Base URL B.AI", draft.bAiBaseUrl) { draft.bAiBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.bAiModel) { draft.bAiModel = it })\n                config.addView(description("OpenAI-compatible · default https://api.b.ai/v1 · Authorization Bearer."))\n            }\n            AiProvider.VYCEAI -> {\n                config.addView(textInput("API Key VyceAI", draft.vyceAiKey, secret = true) { draft.vyceAiKey = it })\n                config.addView(textInput("Base URL VyceAI", draft.vyceAiBaseUrl) { draft.vyceAiBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.vyceAiModel) { draft.vyceAiModel = it })\n                config.addView(description("OpenAI-compatible · default https://vyceai.com/v1 · Authorization Bearer."))\n            }\n            AiProvider.AGENTROUTER -> {\n                config.addView(textInput("API Key AgentRouter", draft.agentRouterKey, secret = true) { draft.agentRouterKey = it })\n                config.addView(textInput("Base URL AgentRouter", draft.agentRouterBaseUrl) { draft.agentRouterBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.agentRouterModel) { draft.agentRouterModel = it })\n                config.addView(description("OpenAI-compatible · default https://co.agentrouter.org/v1 · Authorization Bearer."))\n            }\n            AiProvider.SEEKAI -> {\n                config.addView(textInput("API Key SeekAI", draft.seekAiKey, secret = true) { draft.seekAiKey = it })\n                config.addView(textInput("Base URL SeekAI", draft.seekAiBaseUrl) { draft.seekAiBaseUrl = it })\n                config.addView(textInput("Nama Model", draft.seekAiModel) { draft.seekAiModel = it })\n                config.addView(description("OpenAI-compatible · default https://seekai.cc/v1 · Authorization Bearer."))\n            }\n            AiProvider.XKIRO -> {''',
    "overlay configs",
)
k = replace_once(
    k,
    '''            .putString("bai_api_key", draft.bAiKey.trim())\n            .putString("bai_base_url", draft.bAiBaseUrl.trim().ifBlank { "https://api.b.ai/v1" })\n            .putString("bai_model", draft.bAiModel.trim().ifBlank { "gpt-5.2" })\n            .putString("xkiro_api_key",''',
    '''            .putString("bai_api_key", draft.bAiKey.trim())\n            .putString("bai_base_url", draft.bAiBaseUrl.trim().ifBlank { "https://api.b.ai/v1" })\n            .putString("bai_model", draft.bAiModel.trim().ifBlank { "gpt-5.2" })\n            .putString("vyceai_api_key", draft.vyceAiKey.trim())\n            .putString("vyceai_base_url", draft.vyceAiBaseUrl.trim().ifBlank { "https://vyceai.com/v1" })\n            .putString("vyceai_model", draft.vyceAiModel.trim().ifBlank { "gpt-5.6-luna" })\n            .putString("agentrouter_api_key", draft.agentRouterKey.trim())\n            .putString("agentrouter_base_url", draft.agentRouterBaseUrl.trim().ifBlank { "https://co.agentrouter.org/v1" })\n            .putString("agentrouter_model", draft.agentRouterModel.trim().ifBlank { "glm-5.3" })\n            .putString("seekai_api_key", draft.seekAiKey.trim())\n            .putString("seekai_base_url", draft.seekAiBaseUrl.trim().ifBlank { "https://seekai.cc/v1" })\n            .putString("seekai_model", draft.seekAiModel.trim().ifBlank { "gpt-5.6-sol" })\n            .putString("xkiro_api_key",''',
    "overlay save",
)
k = replace_once(
    k,
    '''        bAiKey = prefs.getString("bai_api_key", "").orEmpty(),\n        bAiBaseUrl = prefs.getString("bai_base_url", "https://api.b.ai/v1").orEmpty(),\n        bAiModel = prefs.getString("bai_model", "gpt-5.2").orEmpty(),\n        xKiroKey =''',
    '''        bAiKey = prefs.getString("bai_api_key", "").orEmpty(),\n        bAiBaseUrl = prefs.getString("bai_base_url", "https://api.b.ai/v1").orEmpty(),\n        bAiModel = prefs.getString("bai_model", "gpt-5.2").orEmpty(),\n        vyceAiKey = prefs.getString("vyceai_api_key", "").orEmpty(),\n        vyceAiBaseUrl = prefs.getString("vyceai_base_url", "https://vyceai.com/v1").orEmpty(),\n        vyceAiModel = prefs.getString("vyceai_model", "gpt-5.6-luna").orEmpty(),\n        agentRouterKey = prefs.getString("agentrouter_api_key", "").orEmpty(),\n        agentRouterBaseUrl = prefs.getString("agentrouter_base_url", "https://co.agentrouter.org/v1").orEmpty(),\n        agentRouterModel = prefs.getString("agentrouter_model", "glm-5.3").orEmpty(),\n        seekAiKey = prefs.getString("seekai_api_key", "").orEmpty(),\n        seekAiBaseUrl = prefs.getString("seekai_base_url", "https://seekai.cc/v1").orEmpty(),\n        seekAiModel = prefs.getString("seekai_model", "gpt-5.6-sol").orEmpty(),\n        xKiroKey =''',
    "overlay load",
)
k = replace_once(
    k,
    '''        bAiKey = draft.bAiKey,\n        bAiBaseUrl = "https://api.b.ai/v1",\n        bAiModel = "gpt-5.2",\n        xKiroKey =''',
    '''        bAiKey = draft.bAiKey,\n        bAiBaseUrl = "https://api.b.ai/v1",\n        bAiModel = "gpt-5.2",\n        vyceAiKey = draft.vyceAiKey,\n        vyceAiBaseUrl = "https://vyceai.com/v1",\n        vyceAiModel = "gpt-5.6-luna",\n        agentRouterKey = draft.agentRouterKey,\n        agentRouterBaseUrl = "https://co.agentrouter.org/v1",\n        agentRouterModel = "glm-5.3",\n        seekAiKey = draft.seekAiKey,\n        seekAiBaseUrl = "https://seekai.cc/v1",\n        seekAiModel = "gpt-5.6-sol",\n        xKiroKey =''',
    "overlay defaults",
)
k = replace_once(
    k,
    '''        AiProvider.BAI -> "B.AI"\n        AiProvider.XKIRO -> "xKiro"''',
    '''        AiProvider.BAI -> "B.AI"\n        AiProvider.VYCEAI -> "VyceAI"\n        AiProvider.AGENTROUTER -> "AgentRouter"\n        AiProvider.SEEKAI -> "SeekAI"\n        AiProvider.XKIRO -> "xKiro"''',
    "overlay labels",
)
OVERLAY.write_text(k, encoding="utf-8")

# Standalone settings activity.
m = MAIN.read_text(encoding="utf-8")
m = replace_once(
    m,
    '''        root.addView(sectionTitle("xKiro"))\n        val xKiroKey = secretField("API key xKiro", prefs.getString("xkiro_api_key", ""))''',
    '''        root.addView(sectionTitle("VyceAI"))\n        val vyceAiKey = secretField("API key VyceAI", prefs.getString("vyceai_api_key", ""))\n        val vyceAiBaseUrl = textField("Base URL VyceAI", prefs.getString("vyceai_base_url", "https://vyceai.com/v1"))\n        val vyceAiModel = textField("Model VyceAI", prefs.getString("vyceai_model", "gpt-5.6-luna"))\n        root.addView(vyceAiKey, ViewGroup.LayoutParams(-1, -2))\n        root.addView(vyceAiBaseUrl, ViewGroup.LayoutParams(-1, -2))\n        root.addView(vyceAiModel, ViewGroup.LayoutParams(-1, -2))\n        root.addView(description("OpenAI-compatible · Authorization Bearer."))\n\n        root.addView(sectionTitle("AgentRouter"))\n        val agentRouterKey = secretField("API key AgentRouter", prefs.getString("agentrouter_api_key", ""))\n        val agentRouterBaseUrl = textField("Base URL AgentRouter", prefs.getString("agentrouter_base_url", "https://co.agentrouter.org/v1"))\n        val agentRouterModel = textField("Model AgentRouter", prefs.getString("agentrouter_model", "glm-5.3"))\n        root.addView(agentRouterKey, ViewGroup.LayoutParams(-1, -2))\n        root.addView(agentRouterBaseUrl, ViewGroup.LayoutParams(-1, -2))\n        root.addView(agentRouterModel, ViewGroup.LayoutParams(-1, -2))\n        root.addView(description("OpenAI-compatible · Base URL default memakai /v1."))\n\n        root.addView(sectionTitle("SeekAI"))\n        val seekAiKey = secretField("API key SeekAI", prefs.getString("seekai_api_key", ""))\n        val seekAiBaseUrl = textField("Base URL SeekAI", prefs.getString("seekai_base_url", "https://seekai.cc/v1"))\n        val seekAiModel = textField("Model SeekAI", prefs.getString("seekai_model", "gpt-5.6-sol"))\n        root.addView(seekAiKey, ViewGroup.LayoutParams(-1, -2))\n        root.addView(seekAiBaseUrl, ViewGroup.LayoutParams(-1, -2))\n        root.addView(seekAiModel, ViewGroup.LayoutParams(-1, -2))\n        root.addView(description("OpenAI-compatible · Authorization Bearer."))\n\n        root.addView(sectionTitle("xKiro"))\n        val xKiroKey = secretField("API key xKiro", prefs.getString("xkiro_api_key", ""))''',
    "main provider fields",
)
m = replace_once(
    m,
    '''                    .putString("bai_api_key", bAiKey.text.toString().trim())\n                    .putString("bai_base_url", bAiBaseUrl.text.toString().trim().ifBlank { "https://api.b.ai/v1" })\n                    .putString("bai_model", bAiModel.text.toString().trim().ifBlank { "gpt-5.2" })\n                    .putString("xkiro_api_key",''',
    '''                    .putString("bai_api_key", bAiKey.text.toString().trim())\n                    .putString("bai_base_url", bAiBaseUrl.text.toString().trim().ifBlank { "https://api.b.ai/v1" })\n                    .putString("bai_model", bAiModel.text.toString().trim().ifBlank { "gpt-5.2" })\n                    .putString("vyceai_api_key", vyceAiKey.text.toString().trim())\n                    .putString("vyceai_base_url", vyceAiBaseUrl.text.toString().trim().ifBlank { "https://vyceai.com/v1" })\n                    .putString("vyceai_model", vyceAiModel.text.toString().trim().ifBlank { "gpt-5.6-luna" })\n                    .putString("agentrouter_api_key", agentRouterKey.text.toString().trim())\n                    .putString("agentrouter_base_url", agentRouterBaseUrl.text.toString().trim().ifBlank { "https://co.agentrouter.org/v1" })\n                    .putString("agentrouter_model", agentRouterModel.text.toString().trim().ifBlank { "glm-5.3" })\n                    .putString("seekai_api_key", seekAiKey.text.toString().trim())\n                    .putString("seekai_base_url", seekAiBaseUrl.text.toString().trim().ifBlank { "https://seekai.cc/v1" })\n                    .putString("seekai_model", seekAiModel.text.toString().trim().ifBlank { "gpt-5.6-sol" })\n                    .putString("xkiro_api_key",''',
    "main save",
)
MAIN.write_text(m, encoding="utf-8")

required = {
    "enum": 'VYCEAI("vyceai", "VyceAI")' in AI.read_text(encoding="utf-8"),
    "agent": 'AGENTROUTER("agentrouter", "AgentRouter")' in AI.read_text(encoding="utf-8"),
    "seek": 'SEEKAI("seekai", "SeekAI")' in AI.read_text(encoding="utf-8"),
    "service": 'agentRouterBaseUrl = prefs.getString("agentrouter_base_url"' in SERVICE.read_text(encoding="utf-8"),
    "overlay": "AiProvider.VYCEAI" in OVERLAY.read_text(encoding="utf-8"),
    "main": 'sectionTitle("SeekAI")' in MAIN.read_text(encoding="utf-8"),
}
missing = [name for name, ok in required.items() if not ok]
if missing:
    raise RuntimeError("Private provider integration incomplete: " + ", ".join(missing))

print("Applied PRIVATE providers: VyceAI, AgentRouter, SeekAI")
