package com.riyan.aikeyboard

import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.hypot

class RiyanKeyboardService : InputMethodService() {
    private enum class KeyboardMode { LETTERS, SYMBOLS, EMOJI, CLIPBOARD }

    private data class KeySpec(
        val label: String,
        val alternate: String? = null,
        val weight: Float = 1f,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null
    )

    private data class ClipEntry(val text: String, val pinned: Boolean)

    private lateinit var root: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var utilityBar: LinearLayout
    private lateinit var resizePanel: LinearLayout
    private lateinit var suggestionBar: LinearLayout
    private lateinit var bottomBrandBar: LinearLayout
    private lateinit var aiPanel: LinearLayout
    private lateinit var aiStatus: TextView
    private lateinit var aiAnswer: TextView
    private lateinit var aiAnswerScroll: ScrollView
    private lateinit var aiInput: EditText
    private lateinit var aiFullscreenButton: Button
    private lateinit var heightLabel: TextView

    private var mode = KeyboardMode.LETTERS
    private var shift = false
    private var capsLock = false
    private var lastShiftTapAt = 0L
    private var pendingText: String? = null
    private var emojiPage = 0
    private var baseKeyboardHeightDp = DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP
    private var minKeyboardHeightDp = MIN_KEYBOARD_HEIGHT_PORTRAIT_DP
    private var maxKeyboardHeightDp = MAX_KEYBOARD_HEIGHT_PORTRAIT_DP
    private var heightPreferenceKey = HEIGHT_PORTRAIT_KEY
    private var keyTextSizeSp = 21f
    private var touchTolerancePx = 0f
    private var instantKeyResponse = false
    private var longPressDurationMs = 450L
    private var aiPanelVisible = false
    private var aiFullscreen = false
    private var resizePanelVisible = false
    private var aiComposeActive = false
    private var numberRowEnabled = true
    private var longPressSymbolsEnabled = true
    private var soundEnabled = false
    private var vibrationEnabled = true
    private var vibrationDurationMs = 28L
    private var doubleSpacePeriodEnabled = false
    private var automaticCapitalizationEnabled = true
    private var punctuationSpaceEnabled = false
    private var clipboardHistoryEnabled = true
    private var suggestionsEnabled = true
    private var personalizedLearningEnabled = true
    private var styleMemoryEnabled = true
    private var enterActionEnabled = false
    private var lastSpaceAt = 0L
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    private val handler = Handler(Looper.getMainLooper())
    private val suggestionRefreshRunnable = Runnable { refreshSuggestions() }
    private val suggestionAutoHideRunnable = Runnable {
        if (::suggestionBar.isInitialized) {
            suggestionBar.visibility = if (suggestionsEnabled) View.INVISIBLE else View.GONE
        }
    }
    private var bg = Color.rgb(18, 18, 23)
    private var keyBg = Color.rgb(50, 50, 60)
    private var specialKeyBg = Color.rgb(43, 68, 80)
    private var pressedKeyBg = Color.rgb(93, 74, 196)
    private var purple = Color.rgb(89, 68, 196)
    private var keyTextColor = Color.WHITE
    private var themeUsesPhoto = false

    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (clipboardHistoryEnabled) addCurrentClipboardToHistory()
        if (::keyboardPanel.isInitialized && mode == KeyboardMode.CLIPBOARD) renderKeyboard()
    }

    private val emojiPages = listOf(
        listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "🤩"),
        listOf("😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫡"),
        listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "👏", "🙌", "🫶", "🤝", "🙏", "✍️", "💪", "🦾", "❤️", "🧡", "💛", "💚", "💙"),
        listOf("🎉", "🎊", "🔥", "✨", "⭐", "🌟", "💯", "✅", "❌", "⚠️", "💡", "📌", "🎁", "🎂", "☕", "🍜", "🍕", "🍔", "🚗", "🏍️", "✈️", "🏠", "📱", "💻", "🎮", "⚽", "🎵", "📷", "🌞", "🌙", "🌈")
    )

    override fun onCreate() {
        super.onCreate()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadPreferences()
        if (::root.isInitialized) {
            applyRootHeight()
            renderKeyboard()
            refreshSuggestionsSoon()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshSuggestionsSoon()
    }

    override fun onCreateInputView(): View {
        loadPreferences()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            updateRootPadding(this)
        }

        addAiConversationPanel()
        addUtilityBar()
        addSuggestionBar()
        addResizePanel()

        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, 0)
        }
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomBrandBar()
        applyTheme()
        applyRootHeight()
        renderKeyboard()
        refreshSuggestionsSoon()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadPreferences()
        if (automaticCapitalizationEnabled) updateAutomaticShift()
        if (clipboardHistoryEnabled) addCurrentClipboardToHistory()
        if (::root.isInitialized) {
            applyRootHeight()
            renderKeyboard()
            refreshSuggestionsSoon()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        heightPreferenceKey = if (landscape) HEIGHT_LANDSCAPE_KEY else HEIGHT_PORTRAIT_KEY
        minKeyboardHeightDp = if (landscape) MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP else MIN_KEYBOARD_HEIGHT_PORTRAIT_DP
        maxKeyboardHeightDp = if (landscape) MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP else MAX_KEYBOARD_HEIGHT_PORTRAIT_DP
        val defaultHeight = if (landscape) DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE_DP else DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP
        val layoutVersion = prefs.getInt("keyboard_layout_version", 0)
        if (layoutVersion < 5) {
            val legacy = prefs.getInt("keyboard_height_dp", OLD_DEFAULT_KEYBOARD_HEIGHT_DP)
            val portrait = if (legacy == OLD_DEFAULT_KEYBOARD_HEIGHT_DP) {
                DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP
            } else {
                legacy.coerceIn(MIN_KEYBOARD_HEIGHT_PORTRAIT_DP, MAX_KEYBOARD_HEIGHT_PORTRAIT_DP)
            }
            prefs.edit()
                .putInt(HEIGHT_PORTRAIT_KEY, portrait)
                .putInt(HEIGHT_LANDSCAPE_KEY, DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE_DP)
                .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                .apply()
        }
        if (layoutVersion < 6) {
            val legacyLandscape = prefs.getInt(HEIGHT_LANDSCAPE_KEY, 205)
            val migratedLandscape = when {
                legacyLandscape >= 175 -> legacyLandscape - 30
                else -> legacyLandscape
            }.coerceIn(MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP, MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP)
            prefs.edit()
                .putInt(HEIGHT_LANDSCAPE_KEY, migratedLandscape)
                .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                .apply()
        }
        if (layoutVersion < KEYBOARD_LAYOUT_VERSION) {
            val oldPortrait = prefs.getInt(HEIGHT_PORTRAIT_KEY, 250)
            val oldLandscape = prefs.getInt(HEIGHT_LANDSCAPE_KEY, 155)
            prefs.edit()
                .putInt(
                    HEIGHT_PORTRAIT_KEY,
                    if (oldPortrait <= 210) 185 else oldPortrait.coerceIn(MIN_KEYBOARD_HEIGHT_PORTRAIT_DP, MAX_KEYBOARD_HEIGHT_PORTRAIT_DP)
                )
                .putInt(
                    HEIGHT_LANDSCAPE_KEY,
                    if (oldLandscape <= 135) 105 else oldLandscape.coerceIn(MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP, MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP)
                )
                .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                .apply()
        }
        baseKeyboardHeightDp = prefs.getInt(heightPreferenceKey, defaultHeight)
            .coerceIn(minKeyboardHeightDp, maxKeyboardHeightDp)
        keyTextSizeSp = prefs.getInt("key_text_size_sp", 21).coerceIn(16, 28).toFloat()
        val sensitivity = prefs.getInt("touch_sensitivity", 100).coerceIn(20, 400)
        touchTolerancePx = dpFloat(12f + sensitivity * 0.18f)
        instantKeyResponse = sensitivity >= INSTANT_RESPONSE_THRESHOLD
        longPressDurationMs = prefs.getInt("long_press_ms", 450).coerceIn(200, 900).toLong()
        numberRowEnabled = prefs.getBoolean("number_row_enabled", true)
        longPressSymbolsEnabled = prefs.getBoolean("long_press_symbols_enabled", true)
        soundEnabled = prefs.getBoolean("sound_enabled", false)
        vibrationEnabled = prefs.getBoolean("vibration_enabled", true)
        vibrationDurationMs = prefs.getInt("vibration_duration_ms", 28).coerceIn(5, 80).toLong()
        doubleSpacePeriodEnabled = prefs.getBoolean("double_space_period_enabled", false)
        automaticCapitalizationEnabled = prefs.getBoolean("automatic_capitalization_enabled", true)
        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)
        clipboardHistoryEnabled = prefs.getBoolean("clipboard_history_enabled", true)
        suggestionsEnabled = prefs.getBoolean("suggestions_enabled", true)
        personalizedLearningEnabled = prefs.getBoolean("personalized_learning_enabled", true)
        styleMemoryEnabled = prefs.getBoolean("style_memory_enabled", true)
        enterActionEnabled = prefs.getBoolean("enter_action_enabled", false)
        val palette = KeyboardTheme.palette(prefs)
        bg = palette.background
        keyBg = palette.key
        specialKeyBg = palette.specialKey
        pressedKeyBg = palette.pressedKey
        purple = palette.accent
        keyTextColor = palette.text
        themeUsesPhoto = palette.usesPhoto
        if (::root.isInitialized) {
            updateRootPadding(root)
            applyTheme()
        }
    }

    private fun applyTheme() {
        if (!::root.isInitialized) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val palette = KeyboardTheme.palette(prefs)
        root.background = KeyboardTheme.background(this, prefs, palette)
        if (::suggestionBar.isInitialized) {
            suggestionBar.setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)
        }
        if (::bottomBrandBar.isInitialized) {
            bottomBrandBar.setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)
        }
    }

    private fun updateRootPadding(target: LinearLayout) {
        target.setPadding(0, dp(1), 0, dp(1))
    }

    private fun addBottomBrandBar() {
        bottomBrandBar = LinearLayout(this).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)
            addView(TextView(this@RiyanKeyboardService).apply {
                text = "AI Ads Keyboard · v0.11"
                textSize = 9f
                setTextColor(Color.rgb(145, 137, 190))
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-2, dp(17)))
        }
        root.addView(bottomBrandBar, LinearLayout.LayoutParams(-1, dp(brandBarHeightDp())))
    }

    private fun addAiConversationPanel() {
        aiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(3), dp(1), dp(2))
            background = roundedBackground(Color.rgb(31, 31, 36), 9f)
            visibility = View.GONE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "✦ Obrolan AI"
            textSize = 14f
            setTextColor(keyTextColor)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(aiHeaderHeightDp()), 1f))
        header.addView(compactButton("Hapus") {
            conversationHistory.clear()
            pendingText = null
            aiAnswer.text = "Jawaban AI akan muncul di sini."
            aiStatus.text = activeProviderLabel()
        }, LinearLayout.LayoutParams(dp(58), dp(aiHeaderHeightDp())))
        aiFullscreenButton = compactButton("⛶") { toggleAiFullscreen() }
        aiFullscreenButton.contentDescription = "Buka obrolan AI layar penuh"
        header.addView(aiFullscreenButton, LinearLayout.LayoutParams(dp(38), dp(aiHeaderHeightDp())).apply {
            leftMargin = dp(2)
        })
        header.addView(compactButton("✕") { toggleAiPanel(false) }, LinearLayout.LayoutParams(dp(38), dp(aiHeaderHeightDp())))
        aiPanel.addView(header)

        aiAnswer = TextView(this).apply {
            text = "Jawaban AI akan muncul di sini."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setOnClickListener { insertPendingResult() }
        }
        aiAnswerScroll = ScrollView(this).apply {
            background = roundedBackground(Color.rgb(38, 38, 43), 10f)
            addView(aiAnswer, ViewGroup.LayoutParams(-1, -2))
        }
        aiPanel.addView(aiAnswerScroll, LinearLayout.LayoutParams(-1, dp(aiAnswerHeightDp())).apply { topMargin = dp(1) })

        val composeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(1), dp(3), dp(1))
            background = roundedBackground(Color.rgb(45, 45, 51), 15f)
        }
        aiInput = EditText(this).apply {
            hint = "Ketik pesan untuk AI…"
            textSize = 13f
            maxLines = 3
            minLines = 1
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            showSoftInputOnFocus = false
            setPadding(dp(8), 0, dp(8), 0)
            background = null
            setOnClickListener { aiComposeActive = true }
            setOnFocusChangeListener { _, hasFocus -> aiComposeActive = hasFocus }
        }
        composeCard.addView(aiInput, LinearLayout.LayoutParams(-1, dp(aiInputHeightDp())))

        val composeFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composeFooter.addView(compactButton("＋") { pasteClipboardIntoAiInput() }, LinearLayout.LayoutParams(dp(34), dp(29)))
        aiStatus = TextView(this).apply {
            text = activeProviderLabel()
            textSize = 10f
            maxLines = 1
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(3), 0)
        }
        composeFooter.addView(aiStatus, LinearLayout.LayoutParams(0, dp(29), 1f))
        composeFooter.addView(compactButton("Pakai") { insertPendingResult() }, LinearLayout.LayoutParams(dp(55), dp(29)))
        composeFooter.addView(compactButton("↑") { runAiConversation() }, LinearLayout.LayoutParams(dp(38), dp(29)).apply { leftMargin = dp(4) })
        composeCard.addView(composeFooter)
        aiPanel.addView(composeCard, LinearLayout.LayoutParams(-1, dp(aiComposeHeightDp())).apply { topMargin = dp(2) })

        val quickActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Perbaiki", "Balas", "Terjemah", "Ringkas", "Santai", "Sopan").forEach { action ->
            quickActions.addView(compactButton(action) { runAi(action) }, LinearLayout.LayoutParams(0, dp(aiQuickActionHeightDp()), 1f).apply {
                setMargins(dp(1), dp(2), dp(1), 0)
            })
        }
        aiPanel.addView(quickActions)
        root.addView(aiPanel, LinearLayout.LayoutParams(-1, dp(currentAiPanelHeightDp())))
    }

    private fun addUtilityBar() {
        utilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        utilityBar.addView(toolbarButton("✦ AI", dp(57)) { toggleAiPanel() })
        utilityBar.addView(toolbarButton("⌨", dp(43)) {
            aiComposeActive = false
            mode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("😊", dp(43)) {
            aiComposeActive = false
            mode = KeyboardMode.EMOJI
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("📋", dp(43)) {
            aiComposeActive = false
            addCurrentClipboardToHistory()
            mode = KeyboardMode.CLIPBOARD
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("↕", dp(43)) { toggleResizePanel() })
        utilityBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        utilityBar.addView(toolbarButton("⚙", dp(43)) { openSettings() })
        root.addView(utilityBar, LinearLayout.LayoutParams(-1, dp(utilityHeightDp())))
    }

    private fun addSuggestionBar() {
        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(2), dp(3), dp(2))
            setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)
            visibility = if (suggestionsEnabled) View.INVISIBLE else View.GONE
        }
        root.addView(suggestionBar, LinearLayout.LayoutParams(-1, dp(activeSuggestionHeightDp())))
        refreshSuggestions()
    }

    private fun addResizePanel() {
        resizePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            background = roundedBackground(Color.rgb(25, 58, 82), 9f)
            visibility = View.GONE
        }
        resizePanel.addView(compactButton("−") { changeKeyboardHeight(-10) }, LinearLayout.LayoutParams(dp(48), dp(34)))
        heightLabel = TextView(this).apply {
            text = keyboardHeightLabel()
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(keyTextColor)
            setTypeface(typeface, Typeface.BOLD)
            setOnTouchListener(heightDragListener())
        }
        resizePanel.addView(heightLabel, LinearLayout.LayoutParams(0, dp(34), 1f))
        resizePanel.addView(compactButton("+") { changeKeyboardHeight(10) }, LinearLayout.LayoutParams(dp(48), dp(34)))
        resizePanel.addView(compactButton("Selesai") { toggleResizePanel(false) }, LinearLayout.LayoutParams(dp(70), dp(34)))
        root.addView(resizePanel, LinearLayout.LayoutParams(-1, dp(resizePanelHeightDp())))
    }

    private fun heightDragListener(): View.OnTouchListener {
        var startY = 0f
        var startHeight = 0
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = baseKeyboardHeightDp
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaDp = ((startY - event.rawY) / resources.displayMetrics.density).toInt()
                    setKeyboardHeight(startHeight + deltaDp, persist = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveKeyboardHeight()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleAiPanel(force: Boolean? = null) {
        aiPanelVisible = force ?: !aiPanelVisible
        aiPanel.visibility = if (aiPanelVisible) View.VISIBLE else View.GONE
        if (aiPanelVisible) {
            aiStatus.text = activeProviderLabel()
        } else {
            aiFullscreen = false
            aiComposeActive = false
            aiInput.clearFocus()
        }
        applyAiDisplayMode()
        applyRootHeight()
    }

    private fun toggleAiFullscreen() {
        aiPanelVisible = true
        aiPanel.visibility = View.VISIBLE
        aiFullscreen = !aiFullscreen
        aiStatus.text = activeProviderLabel()
        applyAiDisplayMode()
        applyRootHeight()
    }

    private fun applyAiDisplayMode() {
        if (!::aiPanel.isInitialized) return
        if (::aiFullscreenButton.isInitialized) {
            aiFullscreenButton.text = if (aiFullscreen) "↙" else "⛶"
            aiFullscreenButton.contentDescription = if (aiFullscreen) {
                "Kecilkan obrolan AI"
            } else {
                "Buka obrolan AI layar penuh"
            }
        }
        if (::aiAnswerScroll.isInitialized) {
            aiAnswerScroll.layoutParams = (aiAnswerScroll.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(aiAnswerHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (aiFullscreen) 0 else dp(aiAnswerHeightDp())
                weight = if (aiFullscreen) 1f else 0f
                topMargin = dp(1)
            }
        }
        if (::suggestionBar.isInitialized) {
            suggestionBar.visibility = when {
                aiFullscreen -> View.GONE
                !suggestionsEnabled -> View.GONE
                else -> View.INVISIBLE
            }
        }
        if (::resizePanel.isInitialized) {
            resizePanel.visibility = if (!aiFullscreen && resizePanelVisible) View.VISIBLE else View.GONE
        }
        if (::keyboardPanel.isInitialized) {
            keyboardPanel.layoutParams = if (aiFullscreen) {
                LinearLayout.LayoutParams(-1, dp(fullscreenKeyboardPanelHeightDp()))
            } else {
                LinearLayout.LayoutParams(-1, 0, 1f)
            }
        }
    }

    private fun toggleResizePanel(force: Boolean? = null) {
        resizePanelVisible = force ?: !resizePanelVisible
        resizePanel.visibility = if (resizePanelVisible) View.VISIBLE else View.GONE
        heightLabel.text = keyboardHeightLabel()
        applyRootHeight()
    }

    private fun changeKeyboardHeight(deltaDp: Int) {
        setKeyboardHeight(baseKeyboardHeightDp + deltaDp, persist = true)
    }

    private fun setKeyboardHeight(valueDp: Int, persist: Boolean) {
        baseKeyboardHeightDp = valueDp.coerceIn(minKeyboardHeightDp, maxKeyboardHeightDp)
        if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
        if (persist) saveKeyboardHeight()
        applyRootHeight()
    }

    private fun saveKeyboardHeight() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(heightPreferenceKey, baseKeyboardHeightDp)
            .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
            .apply()
    }

    private fun keyboardHeightLabel(): String {
        val modeLabel = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "lanskap" else "potret"
        return "Tinggi $modeLabel $baseKeyboardHeightDp dp · geser"
    }

    private fun applyRootHeight() {
        if (!::root.isInitialized) return
        updateRootPadding(root)
        if (::utilityBar.isInitialized) utilityBar.layoutParams = utilityBar.layoutParams.apply {
            height = dp(utilityHeightDp())
        }
        if (::suggestionBar.isInitialized) suggestionBar.layoutParams = suggestionBar.layoutParams.apply {
            height = dp(activeSuggestionHeightDp())
        }
        if (::resizePanel.isInitialized) resizePanel.layoutParams = resizePanel.layoutParams.apply {
            height = dp(resizePanelHeightDp())
        }
        if (::bottomBrandBar.isInitialized) bottomBrandBar.layoutParams = bottomBrandBar.layoutParams.apply {
            height = dp(brandBarHeightDp())
        }
        if (::aiPanel.isInitialized) {
            aiPanel.layoutParams = (aiPanel.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(currentAiPanelHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (aiFullscreen) 0 else dp(currentAiPanelHeightDp())
                weight = if (aiFullscreen) 1f else 0f
            }
        }
        if (aiFullscreen) {
            applyAiDisplayMode()
            val screenHeight = resources.displayMetrics.heightPixels
            root.minimumHeight = screenHeight
            root.layoutParams = (root.layoutParams ?: ViewGroup.LayoutParams(-1, screenHeight)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = screenHeight
            }
            root.requestLayout()
            window?.window?.decorView?.requestLayout()
            return
        }
        applyAiDisplayMode()
        val suggestionReduction = if (suggestionsEnabled) 0 else suggestionHeightDp()
        val extra = brandBarHeightDp() +
            (if (aiPanelVisible) currentAiPanelHeightDp() else 0) +
            (if (resizePanelVisible) resizePanelHeightDp() else 0)
        val height = dp((baseKeyboardHeightDp - suggestionReduction).coerceAtLeast(1) + extra)
        root.minimumHeight = height
        root.layoutParams = (root.layoutParams ?: ViewGroup.LayoutParams(-1, height)).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            this.height = height
        }
        root.requestLayout()
        window?.window?.decorView?.requestLayout()
        root.post {
            root.requestLayout()
            root.parent?.let { parent -> if (parent is View) parent.requestLayout() }
            window?.window?.decorView?.requestLayout()
        }
    }

    private fun renderKeyboard() {
        if (!::keyboardPanel.isInitialized) return
        keyboardPanel.removeAllViews()
        when (mode) {
            KeyboardMode.LETTERS -> renderLetters()
            KeyboardMode.SYMBOLS -> renderSymbols()
            KeyboardMode.EMOJI -> renderEmoji()
            KeyboardMode.CLIPBOARD -> renderClipboard()
        }
        refreshSuggestionsSoon()
    }

    private fun refreshSuggestionsSoon() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionRefreshRunnable)
        handler.postDelayed(suggestionRefreshRunnable, 35L)
    }

    private fun refreshSuggestions() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionAutoHideRunnable)
        suggestionBar.removeAllViews()
        if (!suggestionsEnabled || isSensitiveEditor() || (mode != KeyboardMode.LETTERS && !aiComposeActive)) {
            suggestionBar.visibility = if (suggestionsEnabled) View.INVISIBLE else View.GONE
            return
        }
        val before = textBeforeTypingCursor()
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
        handler.postDelayed(suggestionAutoHideRunnable, SUGGESTION_AUTO_HIDE_MS)
    }

    private fun textBeforeTypingCursor(): String {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val cursor = aiInput.selectionStart.coerceIn(0, aiInput.text.length)
            return aiInput.text.substring(0, cursor)
        }
        return currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
    }

    private fun acceptPrediction(candidate: KeyboardSuggestion) {
        val insertion = "${candidate.text} "
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceIn(0, editable.length)
            val end = aiInput.selectionEnd.coerceIn(0, editable.length)
            val replaceStart = if (start == end) (start - candidate.replaceLength).coerceAtLeast(0) else minOf(start, end)
            editable.replace(replaceStart, maxOf(start, end), insertion)
            aiInput.setSelection((replaceStart + insertion.length).coerceAtMost(editable.length))
        } else {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            if (!deleteSelectedText(ic) && candidate.replaceLength > 0) {
                ic.deleteSurroundingText(candidate.replaceLength, 0)
            }
            ic.commitText(insertion, 1)
            ic.endBatchEdit()
        }
        learnSuggestion(candidate.text)
        refreshSuggestionsSoon()
    }

    private fun renderLetters() {
        if (numberRowEnabled) {
            val numbers = "1234567890"
            val numberAlt = "!@#$%^&*()"
            addRow(numbers.mapIndexed { index, c ->
                KeySpec(
                    c.toString(),
                    if (longPressSymbolsEnabled) numberAlt[index].toString() else null,
                    action = { commit(c.toString()) },
                    longAction = if (longPressSymbolsEnabled) ({ commit(numberAlt[index].toString()) }) else null
                )
            })
        }

        addLetterRow("qwertyuiop", listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}"))
        addLetterRow("asdfghjkl", listOf("@", "#", "£", "_", "&", "-", "+", "(", ")"), sidePadding = 0.35f)

        val third = mutableListOf<KeySpec>()
        third += KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.25f, action = { handleShiftTap() })
        val thirdAlternates = listOf("*", "\"", "'", ":", ";", "!", "?")
        "zxcvbnm".forEachIndexed { index, c -> third += letterSpec(c, thirdAlternates[index]) }
        third += KeySpec("⌫", weight = 1.25f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(third)

        addRow(
            listOf(
                KeySpec("?123", weight = 1.18f, action = { mode = KeyboardMode.SYMBOLS; renderKeyboard() }),
                KeySpec(",", action = { commitPunctuation(",") }),
                KeySpec("spasi", weight = 4.45f, action = { commitSpace() }),
                KeySpec(".", action = { commitPunctuation(".") }),
                KeySpec("↵", weight = 1.25f, action = { pressEnter() })
            )
        )
    }

    private fun addLetterRow(chars: String, alternates: List<String>, sidePadding: Float = 0f) {
        val keys = mutableListOf<KeySpec>()
        if (sidePadding > 0f) keys += KeySpec("", weight = sidePadding, action = {})
        chars.forEachIndexed { index, c -> keys += letterSpec(c, alternates[index]) }
        if (sidePadding > 0f) keys += KeySpec("", weight = sidePadding, action = {})
        addRow(keys)
    }

    private fun letterSpec(char: Char, alternate: String): KeySpec {
        val shown = if (shift) char.uppercaseChar().toString() else char.toString()
        return KeySpec(
            label = shown,
            alternate = if (longPressSymbolsEnabled) alternate else null,
            action = {
                commit(shown)
                if (shift && !capsLock) {
                    shift = false
                    renderKeyboard()
                }
            },
            longAction = if (longPressSymbolsEnabled) ({ commit(alternate) }) else null
        )
    }

    private fun handleShiftTap() {
        val now = SystemClock.elapsedRealtime()
        when {
            capsLock -> {
                capsLock = false
                shift = false
                lastShiftTapAt = 0L
            }
            now - lastShiftTapAt <= DOUBLE_TAP_SHIFT_MS -> {
                capsLock = true
                shift = true
                lastShiftTapAt = 0L
            }
            else -> {
                shift = !shift
                lastShiftTapAt = now
            }
        }
        renderKeyboard()
    }

    private fun renderSymbols() {
        addSimpleSymbolRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addSimpleSymbolRow(listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"))
        addSimpleSymbolRow(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆"))
        val last = listOf("<", ">", "[", "]", "{", "}", "_", "-", "+").map { symbolSpec(it) }.toMutableList()
        last += KeySpec("⌫", weight = 1.2f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(last)
        addRow(
            listOf(
                KeySpec("ABC", weight = 1.2f, action = { mode = KeyboardMode.LETTERS; renderKeyboard() }),
                KeySpec("\\", action = { commit("\\") }),
                KeySpec("/", action = { commit("/") }),
                KeySpec(":", action = { commitPunctuation(":") }),
                KeySpec("spasi", weight = 3.1f, action = { commitSpace() }),
                KeySpec("?", action = { commitPunctuation("?") }),
                KeySpec("↵", weight = 1.25f, action = { pressEnter() })
            )
        )
    }

    private fun addSimpleSymbolRow(symbols: List<String>) = addRow(symbols.map(::symbolSpec))

    private fun symbolSpec(symbol: String) = KeySpec(symbol, action = { commit(symbol) })

    private fun renderEmoji() {
        val emojis = emojiPages[emojiPage]
        repeat(3) { rowIndex ->
            addRow(emojis.drop(rowIndex * 8).take(8).map { emoji -> KeySpec(emoji, action = { commit(emoji) }) })
        }
        val fourth = emojis.drop(24).take(7).map { emoji -> KeySpec(emoji, action = { commit(emoji) }) }.toMutableList()
        fourth += KeySpec("⌫", weight = 1.2f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(fourth)
        addRow(
            listOf(
                KeySpec("ABC", weight = 1.15f, action = { mode = KeyboardMode.LETTERS; renderKeyboard() }),
                KeySpec("◀", action = { emojiPage = (emojiPage - 1 + emojiPages.size) % emojiPages.size; renderKeyboard() }),
                KeySpec("${emojiPage + 1}/${emojiPages.size}", weight = 1.15f, action = {}),
                KeySpec("▶", action = { emojiPage = (emojiPage + 1) % emojiPages.size; renderKeyboard() }),
                KeySpec("spasi", weight = 2.8f, action = { commitSpace() }),
                KeySpec("↵", weight = 1.25f, action = { pressEnter() })
            )
        )
    }

    private fun renderClipboard() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Clipboard"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(compactButton("Tempel terbaru") { pasteClipboard() }, LinearLayout.LayoutParams(dp(105), dp(34)))
        header.addView(compactButton("Bersihkan") {
            saveClips(loadClips().filter { it.pinned })
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(82), dp(34)).apply { leftMargin = dp(4) })
        keyboardPanel.addView(header, LinearLayout.LayoutParams(-1, dp(40)))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(3))
        }
        val clips = loadClips()
        if (clips.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada riwayat. Salin teks, lalu buka Clipboard lagi."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(210, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor)))
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
        } else {
            clips.forEachIndexed { index, clip -> list.addView(clipboardItem(index, clip)) }
        }
        keyboardPanel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun clipboardItem(index: Int, clip: ClipEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        row.addView(compactButton(if (clip.pinned) "📌" else "○") {
            val clips = loadClips().toMutableList()
            if (index in clips.indices) clips[index] = clips[index].copy(pinned = !clips[index].pinned)
            saveClips(clips)
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(45), dp(44)))
        row.addView(TextView(this).apply {
            text = clip.text.replace('\n', ' ')
            textSize = 13f
            maxLines = 2
            setTextColor(Color.WHITE)
            setPadding(dp(9), 0, dp(9), 0)
            background = roundedBackground(Color.rgb(43, 43, 52), 8f)
            setOnClickListener { commitToTarget(clip.text) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(compactButton("🗑") {
            val clips = loadClips().toMutableList()
            if (index in clips.indices) clips.removeAt(index)
            saveClips(clips)
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        return row
    }

    private fun addRow(specs: List<KeySpec>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        specs.forEach { spec ->
            if (spec.label.isEmpty()) {
                row.addView(View(this), LinearLayout.LayoutParams(0, -1, spec.weight))
            } else {
                row.addView(keyView(spec), LinearLayout.LayoutParams(0, -1, spec.weight).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }
        keyboardPanel.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun keyView(spec: KeySpec): View {
        val isSpecial = spec.label.length > 2 || spec.label in listOf("⇧", "⇪", "⌫", "↵", "◀", "▶")
        val normalColor = if (isSpecial) specialKeyBg else keyBg
        val frame = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
            background = roundedBackground(normalColor, 7f)
        }
        frame.addView(TextView(this).apply {
            text = spec.label
            textSize = when {
                spec.label == "spasi" -> 13f
                spec.label.length > 3 -> 12f
                (spec.label.firstOrNull()?.code ?: 0) > 0x2600 -> keyTextSizeSp + 1f
                else -> keyTextSizeSp
            }
            setTextColor(keyTextColor)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))

        spec.alternate?.let { alternate ->
            frame.addView(TextView(this).apply {
                text = alternate
                textSize = 8f
                setTextColor(Color.argb(210, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor)))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(1), dp(4), 0)
            }, FrameLayout.LayoutParams(-1, -1))
        }

        var downX = 0f
        var downY = 0f
        var longTriggered = false
        var actionTriggered = false
        var longRunnable: Runnable? = null
        frame.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longTriggered = false
                    actionTriggered = false
                    view.background = roundedBackground(pressedKeyBg, 7f)
                    spec.longAction?.let { longAction ->
                        longRunnable = Runnable {
                            longTriggered = true
                            if (actionTriggered && spec.alternate != null) deleteOne()
                            longAction()
                            keyFeedback(view, longPress = true)
                        }.also { handler.postDelayed(it, longPressDurationMs) }
                    }
                    if (instantKeyResponse) {
                        spec.action()
                        actionTriggered = true
                        keyFeedback(view, longPress = false)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > touchTolerancePx) {
                        longRunnable?.let(handler::removeCallbacks)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longRunnable?.let(handler::removeCallbacks)
                    view.background = roundedBackground(normalColor, 7f)
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        spec.action()
                        keyFeedback(view, longPress = false)
                        actionTriggered = true
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    view.background = roundedBackground(normalColor, 7f)
                    true
                }
                else -> false
            }
        }
        return frame
    }

    private fun keyFeedback(view: View, longPress: Boolean) {
        if (soundEnabled) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEY_CLICK, 0.35f)
        }
        if (vibrationEnabled) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val duration = if (longPress) (vibrationDurationMs + 12).coerceAtMost(100) else vibrationDurationMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else if (longPress) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpFloat(radiusDp)
    }

    private fun toolbarButton(label: String, widthPx: Int, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 12f else 18f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(bg)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(widthPx, dp(utilityHeightDp()))
    }

    private fun compactButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 6) 10f else 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(Color.WHITE)
        background = roundedBackground(if (label in listOf("➤", "↑", "Pakai")) purple else Color.rgb(50, 50, 60), 8f)
        setOnClickListener { action() }
    }

    private fun pasteClipboard() {
        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let(::commitToTarget)
    }

    private fun pasteClipboardIntoAiInput() {
        val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            aiStatus.text = "Clipboard kosong."
            return
        }
        val editable = aiInput.text
        val start = aiInput.selectionStart.coerceIn(0, editable.length)
        val end = aiInput.selectionEnd.coerceIn(0, editable.length)
        editable.replace(minOf(start, end), maxOf(start, end), text)
        aiInput.requestFocus()
        aiComposeActive = true
        refreshSuggestionsSoon()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun utilityHeightDp(): Int = if (isLandscape()) 30 else 39

    private fun suggestionHeightDp(): Int = if (isLandscape()) 25 else 34

    private fun activeSuggestionHeightDp(): Int = if (suggestionsEnabled) suggestionHeightDp() else 0

    private fun resizePanelHeightDp(): Int = if (isLandscape()) 30 else 36

    private fun brandBarHeightDp(): Int = if (isLandscape()) 18 else 24

    private fun aiHeaderHeightDp(): Int = if (isLandscape()) 23 else 27

    private fun aiAnswerHeightDp(): Int = if (isLandscape()) 43 else 70

    private fun aiInputHeightDp(): Int = if (isLandscape()) 27 else 34

    private fun aiComposeHeightDp(): Int = if (isLandscape()) 60 else 65

    private fun aiQuickActionHeightDp(): Int = if (isLandscape()) 25 else 30

    private fun currentAiPanelHeightDp(): Int = if (isLandscape()) 162 else 204

    private fun fullscreenKeyboardPanelHeightDp(): Int =
        (baseKeyboardHeightDp - utilityHeightDp() - brandBarHeightDp()).coerceAtLeast(if (isLandscape()) 72 else 105)

    private fun addCurrentClipboardToHistory() {
        if (!clipboardHistoryEnabled || !clipboardManager.hasPrimaryClip() || isSensitiveEditor() || clipboardMarkedSensitive()) return
        val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val clips = loadClips().toMutableList()
        val existing = clips.firstOrNull { it.text == text }
        clips.removeAll { it.text == text }
        clips.add(0, ClipEntry(text.take(MAX_CLIP_LENGTH), existing?.pinned == true))
        val kept = clips.filter { it.pinned } + clips.filterNot { it.pinned }.take(MAX_CLIPS)
        saveClips(kept.distinctBy { it.text }.take(MAX_CLIPS + 5))
    }

    private fun loadClips(): List<ClipEntry> = runCatching {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("clipboard_items", "[]").orEmpty()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text")
                if (text.isNotBlank()) add(ClipEntry(text, item.optBoolean("pinned", false)))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveClips(clips: List<ClipEntry>) {
        val array = JSONArray()
        clips.forEach { clip -> array.put(JSONObject().put("text", clip.text).put("pinned", clip.pinned)) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("clipboard_items", array.toString()).apply()
    }

    private fun clipboardMarkedSensitive(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clipboardManager.primaryClipDescription?.extras
                ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true

    private fun savedSuggestionEntries(): List<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("personal_phrases", "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.length in 2..140 }
            .distinctBy { it.lowercase() }
            .take(80)
            .toList()

    private fun loadLearnedSuggestions(): List<LearnedSuggestion> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val fromJson = runCatching {
            val array = JSONArray(prefs.getString("learned_suggestions", "[]").orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.length in 2..140) {
                        add(
                            LearnedSuggestion(
                                text = text,
                                uses = item.optInt("uses", 1).coerceAtLeast(1),
                                lastUsedAt = item.optLong("lastUsedAt", 0L)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (fromJson.isNotEmpty()) return fromJson

        val legacy = prefs.getStringSet("learned_words", emptySet()).orEmpty()
            .map { LearnedSuggestion(it, 1, 0L) }
        if (legacy.isNotEmpty()) saveLearnedSuggestions(legacy)
        return legacy
    }

    private fun learnSuggestion(value: String) {
        if (!personalizedLearningEnabled || isSensitiveEditor()) return
        val cleaned = value.trim().replace(Regex("\\s+"), " ").take(140)
        if (cleaned.length < 2) return
        val entries = loadLearnedSuggestions().toMutableList()
        val existingIndex = entries.indexOfFirst { it.text.equals(cleaned, ignoreCase = true) }
        val updated = if (existingIndex >= 0) {
            val existing = entries.removeAt(existingIndex)
            existing.copy(text = cleaned, uses = (existing.uses + 1).coerceAtMost(100_000), lastUsedAt = System.currentTimeMillis())
        } else {
            LearnedSuggestion(cleaned, 1, System.currentTimeMillis())
        }
        entries.add(updated)
        saveLearnedSuggestions(
            entries.sortedWith(
                compareByDescending<LearnedSuggestion> { it.uses }.thenByDescending { it.lastUsedAt }
            ).take(MAX_LEARNED_SUGGESTIONS)
        )
    }

    private fun saveLearnedSuggestions(entries: List<LearnedSuggestion>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("text", entry.text)
                    .put("uses", entry.uses)
                    .put("lastUsedAt", entry.lastUsedAt)
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("learned_suggestions", array.toString())
            .apply()
    }

    private fun learnCurrentBoundary(completed: Boolean = false, terminalMark: String = "") {
        if (isSensitiveEditor()) return
        val before = textBeforeTypingCursor()
        if (personalizedLearningEnabled) {
            SuggestionEngine.learnableEntries(before).forEach(::learnSuggestion)
        }
        if (styleMemoryEnabled) {
            TypingStyleMemory.observeBoundary(
                getSharedPreferences(PREFS, MODE_PRIVATE),
                before,
                completed,
                terminalMark
            )
        }
    }

    private fun isSensitiveEditor(): Boolean {
        if (aiComposeActive) return false
        val info = currentInputEditorInfo ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            info.imeOptions.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        ) return true

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            if (::aiStatus.isInitialized) aiStatus.text = "Buka aplikasi AI Ads Keyboard untuk pengaturan"
        }
    }

    private fun deleteOne() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            when {
                start != end -> editable.delete(minOf(start, end), maxOf(start, end))
                start > 0 -> editable.delete(start - 1, start)
            }
        } else {
            currentInputConnection?.let { ic ->
                if (!deleteSelectedText(ic)) ic.deleteSurroundingText(1, 0)
            }
        }
        refreshSuggestionsSoon()
    }

    private fun deleteWord() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            if (start != end) {
                editable.delete(minOf(start, end), maxOf(start, end))
            } else if (start > 0) {
                val before = editable.substring(0, start)
                val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
                editable.delete((start - count).coerceAtLeast(0), start)
            }
            refreshSuggestionsSoon()
            return
        }
        val ic = currentInputConnection ?: return
        if (deleteSelectedText(ic)) {
            refreshSuggestionsSoon()
            return
        }
        val before = ic.getTextBeforeCursor(100, 0)?.toString().orEmpty()
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        ic.deleteSurroundingText(count, 0)
        refreshSuggestionsSoon()
    }

    private fun deleteSelectedText(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isEmpty()) return false
        ic.commitText("", 1)
        return true
    }

    private fun pressEnter() {
        if (aiComposeActive) {
            runAiConversation()
            return
        }
        learnCurrentBoundary(completed = true)
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (enterActionEnabled && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
        if (automaticCapitalizationEnabled) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
        refreshSuggestionsSoon()
    }

    private fun commit(text: String) {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceIn(0, editable.length)
            val end = aiInput.selectionEnd.coerceIn(0, editable.length)
            editable.replace(minOf(start, end), maxOf(start, end), text)
            refreshSuggestionsSoon()
        } else {
            commitToTarget(text)
        }
    }

    private fun commitToTarget(text: String) {
        currentInputConnection?.commitText(text, 1)
        refreshSuggestionsSoon()
    }

    private fun commitSpace() {
        if (aiComposeActive) {
            learnCurrentBoundary()
            commit(" ")
            return
        }
        learnCurrentBoundary()
        val now = System.currentTimeMillis()
        if (doubleSpacePeriodEnabled && now - lastSpaceAt < 700) {
            val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.endsWith(" ") && before.dropLast(1).lastOrNull()?.isLetterOrDigit() == true) {
                currentInputConnection?.deleteSurroundingText(1, 0)
                currentInputConnection?.commitText(". ", 1)
                lastSpaceAt = 0L
                if (automaticCapitalizationEnabled) shift = true
                if (mode == KeyboardMode.LETTERS) renderKeyboard()
                return
            }
        }
        commitToTarget(" ")
        lastSpaceAt = now
    }

    private fun commitPunctuation(mark: String) {
        learnCurrentBoundary(completed = mark in listOf(".", "?", "!"), terminalMark = mark)
        commit(if (!aiComposeActive && punctuationSpaceEnabled) "$mark " else mark)
        if (automaticCapitalizationEnabled && mark in listOf(".", "?", "!")) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun updateAutomaticShift() {
        if (capsLock) return
        val before = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        shift = before.isBlank() || before.trimEnd().lastOrNull() in listOf('.', '?', '!') || before.endsWith("\n")
    }

    private fun editorContext(ic: InputConnection): String {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) return selected
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty()
        }.getOrDefault("")
        if (extracted.isNotBlank()) return extracted.takeLast(MAX_AI_CONTEXT_CHARS)
        val before = ic.getTextBeforeCursor(MAX_AI_CONTEXT_CHARS / 2, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_AI_CONTEXT_CHARS / 2, 0)?.toString().orEmpty()
        return (before + after).takeLast(MAX_AI_CONTEXT_CHARS)
    }

    private fun context(ic: InputConnection): String {
        val editor = editorContext(ic)
        if (editor.isNotBlank()) return editor
        val screen = screenContextNow()
        if (screen != null) return screen.text
        return recentSharedContext()
    }

    private fun automaticReplyContext(ic: InputConnection): String {
        val screen = screenContextNow()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val editor = editorContext(ic)
        val copied = if (clipboardMarkedSensitive()) "" else clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        val saved = recentSharedContext()
        return listOf(
            "TEKS LAYAR TERBARU — sumber utama percakapan" to screen?.text.orEmpty(),
            "Teks yang sengaja dipilih atau dibagikan" to saved,
            "Teks yang dipilih di kolom aktif" to selected,
            "Clipboard — gunakan hanya jika cocok dengan percakapan" to copied,
            "Draf pengguna di kolom balasan — jangan dianggap pesan lawan bicara" to editor
        )
            .map { (label, value) -> label to value.trim() }
            .filter { (_, value) -> value.isNotBlank() }
            .distinctBy { (_, value) -> value }
            .joinToString("\n\n") { (label, value) -> "[$label]\n$value" }
            .takeLast(MAX_REPLY_CONTEXT_CHARS)
    }

    /**
     * Chooses a translation source without requiring text to be pasted into the AI composer.
     * An explicit selection wins; otherwise the currently visible application is read on demand.
     * Shared text, the active editor, and the clipboard are safe fallbacks when Accessibility
     * cannot expose the application's view hierarchy.
     */
    private fun automaticTranslationContext(ic: InputConnection): String {
        val selected = ic.getSelectedText(0)?.toString().orEmpty().trim()
        if (selected.isNotBlank()) return selected.takeLast(MAX_AI_CONTEXT_CHARS)

        val screen = screenContextNow()?.text.orEmpty().trim()
        if (screen.isNotBlank()) return screen.takeLast(MAX_AI_CONTEXT_CHARS)

        val shared = recentSharedContext().trim()
        if (shared.isNotBlank()) return shared.takeLast(MAX_AI_CONTEXT_CHARS)

        val editor = editorContext(ic).trim()
        if (editor.isNotBlank()) return editor.takeLast(MAX_AI_CONTEXT_CHARS)

        if (!clipboardMarkedSensitive()) {
            val copied = clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
                .trim()
            if (copied.isNotBlank()) return copied.takeLast(MAX_AI_CONTEXT_CHARS)
        }
        return ""
    }

    private fun conversationContext(ic: InputConnection?): String {
        val screen = screenContextNow()
        val editor = ic?.let(::editorContext).orEmpty()
        val shared = recentSharedContext()
        return listOf(
            "Teks layar aplikasi saat ini" to screen?.text.orEmpty(),
            "Isi kolom tulisan saat ini" to editor,
            "Teks yang dipilih, dibagikan, atau dibaca dari gambar" to shared
        )
            .filter { (_, value) -> value.isNotBlank() }
            .distinctBy { (_, value) -> value }
            .joinToString("\n\n") { (label, value) -> "[$label]\n$value" }
            .takeLast(MAX_AI_CONTEXT_CHARS)
    }

    private fun screenContextNow(): ScreenTextSnapshot? {
        if (isSensitiveEditor()) return null
        return ScreenTextAccessibilityService.captureNow(currentInputEditorInfo?.packageName)
    }

    private fun recentSharedContext(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedAt = prefs.getLong("shared_context_updated_at", 0L)
        return prefs.getString("shared_context", "").orEmpty()
            .takeIf { System.currentTimeMillis() - savedAt <= SHARED_CONTEXT_MAX_AGE_MS }
            .orEmpty()
    }

    private fun aiSettings() = getSharedPreferences(PREFS, MODE_PRIVATE).let { prefs ->
        AiSettings(
            primaryProvider = AiProvider.fromId(prefs.getString("provider", null)),
            openRouterApiKey = prefs.getString("openrouter_api_key", prefs.getString("api_key", "")).orEmpty(),
            openRouterModel = prefs.getString("openrouter_model", "openrouter/free").orEmpty(),
            tabiApiKey = prefs.getString("tabi_api_key", "").orEmpty(),
            tabiBaseUrl = prefs.getString("tabi_base_url", "https://tabitoken.com").orEmpty(),
            tabiModel = prefs.getString("tabi_model", "claude-opus-5").orEmpty(),
            fallbackEnabled = prefs.getBoolean("fallback_enabled", false),
            referenceUrls = prefs.getString("reference_urls", "").orEmpty()
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_REFERENCE_URLS)
                .toList(),
            writingStyleProfile = if (prefs.getBoolean("style_memory_enabled", true)) {
                TypingStyleMemory.prompt(prefs)
            } else {
                ""
            }
        )
    }

    private fun activeProviderLabel(): String {
        val provider = AiProvider.fromId(getSharedPreferences(PREFS, MODE_PRIVATE).getString("provider", null))
        return "Provider: ${provider.label}"
    }

    private fun runAi(action: String) {
        val ic = currentInputConnection ?: return
        if (!aiPanelVisible) toggleAiPanel(true)
        aiComposeActive = false
        aiInput.clearFocus()
        val input = when (action) {
            "Balas" -> automaticReplyContext(ic)
            "Terjemah" -> automaticTranslationContext(ic)
            else -> context(ic)
        }
        if (action in listOf("Balas", "Terjemah") && input.isBlank()) {
            aiStatus.text = if (action == "Balas") {
                "Belum ada pesan untuk dibalas."
            } else {
                "Belum ada teks yang dapat diterjemahkan."
            }
            aiAnswer.text = "Aktifkan Akses Teks Layar, buka kembali aplikasi yang berisi teks, lalu tekan $action lagi. Teks pilihan, Bagikan, atau clipboard tetap dapat dipakai sebagai cadangan."
            return
        }
        aiStatus.text = when (action) {
            "Balas" -> "Membaca percakapan layar dan menyiapkan balasan…"
            "Terjemah" -> "Membaca teks layar dan menerjemahkan otomatis…"
            else -> "$action sedang diproses…"
        }
        thread {
            val result = AiClient.transform(aiSettings(), action, input)
            aiStatus.post {
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiStatus.text = error.message ?: "Terjadi kesalahan"
                    aiAnswer.text = error.message ?: "AI gagal merespons."
                }
            }
        }
    }

    private fun runAiConversation() {
        val prompt = aiInput.text.toString().trim()
        if (prompt.isBlank()) {
            aiStatus.text = "Tulis pesan untuk AI terlebih dahulu."
            aiInput.requestFocus()
            aiComposeActive = true
            return
        }
        val ic = currentInputConnection
        if (styleMemoryEnabled && prompt.isNotBlank()) {
            TypingStyleMemory.observeCompletedText(getSharedPreferences(PREFS, MODE_PRIVATE), prompt)
        }
        val appContext = conversationContext(ic)
        val history = conversationHistory.takeLast(4).joinToString("\n") { (role, text) -> "$role: $text" }
        aiStatus.text = "AI sedang menjawab…"
        aiAnswer.text = "Menunggu jawaban…"
        aiInput.setText("")
        aiComposeActive = false
        aiInput.clearFocus()
        thread {
            val result = AiClient.chat(aiSettings(), prompt, appContext, history)
            aiStatus.post {
                result.onSuccess { response ->
                    conversationHistory += "Pengguna" to prompt
                    conversationHistory += "AI" to response.text
                    while (conversationHistory.size > 8) conversationHistory.removeAt(0)
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Jawaban via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiAnswer.text = "Jawaban AI akan muncul di sini."
                    aiStatus.text = error.message ?: "Terjadi kesalahan"
                }
            }
        }
    }

    private fun insertPendingResult() {
        pendingText?.let { result ->
            commitToTarget(result)
            pendingText = null
            aiStatus.text = "Jawaban dimasukkan ke kolom teks."
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpFloat(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val PREFS = "riyan_ai"
        private const val HEIGHT_PORTRAIT_KEY = "keyboard_height_portrait_dp"
        private const val HEIGHT_LANDSCAPE_KEY = "keyboard_height_landscape_dp"
        private const val DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP = 220
        private const val DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE_DP = 120
        private const val OLD_DEFAULT_KEYBOARD_HEIGHT_DP = 350
        private const val MIN_KEYBOARD_HEIGHT_PORTRAIT_DP = 170
        private const val MAX_KEYBOARD_HEIGHT_PORTRAIT_DP = 330
        private const val MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP = 90
        private const val MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP = 190
        private const val KEYBOARD_LAYOUT_VERSION = 9
        private const val INSTANT_RESPONSE_THRESHOLD = 150
        private const val DOUBLE_TAP_SHIFT_MS = 420L
        private const val SUGGESTION_AUTO_HIDE_MS = 2_600L
        private const val SHARED_CONTEXT_MAX_AGE_MS = 30L * 60L * 1000L
        private const val MAX_AI_CONTEXT_CHARS = 12_000
        private const val MAX_REPLY_CONTEXT_CHARS = 16_000
        private const val MAX_REFERENCE_URLS = 6
        private const val MAX_CLIPS = 12
        private const val MAX_CLIP_LENGTH = 1200
        private const val MAX_LEARNED_SUGGESTIONS = 180
    }
}
