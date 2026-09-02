from pathlib import Path
import re

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
SUGGESTIONS = Path("app/src/main/java/com/riyan/aikeyboard/SuggestionEngine.kt")
ACTIVITY = Path("app/src/main/java/com/riyan/aikeyboard/MainActivity.kt")


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

# Put the prediction bar in the exact empty toolbar area between resize and Settings.
# It is always black so it visually joins the utility bar even when a photo theme is active.
service = replace_required(
    service,
    '''        utilityBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))''',
    '''        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(1), dp(2), dp(1))
            setBackgroundColor(Color.BLACK)
            visibility = if (suggestionsEnabled) View.INVISIBLE else View.GONE
        }
        utilityBar.addView(suggestionBar, LinearLayout.LayoutParams(0, dp(utilityHeightDp() - 4), 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })''',
    "prediction toolbar host",
)

# addSuggestionBar used to create a separate row below the toolbar. The host now exists in toolbar.
if "root.addView(suggestionBar, LinearLayout.LayoutParams(-1, dp(activeSuggestionHeightDp())))" in service:
    pattern = re.compile(
        r'''    private fun addSuggestionBar\(\) \{.*?\n    \}\n\n    private fun addResizePanel\(\)''',
        re.S,
    )
    service, count = pattern.subn(
        '''    private fun addSuggestionBar() {
        refreshSuggestions()
    }

    private fun addResizePanel()''',
        service,
        count=1,
    )
    if count != 1:
        raise RuntimeError("Patch target not found: move suggestion bar into toolbar")

# applyTheme must not turn the prediction host transparent/purple when a theme changes.
service = service.replace(
    'suggestionBar.setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)',
    'suggestionBar.setBackgroundColor(Color.BLACK)',
)

# Prediction items are text-only, maximum two, with no chip/button backgrounds and no timed hiding.
old_prediction_ui = '''        val before = textBeforeTypingCursor()
        val candidates = SuggestionEngine.suggest(before, loadLearnedSuggestions(), savedSuggestionEntries())
        if (candidates.isEmpty()) {
            suggestionBar.visibility = View.INVISIBLE
            return
        }
        suggestionBar.visibility = View.VISIBLE
        candidates.forEach { candidate ->
            suggestionBar.addView(Button(this).apply {
                text = candidate.text
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(4), 0, dp(4), 0)
                setTextColor(Color.WHITE)
                background = roundedBackground(Color.rgb(39, 39, 47), 9f)
                setOnClickListener { acceptPrediction(candidate) }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        handler.postDelayed(suggestionAutoHideRunnable, SUGGESTION_AUTO_HIDE_MS)'''
new_prediction_ui = '''        val before = textBeforeTypingCursor()
        val candidates = SuggestionEngine
            .suggest(before, loadLearnedSuggestions(), savedSuggestionEntries(), limit = 2)
            .take(2)
        if (candidates.isEmpty()) {
            suggestionBar.visibility = View.INVISIBLE
            return
        }
        suggestionBar.visibility = View.VISIBLE
        candidates.forEach { candidate ->
            suggestionBar.addView(TextView(this).apply {
                text = candidate.text
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(dp(3), 0, dp(3), 0)
                setTextColor(Color.WHITE)
                background = null
                isClickable = true
                contentDescription = "Saran: ${candidate.text}"
                setOnClickListener { acceptPrediction(candidate) }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }'''
service = replace_required(service, old_prediction_ui, new_prediction_ui, "text-only prediction UI")

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

# Let users shrink the visible key boxes while keeping the keyboard rows/touch layout stable.
service = replace_required(
    service,
    '''    private var keyTextSizeSp = 21f
    private var touchTolerancePx = 0f''',
    '''    private var keyTextSizeSp = 21f
    private var keyBoxScale = 1f
    private var touchTolerancePx = 0f''',
    "key box scale state",
)
service = replace_required(
    service,
    '''        keyTextSizeSp = prefs.getInt("key_text_size_sp", 21).coerceIn(16, 28).toFloat()
        val sensitivity = prefs.getInt("touch_sensitivity", 100).coerceIn(20, 400)''',
    '''        keyTextSizeSp = prefs.getInt("key_text_size_sp", 21).coerceIn(16, 28).toFloat()
        keyBoxScale = prefs.getInt("key_box_scale_percent", 100).coerceIn(65, 100) / 100f
        val sensitivity = prefs.getInt("touch_sensitivity", 100).coerceIn(20, 400)''',
    "load key box scale",
)
service = replace_required(
    service,
    '''        val frame = FrameLayout(this).apply {
            isClickable = true''',
    '''        val frame = FrameLayout(this).apply {
            scaleX = keyBoxScale
            scaleY = keyBoxScale
            isClickable = true''',
    "apply key box scale",
)

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

# SuggestionEngine now contains filtered personal memory, dynamic ordering, and contextual
# next-word/next-phrase prediction directly in source. Ensure the expected implementation exists.
suggestions = SUGGESTIONS.read_text(encoding="utf-8")
if "continuationAfterContext" not in suggestions or "limit: Int = 2" not in suggestions:
    raise RuntimeError("Contextual prediction engine is missing")
SUGGESTIONS.write_text(suggestions, encoding="utf-8")

# Add a Settings slider for visual key-box size.
activity = ACTIVITY.read_text(encoding="utf-8")
activity = replace_required(
    activity,
    '''        val keyTextSize = settingSlider(root, "Ukuran huruf tombol", 16, 28, prefs.getInt("key_text_size_sp", 21), " sp")
        val touchSensitivity = settingSlider(root, "Sensitivitas dan kecepatan tombol", 20, 400, prefs.getInt("touch_sensitivity", 100), "%")''',
    '''        val keyTextSize = settingSlider(root, "Ukuran huruf tombol", 16, 28, prefs.getInt("key_text_size_sp", 21), " sp")
        val keyBoxScale = settingSlider(root, "Ukuran kotak tombol", 65, 100, prefs.getInt("key_box_scale_percent", 100), "%")
        root.addView(description("Turunkan sampai 65% untuk memperkecil tampilan kotak tombol tanpa membuat area sentuh ikut terlalu kecil."))
        val touchSensitivity = settingSlider(root, "Sensitivitas dan kecepatan tombol", 20, 400, prefs.getInt("touch_sensitivity", 100), "%")''',
    "key box size slider",
)
activity = replace_required(
    activity,
    '''                    .putInt("key_text_size_sp", keyTextSize.progress + 16)
                    .putInt("touch_sensitivity", touchSensitivity.progress + 20)''',
    '''                    .putInt("key_text_size_sp", keyTextSize.progress + 16)
                    .putInt("key_box_scale_percent", keyBoxScale.progress + 65)
                    .putInt("touch_sensitivity", touchSensitivity.progress + 20)''',
    "save key box size",
)
ACTIVITY.write_text(activity, encoding="utf-8")

print("Applied black text-only prediction bar, contextual next phrase prediction, recent emoji, dynamic enter, and key-box scaling patches.")
