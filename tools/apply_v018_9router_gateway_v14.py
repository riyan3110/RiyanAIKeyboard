from pathlib import Path

ai_path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
main_path = Path('app/src/main/java/com/riyan/aikeyboard/MainActivity.kt')
service_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
manifest_path = Path('app/src/main/AndroidManifest.xml')

ai = ai_path.read_text(encoding='utf-8')
main = main_path.read_text(encoding='utf-8')
service = service_path.read_text(encoding='utf-8')
manifest = manifest_path.read_text(encoding='utf-8')

# Match the working AI Ads Lab 9Router integration: port 20128 is dashboard,
# API traffic goes to the 20130 gateway. Allow HTTP because the user's gateway
# is currently exposed as plain HTTP.
if 'android:usesCleartextTraffic="true"' not in manifest:
    old = '<application android:theme="@style/AppTheme"'
    new = '<application android:usesCleartextTraffic="true" android:theme="@style/AppTheme"'
    if old not in manifest:
        raise RuntimeError('manifest application marker not found')
    manifest = manifest.replace(old, new, 1)

# Replace the v12 helper with an AI-Ads-Lab-compatible gateway normalizer.
start = ai.find('    private fun nineRouterChatUrl(baseUrl: String): String {')
if start < 0:
    raise RuntimeError('nineRouterChatUrl not found; v12 must run first')
end = ai.find('\n    private fun requestOpenRouter(', start)
if end < 0:
    raise RuntimeError('nineRouterChatUrl end marker not found')
helper = r'''    private fun nineRouterChatUrl(baseUrl: String): String {
        // 9Router dashboard commonly runs on :20128 while authenticated OpenAI-compatible
        // API traffic is exposed by the gateway on :20130. This mirrors AI Ads Lab.
        var clean = baseUrl.trim().ifBlank { "http://43.159.50.231:20130/v1" }.trimEnd('/')
        clean = clean.replace(Regex(":20128(?=/|$)"), ":20130")
        return when {
            clean.endsWith("/chat/completions", ignoreCase = true) -> clean
            clean.endsWith("/v1", ignoreCase = true) -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }'''
ai = ai[:start] + helper.rstrip() + '\n' + ai[end:]

# New/default installs point straight at the gateway. Previously entered dashboard
# URLs are still accepted because the helper above automatically rewrites 20128 -> 20130.
service = service.replace(
    'prefs.getString("9router_base_url", "https://9router.com/v1").orEmpty()',
    'prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1").orEmpty()'
)
service = service.replace(
    'prefs.getString("9router_base_url", "").orEmpty()',
    'prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1").orEmpty()'
)
main = main.replace(
    'prefs.getString("9router_base_url", "https://9router.com/v1")',
    'prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1")'
)
main = main.replace(
    'prefs.getString("9router_base_url", "")',
    'prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1")'
)
main = main.replace(
    '.putString("9router_base_url", nineRouterBaseUrl.text.toString().trim().ifBlank { "https://9router.com/v1" })',
    '.putString("9router_base_url", nineRouterBaseUrl.text.toString().trim().ifBlank { "http://43.159.50.231:20130/v1" })'
)
main = main.replace(
    '.putString("9router_base_url", nineRouterBaseUrl.text.toString().trim())',
    '.putString("9router_base_url", nineRouterBaseUrl.text.toString().trim().ifBlank { "http://43.159.50.231:20130/v1" })'
)

main = main.replace(
    'val nineRouterBaseUrl = textField("Base URL 9Router", prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1"))',
    'val nineRouterBaseUrl = textField("Base URL 9Router (Gateway API)", prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1"))'
)
main = main.replace(
    'val nineRouterBaseUrl = textField("Base URL 9Router · contoh http://server:20128/v1", prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1"))',
    'val nineRouterBaseUrl = textField("Base URL 9Router (Gateway API)", prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1"))'
)

# Replace old helper text if present.
for old in [
    'OpenAI-compatible · endpoint otomatis: /chat/completions · model vision diperlukan untuk kamera AI Vision',
    'OpenAI-compatible · isi Base URL persis dari menu Endpoint 9Router (akhiri /v1). Nama Combo seperti My2 boleh dipakai sebagai model. Untuk kamera, aktifkan Vision Adapter/model vision di 9Router.'
]:
    main = main.replace(old, 'OpenAI-compatible · dashboard :20128 otomatis dialihkan ke gateway API :20130. Nama Combo seperti My2 dipakai persis. Untuk kamera, aktifkan Vision Adapter/model vision di 9Router.')

# Give the user a useful 404 message specifically for this topology.
old_msg = '"AI Vision gagal mengenali foto · cek API/model lalu coba lagi"'
new_msg = '"AI Vision gagal · cek gateway 9Router :20130, API key, dan model/combo"'
service = service.replace(old_msg, new_msg)

ai_path.write_text(ai, encoding='utf-8')
main_path.write_text(main, encoding='utf-8')
service_path.write_text(service, encoding='utf-8')
manifest_path.write_text(manifest, encoding='utf-8')
print('Applied v0.18 v14: 9Router dashboard 20128 -> API gateway 20130, matching AI Ads Lab.')
