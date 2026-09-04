from pathlib import Path

path = Path('app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt')
text = path.read_text()

pairs = [
(
'''        var nineRouterModel: String,
        var fallbackEnabled: Boolean,''',
'''        var nineRouterModel: String,
        var bluesMindsKey: String,
        var bluesMindsBaseUrl: String,
        var bluesMindsModel: String,
        var fallbackEnabled: Boolean,''',
'draft fields'),
(
'''        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER)''',
'''        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS)''',
'provider list'),
(
'''            AiProvider.NINEROUTER -> {
                config.addView(textInput("API Key 9Router", draft.nineRouterKey, secret = true) { draft.nineRouterKey = it })
                config.addView(textInput("Base URL Gateway", draft.nineRouterBaseUrl) { draft.nineRouterBaseUrl = it })
                config.addView(textInput("Nama Model / Combo", draft.nineRouterModel) { draft.nineRouterModel = it })
            }
        }''',
'''            AiProvider.NINEROUTER -> {
                config.addView(textInput("API Key 9Router", draft.nineRouterKey, secret = true) { draft.nineRouterKey = it })
                config.addView(textInput("Base URL Gateway", draft.nineRouterBaseUrl) { draft.nineRouterBaseUrl = it })
                config.addView(textInput("Nama Model / Combo", draft.nineRouterModel) { draft.nineRouterModel = it })
            }
            AiProvider.BLUESMINDS -> {
                config.addView(textInput("API Key BluesMinds", draft.bluesMindsKey, secret = true) { draft.bluesMindsKey = it })
                config.addView(textInput("Base URL BluesMinds", draft.bluesMindsBaseUrl) { draft.bluesMindsBaseUrl = it })
                config.addView(textInput("Nama Model", draft.bluesMindsModel) { draft.bluesMindsModel = it })
            }
        }''',
'provider config'),
(
'''            .putString("9router_model", draft.nineRouterModel.trim().ifBlank { "cc/claude-sonnet-4-20250514" })
            .putBoolean("fallback_enabled", draft.fallbackEnabled)''',
'''            .putString("9router_model", draft.nineRouterModel.trim().ifBlank { "cc/claude-sonnet-4-20250514" })
            .putString("bluesminds_api_key", draft.bluesMindsKey.trim())
            .putString("bluesminds_base_url", draft.bluesMindsBaseUrl.trim().ifBlank { "https://api.bluesminds.com/v1" })
            .putString("bluesminds_model", draft.bluesMindsModel.trim().ifBlank { "deepseek-ai/deepseek-v4-flash" })
            .putBoolean("fallback_enabled", draft.fallbackEnabled)''',
'save bluesminds'),
(
'''        nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),
        fallbackEnabled = prefs.getBoolean("fallback_enabled", false),''',
'''        nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),
        bluesMindsKey = prefs.getString("bluesminds_api_key", "").orEmpty(),
        bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),
        bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),
        fallbackEnabled = prefs.getBoolean("fallback_enabled", false),''',
'load bluesminds'),
(
'''        nineRouterModel = "cc/claude-sonnet-4-20250514",
        fallbackEnabled = false,''',
'''        nineRouterModel = "cc/claude-sonnet-4-20250514",
        bluesMindsKey = draft.bluesMindsKey,
        bluesMindsBaseUrl = "https://api.bluesminds.com/v1",
        bluesMindsModel = "deepseek-ai/deepseek-v4-flash",
        fallbackEnabled = false,''',
'default bluesminds'),
(
'''        AiProvider.NINEROUTER -> "9Router"
    }''',
'''        AiProvider.NINEROUTER -> "9Router"
        AiProvider.BLUESMINDS -> "BluesMinds"
    }''',
'provider label')
]

for old, new, label in pairs:
    if new in text:
        continue
    if old not in text:
        raise SystemExit(f'{label} anchor missing')
    text = text.replace(old, new, 1)

path.write_text(text)
print('KeyboardSettingsOverlay BluesMinds compatibility fixed')
