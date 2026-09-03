from pathlib import Path

service_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
ai_path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
main_path = Path('app/src/main/java/com/riyan/aikeyboard/MainActivity.kt')
manifest_path = Path('app/src/main/AndroidManifest.xml')

service = service_path.read_text(encoding='utf-8')
ai = ai_path.read_text(encoding='utf-8')
main = main_path.read_text(encoding='utf-8')
manifest = manifest_path.read_text(encoding='utf-8')


def replace_private_fun(text: str, name: str, replacement: str) -> str:
    marker = f'    private fun {name}('
    start = text.find(marker)
    if start < 0:
        raise RuntimeError(f'{name}: function not found')
    next_fun = text.find('\n    private fun ', start + len(marker))
    if next_fun < 0:
        raise RuntimeError(f'{name}: next function marker not found')
    return text[:start] + replacement.rstrip() + '\n' + text[next_fun:]


# -----------------------------------------------------------------------------
# 1) Native-app routing for Bing results and direct links.
#    Bing wraps many result URLs in /ck/a?u=a1<base64url>. Decode that first,
#    then explicitly prefer the installed platform app. Unknown sites still use
#    Android App Links generically and otherwise remain inside the WebView.
# -----------------------------------------------------------------------------
new_native_router = r'''    private fun openInstalledAppForLink(rawUrl: String): Boolean {
        val searchUnwrapped = unwrapSearchRedirect(rawUrl)
        val url = unwrapBingRedirect(searchUnwrapped)
        if (url.isBlank()) return false

        return runCatching {
            if (url.startsWith("intent://", ignoreCase = true)) {
                val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val resolved = parsed.resolveActivity(packageManager)
                if (resolved != null && resolved.packageName != packageName) {
                    startActivity(parsed)
                    return@runCatching true
                }
                val fallback = parsed.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    searchWebView?.loadUrl(fallback)
                    return@runCatching true
                }
                return@runCatching false
            }

            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme !in setOf("http", "https")) {
                val direct = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val resolved = direct.resolveActivity(packageManager)
                if (resolved != null && resolved.packageName != packageName) {
                    startActivity(direct)
                    return@runCatching true
                }
                return@runCatching false
            }

            // Known services: send the original HTTPS/App-Link URL directly to the
            // native package. This avoids Bing/browser claiming the link first.
            val knownPackages = nativePackagesForUrl(uri)
            for (candidate in knownPackages) {
                val direct = Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setPackage(candidate)
                val handled = runCatching {
                    startActivity(direct)
                    true
                }.getOrDefault(false)
                if (handled) return@runCatching true
            }

            // Generic App-Link fallback for other installed apps. Browsers are
            // deliberately excluded; if no native handler exists WebView returns false
            // and continues loading the page inside the keyboard panel.
            val targetIntent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val browserProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val browserPackages = packageManager.queryIntentActivities(browserProbe, 0)
                .map { it.activityInfo.packageName }
                .toSet()

            val appPackage = packageManager.queryIntentActivities(targetIntent, 0)
                .asSequence()
                .map { it.activityInfo.packageName }
                .firstOrNull { it != packageName && it !in browserPackages }
                ?: return@runCatching false

            targetIntent.setPackage(appPackage)
            startActivity(targetIntent)
            true
        }.getOrDefault(false)
    }

    private fun unwrapBingRedirect(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith("bing.com")) return rawUrl

        listOf("url", "r", "target").forEach { key ->
            val direct = uri.getQueryParameter(key).orEmpty().trim()
            if (direct.startsWith("http://", true) || direct.startsWith("https://", true)) {
                return direct
            }
        }

        val encoded = uri.getQueryParameter("u").orEmpty().trim()
        if (encoded.startsWith("http://", true) || encoded.startsWith("https://", true)) {
            return encoded
        }
        if (encoded.startsWith("a1") && encoded.length > 4) {
            val payload = encoded.substring(2)
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = runCatching {
                String(
                    android.util.Base64.decode(
                        padded,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                    ),
                    Charsets.UTF_8
                )
            }.getOrNull().orEmpty().trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                return decoded
            }
        }
        return rawUrl
    }

    private fun nativePackagesForUrl(uri: Uri): List<String> {
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        return when {
            host == "shopee.co.id" || host.endsWith(".shopee.co.id") ||
                host == "shopee.com" || host.endsWith(".shopee.com") -> listOf("com.shopee.id")
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                listOf("com.google.android.youtube")
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                listOf("com.instagram.android")
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") ->
                listOf("com.twitter.android")
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" ->
                listOf("com.facebook.katana")
            else -> emptyList()
        }
    }'''

if 'private fun openInstalledAppForLink(' not in service:
    raise RuntimeError('post-v0.18 native app router not found')
service = replace_private_fun(service, 'openInstalledAppForLink', new_native_router)

