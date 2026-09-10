#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/riyan/aikeyboard"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"PRIVATE v035 patch marker not found: {label}")
    return text.replace(old, new, 1)


# Settings overlay: requested tab order, fallback toggle, URL move, keyboard setup buttons,
# and touch sensitivity in Theme & Tampilan.
overlay_path = SRC / "KeyboardSettingsOverlay.kt"
overlay = overlay_path.read_text(encoding="utf-8")

if "import android.provider.Settings" not in overlay:
    overlay = replace_once(
        overlay,
        "import android.graphics.drawable.GradientDrawable\n",
        "import android.graphics.drawable.GradientDrawable\nimport android.provider.Settings\n",
        "Settings import",
    )
if "import android.view.inputmethod.InputMethodManager" not in overlay:
    overlay = replace_once(
        overlay,
        "import android.view.ViewGroup\n",
        "import android.view.ViewGroup\nimport android.view.inputmethod.InputMethodManager\n",
        "InputMethodManager import",
    )

overlay = overlay.replace(
    "enum class Tab { MODEL, MEMORY, THEME, TYPING }",
    "enum class Tab { THEME, TYPING, MEMORY, MODEL }",
    1,
)
overlay = overlay.replace("private var currentTab = Tab.MODEL", "private var currentTab = Tab.THEME", 1)

overlay = replace_once(
    overlay,
    "        var keyBoxScale: Int,\n        var longPressMs: Int,",
    "        var keyBoxScale: Int,\n        var touchSensitivity: Int,\n        var longPressMs: Int,",
    "Draft touch sensitivity",
)

overlay = replace_once(
    overlay,
    '''        val tabs = listOf(\n            Tab.MODEL to "🤖 Model AI",\n            Tab.MEMORY to "✍ Memori Gaya",\n            Tab.THEME to "🎨 Tema & Tampilan",\n            Tab.TYPING to "⌨ Ketikan & Saran"\n        )''',
    '''        val tabs = listOf(\n            Tab.THEME to "🎨 Tema & Tampilan",\n            Tab.TYPING to "⌨ Ketikan & Saran",\n            Tab.MEMORY to "✍ Memori Gaya",\n            Tab.MODEL to "🤖 Model AI"\n        )''',
    "tab order",
)

