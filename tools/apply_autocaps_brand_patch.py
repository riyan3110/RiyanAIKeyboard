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
    "    private var lastSpaceAt = 0L\n    private val conversationHistory = mutableListOf<Pair<String, String>>()",
    "    private var lastSpaceAt = 0L\n    private var autoCapsForceUntilMs = 0L\n    private val conversationHistory = mutableListOf<Pair<String, String>>()",
    "service auto caps force state",
)

s = replace_once(
    s,
    '        automaticCapitalizationEnabled = prefs.getBoolean("automatic_capitalization_enabled", true)\n        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)',
    '        automaticCapitalizationEnabled = prefs.getBoolean("automatic_capitalization_enabled", true)\n        brandTextEnabled = prefs.getBoolean("brand_text_enabled", true)\n        if (::bottomBrandBar.isInitialized) {\n            bottomBrandBar.visibility = if (brandTextEnabled) View.VISIBLE else View.GONE\n            bottomBrandBar.layoutParams = bottomBrandBar.layoutParams.apply { height = dp(brandBarHeightDp()) }\n        }\n        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)',
    "service load brand pref",
)

s = replace_once(
    s,
    "        if (!fastTypingMode) refreshSuggestionsSoon()\n        refreshEnterKeyIfNeeded()\n    }",
    "        if (!fastTypingMode) refreshSuggestionsSoon()\n        refreshEnterKeyIfNeeded()\n        if (automaticCapitalizationEnabled && !capsLock) refreshAutomaticShiftRetries()\n    }",
    "selection auto caps refresh",
)

old_auto = '''    private fun updateAutomaticShift() {
        if (capsLock) return
        val before = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        shift = before.isBlank() || before.trimEnd().lastOrNull() in listOf('.', '?', '!') || before.endsWith("\\n")
    }'''
new_auto = '''    private fun shouldCapitalizeAfter(before: String): Boolean =
        before.isBlank() || before.endsWith("\\n") || before.trimEnd().lastOrNull() in listOf('.', '?', '!')

    private fun armAutomaticCapitalizationBoundary() {
        if (!automaticCapitalizationEnabled || capsLock) return
        autoCapsForceUntilMs = SystemClock.uptimeMillis() + 2500L
        if (!shift) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun refreshAutomaticShiftRetries() {
        if (!automaticCapitalizationEnabled || capsLock) return
        listOf(20L, 90L, 220L, 480L).forEach { delayMs ->
            handler.postDelayed({
                if (!automaticCapitalizationEnabled || capsLock) return@postDelayed
                val previousShift = shift
                updateAutomaticShift()
                if (mode == KeyboardMode.LETTERS && previousShift != shift) renderKeyboard()
            }, delayMs)
        }
    }

    private fun updateAutomaticShift() {
        if (capsLock) return
        if (SystemClock.uptimeMillis() < autoCapsForceUntilMs) {
            shift = true
            return
        }

        val internal = activeInternalInput()
        if (internal != null) {
            val cursor = internal.selectionStart.coerceIn(0, internal.text.length)
            val before = internal.text.subSequence(0, cursor).toString()
            shift = shouldCapitalizeAfter(before)
            return
        }

        val ic = currentInputConnection
        val before = runCatching { ic?.getTextBeforeCursor(160, 0)?.toString().orEmpty() }.getOrDefault("")
        val systemCaps = runCatching {
            ic?.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) ?: 0
        }.getOrDefault(0)
        shift = systemCaps != 0 || shouldCapitalizeAfter(before)
    }'''
s = replace_once(s, old_auto, new_auto, "robust auto caps helpers")

s = replace_once(
    s,
    "                commit(shown)\n                if (shift && !capsLock) {",
    "                commit(shown)\n                autoCapsForceUntilMs = 0L\n                if (shift && !capsLock) {",
    "clear enter auto caps force after first letter",
)

s = replace_once(
    s,
    "        refreshSuggestionsSoon()\n    }\n\n    private fun deleteWord() {",
    "        refreshSuggestionsSoon()\n        refreshAutomaticShiftRetries()\n    }\n\n    private fun deleteWord() {",
    "backspace auto caps refresh",
)

s = replace_once(
    s,
    "        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }\n        refreshSuggestionsSoon()\n    }",
    "        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }\n        refreshSuggestionsSoon()\n        refreshAutomaticShiftRetries()\n    }",
    "delete word auto caps refresh",
)

s = replace_once(
    s,
    "    private fun pressEnter() {\n        if (searchWebComposeActive) {",
    "    private fun pressEnter() {\n        armAutomaticCapitalizationBoundary()\n        refreshAutomaticShiftRetries()\n        if (searchWebComposeActive) {",
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
    '            text = "v0.20"',
    '            text = "v${BuildConfig.VERSION_NAME.substringBefore("-")}"',
    "settings build version label",
)

k = replace_once(
    k,
    "    private fun renderTypingTab() {\n        body.addView(toggleCard(\"Efek Suara Tombol (Click Sound)\"",
    "    private fun renderTypingTab() {\n        body.addView(toggleCard(\"Tampilkan Tulisan AI Ads Keyboard\", \"Menampilkan tulisan AI Ads Keyboard di bawah tombol spasi. Jika dimatikan, tulisan dan seluruh ruang bawahnya hilang sehingga keyboard turun sampai bagian paling bawah.\", draft.showBrandText) { draft.showBrandText = it })\n        body.addView(toggleCard(\"Efek Suara Tombol (Click Sound)\"",
    "typing brand toggle first card",
)

k = replace_once(
    k,
    '        body.addView(toggleCard("Huruf Kapital Otomatis", "Mengkapitalkan huruf pertama kalimat.", draft.autoCaps) { draft.autoCaps = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })',
    '        body.addView(toggleCard("Huruf Kapital Otomatis", "Mengkapitalkan huruf pertama kalimat, termasuk setelah Enter dan setelah teks dihapus sampai awal.", draft.autoCaps) { draft.autoCaps = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })',
    "typing auto caps description",
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
print("Applied robust auto-capitalization + always-visible settings toggle + collapsible bottom brand patch")