# -----------------------------------------------------------------------------
# 2) 9Router provider. 9Router is OpenAI-compatible. Keep Base URL configurable;
#    cloud default is https://9router.com/v1. Text and image/vision both use
#    /chat/completions, so the existing camera AI Vision can use a vision-capable
#    9Router model without a separate integration.
# -----------------------------------------------------------------------------
old_enum = '    TABIAI("tabiai", "TabiAI");'
new_enum = '    TABIAI("tabiai", "TabiAI"),\n    NINEROUTER("9router", "9Router");'
if new_enum not in ai:
    if old_enum not in ai:
        raise RuntimeError('AiProvider enum marker not found')
    ai = ai.replace(old_enum, new_enum, 1)

settings_marker = '    val tabiModel: String,\n'
settings_add = (
    '    val tabiModel: String,\n'
    '    val nineRouterApiKey: String,\n'
    '    val nineRouterBaseUrl: String,\n'
    '    val nineRouterModel: String,\n'
)
if 'val nineRouterApiKey: String' not in ai:
    if settings_marker not in ai:
        raise RuntimeError('AiSettings marker not found')
    ai = ai.replace(settings_marker, settings_add, 1)

old_provider_block = '''        val providers = buildList {
            add(settings.primaryProvider)
            if (settings.fallbackEnabled) {
                add(if (settings.primaryProvider == AiProvider.OPENROUTER) AiProvider.TABIAI else AiProvider.OPENROUTER)
            }
        }'''
new_provider_block = '''        val providers = buildList {
            add(settings.primaryProvider)
            if (settings.fallbackEnabled) {
                AiProvider.entries
                    .filter { it != settings.primaryProvider }
                    .forEach(::add)
            }
        }'''
if old_provider_block in ai:
    ai = ai.replace(old_provider_block, new_provider_block)
if 'AiProvider.entries\n                    .filter { it != settings.primaryProvider }' not in ai:
    raise RuntimeError('provider fallback block was not upgraded for 3 providers')

text_when_old = '''                    AiProvider.OPENROUTER -> requestOpenRouter(settings, personalizedInstruction, text, temperature, maxTokens)
                    AiProvider.TABIAI -> requestTabiAi(settings, personalizedInstruction, text, temperature, maxTokens)'''
text_when_new = text_when_old + '''
                    AiProvider.NINEROUTER -> request9Router(settings, personalizedInstruction, text, temperature, maxTokens)'''
if 'AiProvider.NINEROUTER -> request9Router(settings' not in ai:
    if text_when_old not in ai:
        raise RuntimeError('text provider when block not found')
    ai = ai.replace(text_when_old, text_when_new, 1)

vision_when_old = '''                    AiProvider.OPENROUTER -> requestOpenRouterVision(settings, jpegBase64, localTextHint)
                    AiProvider.TABIAI -> requestTabiAiVision(settings, jpegBase64, localTextHint)'''
vision_when_new = vision_when_old + '''
                    AiProvider.NINEROUTER -> request9RouterVision(settings, jpegBase64, localTextHint)'''
if 'AiProvider.NINEROUTER -> request9RouterVision' not in ai:
    if vision_when_old not in ai:
        raise RuntimeError('vision provider when block not found; v10 must run before v12')
    ai = ai.replace(vision_when_old, vision_when_new, 1)

nine_router_methods = r'''    private fun request9Router(
        settings: AiSettings,
        systemInstruction: String,
        text: String,
        temperature: Double,
        maxTokens: Int
    ): String {
        require(settings.nineRouterApiKey.isNotBlank()) { "API key 9Router belum diisi." }
        require(settings.nineRouterModel.isNotBlank()) { "Model 9Router belum diisi." }

        val body = JSONObject()
            .put("model", settings.nineRouterModel.trim())
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemInstruction))
                    .put(JSONObject().put("role", "user").put("content", text))
            )

        val response = postJson(
            url = nineRouterChatUrl(settings.nineRouterBaseUrl),
            headers = mapOf("Authorization" to "Bearer ${settings.nineRouterApiKey.trim()}"),
            body = body
        )
        return response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    private fun request9RouterVision(
        settings: AiSettings,
        jpegBase64: String,
        localTextHint: String
    ): String {
        require(settings.nineRouterApiKey.isNotBlank()) { "API key 9Router belum diisi." }
        require(settings.nineRouterModel.isNotBlank()) { "Model 9Router belum diisi." }

        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", visionInstruction(localTextHint)))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64"))
            )

        val body = JSONObject()
            .put("model", settings.nineRouterModel.trim())
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
            url = nineRouterChatUrl(settings.nineRouterBaseUrl),
            headers = mapOf("Authorization" to "Bearer ${settings.nineRouterApiKey.trim()}"),
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
        require(output.isNotBlank()) { "Model 9Router tidak mengembalikan hasil vision." }
        return output
    }

    private fun nineRouterChatUrl(baseUrl: String): String {
        val clean = baseUrl.trim().ifBlank { "https://9router.com/v1" }.trimEnd('/')
        return when {
            clean.endsWith("/chat/completions", ignoreCase = true) -> clean
            clean.endsWith("/v1", ignoreCase = true) -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }'''