model_urls = '''        // URL referensi tetap dipertahankan karena ini fitur terpisah dari konfigurasi provider.\n        val urls = cardContainer()\n        urls.addView(section("URL Referensi Domain", compact = true))\n        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))\n        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })\n        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
model_fallback = '''        body.addView(toggleCard(\n            "Aktifkan Fallback Penyedia",\n            "Otomatis beralih ke provider lain bila provider/model utama gagal.",\n            draft.fallbackEnabled\n        ) { draft.fallbackEnabled = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
if model_urls in overlay:
    overlay = overlay.replace(model_urls, model_fallback, 1)
elif model_fallback not in overlay:
    raise RuntimeError("PRIVATE v035 model URL/fallback marker not found")

memory_tail = '''        val phraseCard = cardContainer()\n        phraseCard.addView(section("Kalimat Tersimpan", compact = true))\n        phraseCard.addView(textInput("Email atau kalimat tersimpan, satu per baris", draft.personalPhrases, multiline = true) { draft.personalPhrases = it })\n        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })\n    }'''
memory_tail_new = '''        val phraseCard = cardContainer()\n        phraseCard.addView(section("Kalimat Tersimpan", compact = true))\n        phraseCard.addView(textInput("Email atau kalimat tersimpan, satu per baris", draft.personalPhrases, multiline = true) { draft.personalPhrases = it })\n        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })\n\n        val urls = cardContainer()\n        urls.addView(section("URL Referensi Domain", compact = true))\n        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))\n        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })\n        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })\n    }'''
overlay = replace_once(overlay, memory_tail, memory_tail_new, "URL reference move to memory")

overlay = replace_once(
    overlay,
    '''        keyCard.addView(sliderRow("Skala Kotak Tombol (Maks. 150%)", 65, 150, draft.keyBoxScale, "%") { draft.keyBoxScale = it })\n        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })''',
    '''        keyCard.addView(sliderRow("Skala Kotak Tombol (Maks. 150%)", 65, 150, draft.keyBoxScale, "%") { draft.keyBoxScale = it })\n        keyCard.addView(sliderRow("Sensitivitas dan Kecepatan Tombol", 20, 400, draft.touchSensitivity, "%") { draft.touchSensitivity = it })\n        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })''',
    "theme touch sensitivity slider",
)

typing_start = '''    private fun renderTypingTab() {\n        body.addView(toggleCard("Efek Suara Tombol (Click Sound)", "Memainkan suara klik saat tombol disentuh.", draft.sound) { draft.sound = it })'''
typing_new = '''    private fun renderTypingTab() {\n        val keyboardAccess = cardContainer()\n        keyboardAccess.addView(section("Akses Keyboard", compact = true))\n        keyboardAccess.addView(description("Aktifkan AI Ads Keyboard di Android, lalu pilih sebagai keyboard yang digunakan."))\n        keyboardAccess.addView(actionButton("Aktifkan Keyboard") {\n            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)\n            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n            context.startActivity(intent)\n        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })\n        keyboardAccess.addView(actionButton("Pilih Keyboard") {\n            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager\n            imm.showInputMethodPicker()\n        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })\n        body.addView(keyboardAccess, LinearLayout.LayoutParams(-1, -2))\n\n        body.addView(toggleCard("Efek Suara Tombol (Click Sound)", "Memainkan suara klik saat tombol disentuh.", draft.sound) { draft.sound = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
overlay = replace_once(overlay, typing_start, typing_new, "typing keyboard access")

overlay = replace_once(
    overlay,
    '''            .putInt("key_box_scale_percent", draft.keyBoxScale)\n            .putInt("long_press_ms", draft.longPressMs)''',
    '''            .putInt("key_box_scale_percent", draft.keyBoxScale)\n            .putInt("touch_sensitivity", draft.touchSensitivity)\n            .putInt("long_press_ms", draft.longPressMs)''',
    "save touch sensitivity",
)

overlay = replace_once(
    overlay,
    '''        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),\n        longPressMs = prefs.getInt("long_press_ms", 450),''',
    '''        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),\n        touchSensitivity = prefs.getInt("touch_sensitivity", 100),\n        longPressMs = prefs.getInt("long_press_ms", 450),''',
    "load touch sensitivity",
)

overlay = replace_once(
    overlay,
    '''        keyBoxScale = 100,\n        longPressMs = 450,''',
    '''        keyBoxScale = 100,\n        touchSensitivity = 100,\n        longPressMs = 450,''',
    "default touch sensitivity",
)

overlay_path.write_text(overlay, encoding="utf-8")

# AI conversation footer: show the selected model, not the provider label, and refresh it
# immediately when settings change.
service_path = SRC / "RiyanKeyboardService.kt"
service = service_path.read_text(encoding="utf-8")

old_label = '''    private fun activeProviderLabel(): String {\n        val provider = AiProvider.fromId(getSharedPreferences(PREFS, MODE_PRIVATE).getString("provider", null))\n        return "Provider: ${provider.label}"\n    }'''
new_label = '''    private fun activeModelName(): String {\n        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)\n        PrivateProviderStore.selected(prefs, PrivateProviderStore.load(prefs))\n            ?.model\n            ?.trim()\n            ?.takeIf { it.isNotBlank() }\n            ?.let { return it }\n        return when (prefs.getString("provider", null).orEmpty()) {\n            "openrouter" -> prefs.getString("openrouter_model", "openrouter/free").orEmpty()\n            "tabiai" -> prefs.getString("tabi_model", "").orEmpty()\n            "9router" -> prefs.getString("9router_model", "").orEmpty()\n            "bluesminds" -> prefs.getString("bluesminds_model", "").orEmpty()\n            "bai" -> prefs.getString("bai_model", "").orEmpty()\n            "vyceai" -> prefs.getString("vyceai_model", "").orEmpty()\n            "agentrouter" -> prefs.getString("agentrouter_model", "").orEmpty()\n            "seekai" -> prefs.getString("seekai_model", "").orEmpty()\n            "orcarouter" -> prefs.getString("orcarouter_model", "").orEmpty()\n            "aihorde" -> prefs.getString("aihorde_model", "").orEmpty().substringBefore(',')\n            else -> prefs.getString("xkiro_model", "").orEmpty()\n        }.trim().ifBlank { "Belum memilih model" }\n    }\n\n    private fun activeProviderLabel(): String = "Model: ${activeModelName()}"'''
service = replace_once(service, old_label, new_label, "AI model footer label")
service = service.replace(
    'aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"',
    'aiStatus.text = "Model: ${activeModelName()} · ketuk jawaban atau Pakai"',
)
service = service.replace(
    'aiStatus.text = "Jawaban via ${response.provider.label} · ketuk jawaban atau Pakai"',
    'aiStatus.text = "Model: ${activeModelName()} · ketuk jawaban atau Pakai"',
)
service = replace_once(
    service,
    '''            onApply = {\n                loadPreferences()\n                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()\n                renderKeyboard()''',
    '''            onApply = {\n                loadPreferences()\n                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()\n                if (::aiStatus.isInitialized) aiStatus.text = activeProviderLabel()\n                renderKeyboard()''',
    "refresh model footer after settings",
)
service_path.write_text(service, encoding="utf-8")

print("Applied PRIVATE v0.21.35 settings order, fallback, model label, and launcher-related settings")
