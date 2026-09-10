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
    '''        val tabs = listOf(
            Tab.MODEL to "🤖 Model AI",
            Tab.MEMORY to "✍ Memori Gaya",
            Tab.THEME to "🎨 Tema & Tampilan",
            Tab.TYPING to "⌨ Ketikan & Saran"
        )''',
    '''        val tabs = listOf(
            Tab.THEME to "🎨 Tema & Tampilan",
            Tab.TYPING to "⌨ Ketikan & Saran",
            Tab.MEMORY to "✍ Memori Gaya",
            Tab.MODEL to "🤖 Model AI"
        )''',
    "tab order",
)

model_urls = '''        // URL referensi tetap dipertahankan karena ini fitur terpisah dari konfigurasi provider.
        val urls = cardContainer()
        urls.addView(section("URL Referensi Domain", compact = true))
        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))
        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })
        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
model_fallback = '''        body.addView(toggleCard(
            "Aktifkan Fallback Penyedia",
            "Otomatis beralih ke provider lain bila provider/model utama gagal.",
            draft.fallbackEnabled
        ) { draft.fallbackEnabled = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
if model_urls in overlay:
    overlay = overlay.replace(model_urls, model_fallback, 1)
elif model_fallback not in overlay:
    raise RuntimeError("PRIVATE v035 model URL/fallback marker not found")

memory_tail = '''        val phraseCard = cardContainer()
        phraseCard.addView(section("Kalimat Tersimpan", compact = true))
        phraseCard.addView(textInput("Email atau kalimat tersimpan, satu per baris", draft.personalPhrases, multiline = true) { draft.personalPhrases = it })
        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }'''
memory_tail_new = '''        val phraseCard = cardContainer()
        phraseCard.addView(section("Kalimat Tersimpan", compact = true))
        phraseCard.addView(textInput("Email atau kalimat tersimpan, satu per baris", draft.personalPhrases, multiline = true) { draft.personalPhrases = it })
        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val urls = cardContainer()
        urls.addView(section("URL Referensi Domain", compact = true))
        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))
        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })
        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }'''
overlay = replace_once(overlay, memory_tail, memory_tail_new, "URL reference move to memory")

overlay = replace_once(
    overlay,
    '''        keyCard.addView(sliderRow("Skala Kotak Tombol (Maks. 150%)", 65, 150, draft.keyBoxScale, "%") { draft.keyBoxScale = it })
        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })''',
    '''        keyCard.addView(sliderRow("Skala Kotak Tombol (Maks. 150%)", 65, 150, draft.keyBoxScale, "%") { draft.keyBoxScale = it })
        keyCard.addView(sliderRow("Sensitivitas dan Kecepatan Tombol", 20, 400, draft.touchSensitivity, "%") { draft.touchSensitivity = it })
        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })''',
    "theme touch sensitivity slider",
)

# Insert immediately after the function signature instead of depending on whichever
# first typing setting an earlier PRIVATE patch happens to generate.
typing_signature = "    private fun renderTypingTab() {\n"
typing_access_marker = 'keyboardAccess.addView(section("Akses Keyboard", compact = true))'
if typing_access_marker not in overlay:
    if typing_signature not in overlay:
        raise RuntimeError("PRIVATE v035 patch marker not found: typing function")
    typing_access = '''    private fun renderTypingTab() {
        val keyboardAccess = cardContainer()
        keyboardAccess.addView(section("Akses Keyboard", compact = true))
        keyboardAccess.addView(description("Aktifkan AI Ads Keyboard di Android, lalu pilih sebagai keyboard yang digunakan."))
        keyboardAccess.addView(actionButton("Aktifkan Keyboard") {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        keyboardAccess.addView(actionButton("Pilih Keyboard") {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        body.addView(keyboardAccess, LinearLayout.LayoutParams(-1, -2))

'''
    overlay = overlay.replace(typing_signature, typing_access, 1)

overlay = replace_once(
    overlay,
    '''            .putInt("key_box_scale_percent", draft.keyBoxScale)
            .putInt("long_press_ms", draft.longPressMs)''',
    '''            .putInt("key_box_scale_percent", draft.keyBoxScale)
            .putInt("touch_sensitivity", draft.touchSensitivity)
            .putInt("long_press_ms", draft.longPressMs)''',
    "save touch sensitivity",
)

overlay = replace_once(
    overlay,
    '''        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),
        longPressMs = prefs.getInt("long_press_ms", 450),''',
    '''        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),
        touchSensitivity = prefs.getInt("touch_sensitivity", 100),
        longPressMs = prefs.getInt("long_press_ms", 450),''',
    "load touch sensitivity",
)

overlay = replace_once(
    overlay,
    '''        keyBoxScale = 100,
        longPressMs = 450,''',
    '''        keyBoxScale = 100,
        touchSensitivity = 100,
        longPressMs = 450,''',
    "default touch sensitivity",
)

overlay_path.write_text(overlay, encoding="utf-8")

# AI conversation footer: show the selected model, not the provider label, and refresh it
# immediately when settings change.
service_path = SRC / "RiyanKeyboardService.kt"
service = service_path.read_text(encoding="utf-8")

old_label = '''    private fun activeProviderLabel(): String {
        val provider = AiProvider.fromId(getSharedPreferences(PREFS, MODE_PRIVATE).getString("provider", null))
        return "Provider: ${provider.label}"
    }'''
new_label = '''    private fun activeModelName(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        PrivateProviderStore.selected(prefs, PrivateProviderStore.load(prefs))
            ?.model
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return when (prefs.getString("provider", null).orEmpty()) {
            "openrouter" -> prefs.getString("openrouter_model", "openrouter/free").orEmpty()
            "tabiai" -> prefs.getString("tabi_model", "").orEmpty()
            "9router" -> prefs.getString("9router_model", "").orEmpty()
            "bluesminds" -> prefs.getString("bluesminds_model", "").orEmpty()
            "bai" -> prefs.getString("bai_model", "").orEmpty()
            "vyceai" -> prefs.getString("vyceai_model", "").orEmpty()
            "agentrouter" -> prefs.getString("agentrouter_model", "").orEmpty()
            "seekai" -> prefs.getString("seekai_model", "").orEmpty()
            "orcarouter" -> prefs.getString("orcarouter_model", "").orEmpty()
            "aihorde" -> prefs.getString("aihorde_model", "").orEmpty().substringBefore(',')
            else -> prefs.getString("xkiro_model", "").orEmpty()
        }.trim().ifBlank { "Belum memilih model" }
    }

    private fun activeProviderLabel(): String = "Model: ${activeModelName()}"'''
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
    '''            onApply = {
                loadPreferences()
                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
                renderKeyboard()''',
    '''            onApply = {
                loadPreferences()
                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
                if (::aiStatus.isInitialized) aiStatus.text = activeProviderLabel()
                renderKeyboard()''',
    "refresh model footer after settings",
)
service_path.write_text(service, encoding="utf-8")

print("Applied PRIVATE v0.21.35 settings order, fallback, model label, and launcher-related settings")