if 'private fun request9Router(' not in ai:
    marker = '    private fun requestOpenRouter('
    pos = ai.find(marker)
    if pos < 0:
        raise RuntimeError('OpenRouter request marker not found for 9Router insertion')
    ai = ai[:pos] + nine_router_methods.rstrip() + '\n\n' + ai[pos:]

# Keyboard service must load the three extra preference values into AiSettings.
settings_call_marker = '            tabiModel = prefs.getString("tabi_model", "claude-opus-5").orEmpty(),\n'
settings_call_add = settings_call_marker + (
    '            nineRouterApiKey = prefs.getString("9router_api_key", "").orEmpty(),\n'
    '            nineRouterBaseUrl = prefs.getString("9router_base_url", "https://9router.com/v1").orEmpty(),\n'
    '            nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),\n'
)
if 'nineRouterApiKey = prefs.getString("9router_api_key"' not in service:
    if settings_call_marker not in service:
        raise RuntimeError('Keyboard AiSettings call marker not found')
    service = service.replace(settings_call_marker, settings_call_add, 1)

# Main settings UI: separate 9Router key/base URL/model section.
tabi_ui_marker = '''        root.addView(description("Endpoint Claude: /v1/messages · header: x-api-key"))'''
nine_ui = tabi_ui_marker + r'''

        root.addView(sectionTitle("9Router"))
        val nineRouterKey = secretField("API key 9Router", prefs.getString("9router_api_key", ""))
        val nineRouterBaseUrl = textField("Base URL 9Router", prefs.getString("9router_base_url", "https://9router.com/v1"))
        val nineRouterModel = textField("Model 9Router", prefs.getString("9router_model", "cc/claude-sonnet-4-20250514"))
        root.addView(nineRouterKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(nineRouterBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(nineRouterModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("OpenAI-compatible · endpoint otomatis: /chat/completions · model vision diperlukan untuk kamera AI Vision"))'''
if 'val nineRouterKey = secretField(' not in main:
    if tabi_ui_marker not in main:
        raise RuntimeError('TabiAI UI marker not found')
    main = main.replace(tabi_ui_marker, nine_ui, 1)

save_marker = '                    .putString("tabi_model", tabiModel.text.toString().trim().ifBlank { "claude-opus-5" })\n'
save_add = save_marker + (
    '                    .putString("9router_api_key", nineRouterKey.text.toString().trim())\n'
    '                    .putString("9router_base_url", nineRouterBaseUrl.text.toString().trim().ifBlank { "https://9router.com/v1" })\n'
    '                    .putString("9router_model", nineRouterModel.text.toString().trim().ifBlank { "cc/claude-sonnet-4-20250514" })\n'
)
if '.putString("9router_api_key"' not in main:
    if save_marker not in main:
        raise RuntimeError('AI settings save marker not found')
    main = main.replace(save_marker, save_add, 1)

# Show a clear provider name in the selector.
old_provider_label = 'providerOptions.map { if (it == AiProvider.TABIAI) "TabiAI (tabitoken.com)" else it.label }'
new_provider_label = '''providerOptions.map {
                when (it) {
                    AiProvider.TABIAI -> "TabiAI (tabitoken.com)"
                    AiProvider.NINEROUTER -> "9Router (OpenAI-compatible)"
                    else -> it.label
                }
            }'''
if old_provider_label in main:
    main = main.replace(old_provider_label, new_provider_label, 1)

# Android package visibility for direct platform handoff.
package_queries = '''        <package android:name="com.shopee.id" />
        <package android:name="com.google.android.youtube" />
        <package android:name="com.instagram.android" />
        <package android:name="com.zhiliaoapp.musically" />
        <package android:name="com.ss.android.ugc.trill" />
        <package android:name="com.twitter.android" />
        <package android:name="com.facebook.katana" />
'''
if 'android:name="com.shopee.id"' not in manifest:
    q_end = manifest.find('    </queries>')
    if q_end < 0:
        raise RuntimeError('manifest <queries> block not found')
    manifest = manifest[:q_end] + package_queries + manifest[q_end:]

service_path.write_text(service, encoding='utf-8')
ai_path.write_text(ai, encoding='utf-8')
main_path.write_text(main, encoding='utf-8')
manifest_path.write_text(manifest, encoding='utf-8')
print('Applied v0.18 v12: Bing redirect decoding + native platform app handoff + 9Router AI provider (text and multimodal vision).')
