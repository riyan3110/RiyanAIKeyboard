from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
SUGGESTIONS = Path("app/src/main/java/com/riyan/aikeyboard/SuggestionEngine.kt")


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new)


service = SERVICE.read_text(encoding="utf-8")

# Always open the emoji keyboard on page 1, where recent emojis are shown first.
service = replace_required(
    service,
    '''        utilityBar.addView(toolbarButton("😊", dp(43)) {
            aiComposeActive = false
            mode = KeyboardMode.EMOJI
            renderKeyboard()
        })''',
    '''        utilityBar.addView(toolbarButton("😊", dp(43)) {
            aiComposeActive = false
            emojiPage = 0
            mode = KeyboardMode.EMOJI
            renderKeyboard()
        })''',
    "emoji toolbar reset",
)

# Page 1 starts with most recently used emojis, then fills the rest with defaults.
service = replace_required(
    service,
    '''    private fun renderEmoji() {
        val emojis = emojiPages[emojiPage]''',
    '''    private fun renderEmoji() {
        val emojis = if (emojiPage == 0) {
            (loadRecentEmojis() + emojiPages[0]).distinct().take(31)
        } else {
            emojiPages[emojiPage]
        }''',
    "recent emoji page",
)

service = service.replace(
    'KeySpec(emoji, action = { commit(emoji) })',
    'KeySpec(emoji, action = { rememberEmoji(emoji); commit(emoji) })',
)

recent_emoji_helpers = '''
    private fun loadRecentEmojis(): List<String> = runCatching {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("recent_emojis", "[]").orEmpty()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }.getOrDefault(emptyList())

    private fun rememberEmoji(emoji: String) {
        val recent = loadRecentEmojis().toMutableList()
        recent.removeAll { it == emoji }
        recent.add(0, emoji)
        val array = JSONArray()
        recent.take(16).forEach(array::put)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("recent_emojis", array.toString())
            .apply()
    }

'''
if "private fun loadRecentEmojis()" not in service:
    marker = "    private fun renderClipboard() {"
    if marker not in service:
        raise RuntimeError("Patch target not found: recent emoji helper insertion")
    service = service.replace(marker, recent_emoji_helpers + marker, 1)

# The enter key follows the active editor action: Search, Send, Next, Done, etc.
service = service.replace(
    'KeySpec("↵", weight = 1.25f, action = { pressEnter() })',
    'KeySpec(enterKeyLabel(), weight = 1.25f, action = { pressEnter() })',
)

enter_label_helper = '''
    private fun enterKeyLabel(): String {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_GO -> "→"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_PREVIOUS -> "←"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "↵"
        }
    }

'''
if "private fun enterKeyLabel()" not in service:
    marker = "    private fun pressEnter() {"
    if marker not in service:
        raise RuntimeError("Patch target not found: enter label helper insertion")
    service = service.replace(marker, enter_label_helper + marker, 1)

service = service.replace(
    "if (enterActionEnabled && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {",
    "if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {",
)

SERVICE.write_text(service, encoding="utf-8")

# Older source versions did not learn phone numbers. Keep this migration only when the old
# SuggestionEngine is still present. Newer versions contain their own filtered phone-number logic.
suggestions = SUGGESTIONS.read_text(encoding="utf-8")
old_learning = '''            if (token.length in 3..80 && (token.any(Char::isLetter) || token.contains('@'))) add(token)'''
new_learning = '''            val digitCount = token.count(Char::isDigit)
            val looksLikePhoneOrNumber = digitCount >= 5 && token.all { it.isDigit() || it in "+-._" }
            if (token.length in 3..80 && (token.any(Char::isLetter) || token.contains('@') || looksLikePhoneOrNumber)) add(token)'''
if old_learning in suggestions:
    suggestions = suggestions.replace(old_learning, new_learning)
elif "isPhoneNumber(token)" not in suggestions and "looksLikePhoneOrNumber" not in suggestions:
    raise RuntimeError("Patch target not found: learn phone and number suggestions")
SUGGESTIONS.write_text(suggestions, encoding="utf-8")

print("Applied personal prediction, recent emoji, dynamic enter, and memory-filter compatible patches.")
