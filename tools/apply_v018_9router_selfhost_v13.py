from pathlib import Path

ai_path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
main_path = Path('app/src/main/java/com/riyan/aikeyboard/MainActivity.kt')
service_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
manifest_path = Path('app/src/main/AndroidManifest.xml')

ai = ai_path.read_text(encoding='utf-8')
main = main_path.read_text(encoding='utf-8')
service = service_path.read_text(encoding='utf-8')
manifest = manifest_path.read_text(encoding='utf-8')

# Self-hosted 9Router commonly exposes http://HOST:20128/v1. Android 9+ blocks
# cleartext HTTP by default for modern targetSdk apps, so explicitly allow it.
# This is only transport compatibility; users should prefer HTTPS/Tailscale when available.
if 'android:usesCleartextTraffic="true"' not in manifest:
    old = '<application android:theme="@style/AppTheme"'
    new = '<application android:usesCleartextTraffic="true" android:theme="@style/AppTheme"'
    if old not in manifest:
        raise RuntimeError('application manifest marker not found')
    manifest = manifest.replace(old, new, 1)

# Do not silently fall back to 9router.com for a self-hosted setup. Require the
# exact Base URL the dashboard shows under Endpoint, e.g. http://host:20128/v1.
ai = ai.replace(
    'val clean = baseUrl.trim().ifBlank { "https://9router.com/v1" }.trimEnd(\'/\')',
    'val clean = baseUrl.trim().also { require(it.isNotBlank()) { "Base URL 9Router belum diisi." } }.trimEnd(\'/\')'
)
if 'Base URL 9Router belum diisi.' not in ai:
    raise RuntimeError('9Router URL validation replacement failed')

# New installs leave the Base URL empty instead of assuming the cloud service.
service = service.replace(
    'prefs.getString("9router_base_url", "https://9router.com/v1").orEmpty()',
    'prefs.getString("9router_base_url", "").orEmpty()'
)
main = main.replace(
    'prefs.getString("9router_base_url", "https://9router.com/v1")',
    'prefs.getString("9router_base_url", "")'
)
main = main.replace(
    '.putString("9router_base_url", nineRouterBaseUrl.text.toString().trim().ifBlank { "https://9router.com/v1" })',
    '.putString("9router_base_url", nineRouterBaseUrl.text.toString().trim())'
)

# Clearer UI hints for self-hosted instances and combo names such as My2.
main = main.replace(
    'val nineRouterBaseUrl = textField("Base URL 9Router", prefs.getString("9router_base_url", ""))',
    'val nineRouterBaseUrl = textField("Base URL 9Router · contoh http://server:20128/v1", prefs.getString("9router_base_url", ""))'
)
main = main.replace(
    'OpenAI-compatible · endpoint otomatis: /chat/completions · model vision diperlukan untuk kamera AI Vision',
    'OpenAI-compatible · isi Base URL persis dari menu Endpoint 9Router (akhiri /v1). Nama Combo seperti My2 boleh dipakai sebagai model. Untuk kamera, aktifkan Vision Adapter/model vision di 9Router.'
)

# Improve the keyboard-side error text so a wrong host/path is distinguishable
# from a bad key or a non-vision model.
old = '''        return when {
            message.contains("API key", ignoreCase = true) -> "AI Vision belum bisa dipakai · isi API key di pengaturan"
            message.contains("model", ignoreCase = true) || message.contains("image", ignoreCase = true) ||
                message.contains("vision", ignoreCase = true) -> "Model AI yang dipilih belum mendukung gambar · pilih model vision/multimodal"
            else -> "AI Vision gagal mengenali foto · cek API/model lalu coba lagi"
        }'''
new = '''        return when {
            message.contains("404", ignoreCase = true) -> "9Router/API 404 · cek Base URL, harus endpoint /v1 dari dashboard"
            message.contains("401", ignoreCase = true) || message.contains("403", ignoreCase = true) -> "API key ditolak · cek key provider yang dipilih"
            message.contains("cleartext", ignoreCase = true) -> "HTTP self-hosted diblokir Android · gunakan build terbaru atau HTTPS/Tailscale"
            message.contains("API key", ignoreCase = true) -> "AI Vision belum bisa dipakai · isi API key di pengaturan"
            message.contains("model", ignoreCase = true) || message.contains("image", ignoreCase = true) ||
                message.contains("vision", ignoreCase = true) -> "Model AI yang dipilih belum mendukung gambar · pilih model vision/multimodal"
            else -> "AI Vision gagal mengenali foto · cek Base URL, API key, dan model lalu coba lagi"
        }'''
if old in service:
    service = service.replace(old, new, 1)

ai_path.write_text(ai, encoding='utf-8')
main_path.write_text(main, encoding='utf-8')
service_path.write_text(service, encoding='utf-8')
manifest_path.write_text(manifest, encoding='utf-8')
print('Applied v0.18 v13: self-hosted 9Router Base URL, HTTP transport support, and clearer 404/key/model errors.')
