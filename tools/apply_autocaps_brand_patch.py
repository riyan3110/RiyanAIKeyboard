#!/usr/bin/env python3
from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
SETTINGS = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)


# --- Keyboard service ---
s = SERVICE.read_text(encoding="utf-8")

s = replace_once(
    s,
    "    private var automaticCapitalizationEnabled = true\n    private var punctuationSpaceEnabled = false",
    "    private var automaticCapitalizationEnabled = true\n    private var brandTextEnabled = true\n    private var punctuationSpaceEnabled = false",
    "service brand state",
)

s = replace_once(
    s,
    '        automaticCapitalizationEnabled = prefs.getBoolean("automatic_capitalization_enabled", true)\n        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)',
    '        automaticCapitalizationEnabled = prefs.getBoolean("automatic_capitalization_enabled", true)\n        brandTextEnabled = prefs.getBoolean("brand_text_enabled", true)\n        if (::bottomBrandBar.isInitialized) {\n            bottomBrandBar.visibility = if (brandTextEnabled) View.VISIBLE else View.GONE\n        }\n        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)',
    "service load brand pref",
)

s = replace_once(
    s,
    "        if (!fastTypingMode) refreshSuggestionsSoon()\n        refreshEnterKeyIfNeeded()\n    }",
    "        if (!fastTypingMode) refreshSuggestionsSoon()\n        refreshEnterKeyIfNeeded()\n        if (automaticCapitalizationEnabled && !capsLock) refreshAutomaticShiftSoon()\n    }",
    "selection auto caps refresh",
)

s = replace_once(
    s,
    "    private fun updateAutomaticShift() {\n        if (capsLock) return",
    "    private fun refreshAutomaticShiftSoon(delayMs: Long = 35L) {\n        if (!automaticCapitalizationEnabled || capsLock) return\n        handler.postDelayed({\n            if (!automaticCapitalizationEnabled || capsLock) return@postDelayed\n            val previousShift = shift\n            updateAutomaticShift()\n            if (mode == KeyboardMode.LETTERS && previousShift != shift) renderKeyboard()\n        }, delayMs)\n    }\n\n    private fun updateAutomaticShift() {\n        if (capsLock) return",
    "auto caps helper",
)

s = replace_once(
    s,
    "        refreshSuggestionsSoon()\n    }\n\n    private fun deleteWord() {",
    "        refreshSuggestionsSoon()\n        refreshAutomaticShiftSoon()\n    }\n\n    private fun deleteWord() {",
    "backspace auto caps refresh",
)

s = replace_once(
    s,
    "        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }\n        refreshSuggestionsSoon()\n    }",
    "        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }\n        refreshSuggestionsSoon()\n        refreshAutomaticShiftSoon()\n    }",
    "delete word auto caps refresh",
)

s = replace_once(
    s,
    "    private fun pressEnter() {\n        if (searchWebComposeActive) {",
    "    private fun pressEnter() {\n        // Capitalization must be armed before any early-return enter path (web/search/internal fields).\n        if (automaticCapitalizationEnabled && !capsLock) {\n            shift = true\n            if (mode == KeyboardMode.LETTERS) renderKeyboard()\n        }\n        if (searchWebComposeActive) {",
    "enter auto caps",
)

s = replace_once(
    s,
    "        bottomBrandBar = LinearLayout(this).apply {\n            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL",
    "        bottomBrandBar = LinearLayout(this).apply {\n            visibility = if (brandTextEnabled) View.VISIBLE else View.GONE\n            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL",
    "brand bar visibility",
)

s = replace_once(
    s,
    '                text = "AI Ads Keyboard · v0.21.6 test"',
    '                text = "AI Ads Keyboard"',
    "brand text remove version",
)

s = replace_once(
    s,
    "    private fun brandBarHeightDp(): Int = if (isLandscape()) 18 else 24",
    "    private fun brandBarHeightDp(): Int = if (!brandTextEnabled) 0 else if (isLandscape()) 18 else 24",
    "brand bar dynamic height",
)

SERVICE.write_text(s, encoding="utf-8")

# --- Settings overlay ---
k = SETTINGS.read_text(encoding="utf-8")

k = replace_once(
    k,
    "        var autoCaps: Boolean,\n        var punctuationSpace: Boolean,",
    "        var autoCaps: Boolean,\n        var showBrandText: Boolean,\n        var punctuationSpace: Boolean,",
    "draft brand field",
)

k = replace_once(
    k,
    '        body.addView(toggleCard("Huruf Kapital Otomatis", "Mengkapitalkan huruf pertama kalimat.", draft.autoCaps) { draft.autoCaps = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })',
    '        body.addView(toggleCard("Huruf Kapital Otomatis", "Mengkapitalkan huruf pertama kalimat, termasuk setelah Enter dan setelah teks dihapus sampai awal.", draft.autoCaps) { draft.autoCaps = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })\n        body.addView(toggleCard("Tampilkan AI Ads Keyboard", "Menampilkan tulisan AI Ads Keyboard di bawah tombol spasi. Jika dimatikan, area ini dihapus dan keyboard turun sampai bagian paling bawah.", draft.showBrandText) { draft.showBrandText = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })',
    "typing brand toggle",
)

k = replace_once(
    k,
    '            .putBoolean("automatic_capitalization_enabled", draft.autoCaps)\n            .putBoolean("punctuation_space_enabled", draft.punctuationSpace)',
    '            .putBoolean("automatic_capitalization_enabled", draft.autoCaps)\n            .putBoolean("brand_text_enabled", draft.showBrandText)\n            .putBoolean("punctuation_space_enabled", draft.punctuationSpace)',
    "save brand pref",
)

k = replace_once(
    k,
    '        autoCaps = prefs.getBoolean("automatic_capitalization_enabled", true),\n        punctuationSpace = prefs.getBoolean("punctuation_space_enabled", false),',
    '        autoCaps = prefs.getBoolean("automatic_capitalization_enabled", true),\n        showBrandText = prefs.getBoolean("brand_text_enabled", true),\n        punctuationSpace = prefs.getBoolean("punctuation_space_enabled", false),',
    "load brand pref",
)

k = replace_once(
    k,
    "        autoCaps = true,\n        punctuationSpace = false,",
    "        autoCaps = true,\n        showBrandText = true,\n        punctuationSpace = false,",
    "reset brand pref",
)

SETTINGS.write_text(k, encoding="utf-8")
print("Applied auto-capitalization + bottom brand visibility patch")
