package com.riyan.aikeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
    private lateinit var aiPanel: LinearLayout
    private lateinit var aiStatus: TextView
    private lateinit var aiAnswer: TextView
    private lateinit var aiInput: EditText
    private lateinit var heightLabel: TextView

    private var mode = KeyboardMode.LETTERS
    private var shift = false
    private var pendingText: String? = null
    private var emojiPage = 0
    private var baseKeyboardHeightDp = DEFAULT_KEYBOARD_HEIGHT_DP
    private var keyTextSizeSp = 21f
    private var touchTolerancePx = 0f
    private var longPressDurationMs = 450L
    private var aiPanelVisible = false
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
    private var lastSpaceAt = 0L
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    private val handler = Handler(Looper.getMainLooper())
    private val bg = Color.rgb(18, 18, 23)
    private val panelBg = Color.rgb(27, 27, 34)
    private val keyBg = Color.rgb(50, 50, 60)
    private val specialKeyBg = Color.rgb(43, 68, 80)
    private val pressedKeyBg = Color.rgb(93, 74, 196)
    private val purple = Color.rgb(89, 68, 196)

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

    override fun onCreateInputView(): View {
        loadPreferences()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(4), dp(3), dp(4), dp(5))
        }

        addAiConversationPanel()
        addUtilityBar()
        addResizePanel()

        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, 0)
        }
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        applyRootHeight()
        renderKeyboard()
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
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val storedHeight = prefs.getInt("keyboard_height_dp", OLD_DEFAULT_KEYBOARD_HEIGHT_DP)
        baseKeyboardHeightDp = if (prefs.getInt("keyboard_layout_version", 0) < 4) {
            val migrated = if (storedHeight == OLD_DEFAULT_KEYBOARD_HEIGHT_DP) {
                DEFAULT_KEYBOARD_HEIGHT_DP
            } else {
                storedHeight.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
            }
            prefs.edit()
                .putInt("keyboard_height_dp", migrated)
                .putInt("keyboard_layout_version", 4)
                .apply()
            migrated
        } else {
            storedHeight.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
        }
        keyTextSizeSp = prefs.getInt("key_text_size_sp", 21).coerceIn(16, 28).toFloat()
        val sensitivity = prefs.getInt("touch_sensitivity", 65).coerceIn(20, 100)
        touchTolerancePx = dpFloat(8f + sensitivity * 0.28f)
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
    }

    private fun addAiConversationPanel() {
        aiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(panelBg, 11f)
            visibility = View.GONE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "✦ Obrolan AI"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        header.addView(compactButton("Hapus") {
            conversationHistory.clear()
            pendingText = null
            aiAnswer.text = "Jawaban AI akan muncul di sini."
            aiStatus.text = activeProviderLabel()
        }, LinearLayout.LayoutParams(dp(58), dp(28)))
        header.addView(compactButton("✕") { toggleAiPanel(false) }, LinearLayout.LayoutParams(dp(38), dp(28)))
        aiPanel.addView(header)

        aiAnswer = TextView(this).apply {
            text = "Jawaban AI akan muncul di sini."
            textSize = 12f
            maxLines = 2
            setTextColor(Color.LTGRAY)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = roundedBackground(Color.rgb(38, 38, 47), 8f)
            setOnClickListener { insertPendingResult() }
        }
        aiPanel.addView(aiAnswer, LinearLayout.LayoutParams(-1, dp(46)))

        aiStatus = TextView(this).apply {
            text = activeProviderLabel()
            textSize = 10f
            maxLines = 1
            setTextColor(Color.GRAY)
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        aiPanel.addView(aiStatus, LinearLayout.LayoutParams(-1, dp(19)))

        val compose = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        aiInput = EditText(this).apply {
            hint = "Tulis pesan atau perintah untuk AI…"
            textSize = 13f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            showSoftInputOnFocus = false
            setPadding(dp(9), 0, dp(6), 0)
            background = roundedBackground(Color.rgb(43, 43, 52), 10f)
            setOnClickListener { aiComposeActive = true }
            setOnFocusChangeListener { _, hasFocus -> aiComposeActive = hasFocus }
        }
        compose.addView(aiInput, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        compose.addView(compactButton("➤") { runAiConversation() }, LinearLayout.LayoutParams(dp(48), dp(40)))
        compose.addView(compactButton("Pakai") { insertPendingResult() }, LinearLayout.LayoutParams(dp(58), dp(40)).apply { leftMargin = dp(4) })
        aiPanel.addView(compose)

        val quickActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Perbaiki", "Balas", "Santai", "Sopan").forEach { action ->
            quickActions.addView(compactButton(action) { runAi(action) }, LinearLayout.LayoutParams(0, dp(31), 1f).apply {
                setMargins(dp(2), dp(3), dp(2), 0)
            })
        }
        aiPanel.addView(quickActions)
        root.addView(aiPanel, LinearLayout.LayoutParams(-1, dp(AI_PANEL_HEIGHT_DP)))
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
        utilityBar.addView(toolbarButton("⌄", dp(43)) { requestHideSelf(0) })
        root.addView(utilityBar, LinearLayout.LayoutParams(-1, dp(UTILITY_HEIGHT_DP)))
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
            text = "Tinggi $baseKeyboardHeightDp dp"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setOnTouchListener(heightDragListener())
        }
        resizePanel.addView(heightLabel, LinearLayout.LayoutParams(0, dp(34), 1f))
        resizePanel.addView(compactButton("+") { changeKeyboardHeight(10) }, LinearLayout.LayoutParams(dp(48), dp(34)))
        resizePanel.addView(compactButton("Selesai") { toggleResizePanel(false) }, LinearLayout.LayoutParams(dp(70), dp(34)))
        root.addView(resizePanel, LinearLayout.LayoutParams(-1, dp(RESIZE_PANEL_HEIGHT_DP)))
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
            aiComposeActive = false
            aiInput.clearFocus()
        }
        applyRootHeight()
    }

    private fun toggleResizePanel(force: Boolean? = null) {
        resizePanelVisible = force ?: !resizePanelVisible
        resizePanel.visibility = if (resizePanelVisible) View.VISIBLE else View.GONE
        heightLabel.text = "Tinggi $baseKeyboardHeightDp dp · geser"
        applyRootHeight()
    }

    private fun changeKeyboardHeight(deltaDp: Int) {
        setKeyboardHeight(baseKeyboardHeightDp + deltaDp, persist = true)
    }

    private fun setKeyboardHeight(valueDp: Int, persist: Boolean) {
        baseKeyboardHeightDp = valueDp.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
        if (::heightLabel.isInitialized) heightLabel.text = "Tinggi $baseKeyboardHeightDp dp · geser"
        if (persist) saveKeyboardHeight()
        applyRootHeight()
    }

    private fun saveKeyboardHeight() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("keyboard_height_dp", baseKeyboardHeightDp)
            .putInt("keyboard_layout_version", 4)
            .apply()
    }

    private fun applyRootHeight() {
        if (!::root.isInitialized) return
        val extra = (if (aiPanelVisible) AI_PANEL_HEIGHT_DP else 0) +
            (if (resizePanelVisible) RESIZE_PANEL_HEIGHT_DP else 0)
        val height = dp(baseKeyboardHeightDp + extra)
        root.minimumHeight = height
        root.layoutParams = (root.layoutParams ?: ViewGroup.LayoutParams(-1, height)).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            this.height = height
        }
        root.requestLayout()
        window?.window?.decorView?.requestLayout()
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
        third += KeySpec("⇧", weight = 1.25f, action = { shift = !shift; renderKeyboard() })
        val thirdAlternates = listOf("*", "\"", "'", ":", ";", "!", "?")
        "zxcvbnm".forEachIndexed { index, c -> third += letterSpec(c, thirdAlternates[index]) }
        third += KeySpec("⌫", weight = 1.25f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(third)

        addRow(
            listOf(
                KeySpec("?123", weight = 1.18f, action = { mode = KeyboardMode.SYMBOLS; renderKeyboard() }),
                KeySpec(",", action = { commitPunctuation(",") }),
                KeySpec("😊", action = { mode = KeyboardMode.EMOJI; renderKeyboard() }),
                KeySpec("spasi", weight = 3.45f, action = { commitSpace() }),
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
                if (shift) {
                    shift = false
                    renderKeyboard()
                }
            },
            longAction = if (longPressSymbolsEnabled) ({ commit(alternate) }) else null
        )
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
                setTextColor(Color.LTGRAY)
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
        val isSpecial = spec.label.length > 2 || spec.label in listOf("⇧", "⌫", "↵", "◀", "▶")
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
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))

        spec.alternate?.let { alternate ->
            frame.addView(TextView(this).apply {
                text = alternate
                textSize = 8f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(1), dp(4), 0)
            }, FrameLayout.LayoutParams(-1, -1))
        }

        var downX = 0f
        var downY = 0f
        var longTriggered = false
        var longRunnable: Runnable? = null
        frame.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longTriggered = false
                    view.background = roundedBackground(pressedKeyBg, 7f)
                    spec.longAction?.let { longAction ->
                        longRunnable = Runnable {
                            longTriggered = true
                            longAction()
                            keyFeedback(view, longPress = true)
                        }.also { handler.postDelayed(it, longPressDurationMs) }
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
                    if (!longTriggered && moved <= touchTolerancePx) {
                        spec.action()
                        keyFeedback(view, longPress = false)
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
        layoutParams = LinearLayout.LayoutParams(widthPx, dp(UTILITY_HEIGHT_DP))
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
        background = roundedBackground(if (label in listOf("➤", "Pakai")) purple else Color.rgb(50, 50, 60), 8f)
        setOnClickListener { action() }
    }

    private fun pasteClipboard() {
        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let(::commitToTarget)
    }

    private fun addCurrentClipboardToHistory() {
        if (!clipboardHistoryEnabled || !clipboardManager.hasPrimaryClip()) return
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

    private fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            if (::aiStatus.isInitialized) aiStatus.text = "Buka aplikasi Riyan AI Keyboard untuk pengaturan"
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
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun deleteWord() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val cursor = aiInput.selectionStart.coerceAtLeast(0)
            if (cursor > 0) {
                val before = editable.substring(0, cursor)
                val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
                editable.delete((cursor - count).coerceAtLeast(0), cursor)
            }
            return
        }
        val before = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty()
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        currentInputConnection?.deleteSurroundingText(count, 0)
    }

    private fun pressEnter() {
        if (aiComposeActive) {
            runAiConversation()
            return
        }
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
        if (automaticCapitalizationEnabled) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun commit(text: String) {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val cursor = aiInput.selectionStart.coerceAtLeast(0)
            aiInput.text.insert(cursor.coerceAtMost(aiInput.text.length), text)
        } else {
            commitToTarget(text)
        }
    }

    private fun commitToTarget(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun commitSpace() {
        if (aiComposeActive) {
            commit(" ")
            return
        }
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
        commit(if (!aiComposeActive && punctuationSpaceEnabled) "$mark " else mark)
        if (automaticCapitalizationEnabled && mark in listOf(".", "?", "!")) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun updateAutomaticShift() {
        val before = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        shift = before.isBlank() || before.trimEnd().lastOrNull() in listOf('.', '?', '!') || before.endsWith("\n")
    }

    private fun context(ic: InputConnection): String {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) return selected
        val before = ic.getTextBeforeCursor(1800, 0)?.toString().orEmpty()
        if (before.isNotBlank()) return before
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("shared_context", "").orEmpty()
    }

    private fun aiSettings() = getSharedPreferences(PREFS, MODE_PRIVATE).let { prefs ->
        AiSettings(
            primaryProvider = AiProvider.fromId(prefs.getString("provider", null)),
            openRouterApiKey = prefs.getString("openrouter_api_key", prefs.getString("api_key", "")).orEmpty(),
            openRouterModel = prefs.getString("openrouter_model", "openrouter/free").orEmpty(),
            tabiApiKey = prefs.getString("tabi_api_key", "").orEmpty(),
            tabiBaseUrl = prefs.getString("tabi_base_url", "https://tabitoken.com").orEmpty(),
            tabiModel = prefs.getString("tabi_model", "claude-opus-5").orEmpty(),
            fallbackEnabled = prefs.getBoolean("fallback_enabled", false)
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
        aiStatus.text = "$action sedang diproses…"
        thread {
            val result = AiClient.transform(aiSettings(), action, context(ic))
            aiStatus.post {
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiStatus.text = error.message ?: "Terjadi kesalahan"
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
        val appContext = ic?.let(::context).orEmpty()
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
        private const val DEFAULT_KEYBOARD_HEIGHT_DP = 250
        private const val OLD_DEFAULT_KEYBOARD_HEIGHT_DP = 350
        private const val MIN_KEYBOARD_HEIGHT_DP = 210
        private const val MAX_KEYBOARD_HEIGHT_DP = 360
        private const val UTILITY_HEIGHT_DP = 41
        private const val RESIZE_PANEL_HEIGHT_DP = 38
        private const val AI_PANEL_HEIGHT_DP = 184
        private const val MAX_CLIPS = 12
        private const val MAX_CLIP_LENGTH = 1200
    }
}
