package com.riyan.aikeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.hypot

class RiyanKeyboardService : InputMethodService() {
    private enum class KeyboardMode { LETTERS, SYMBOLS, EMOJI }

    private data class KeySpec(
        val label: String,
        val alternate: String? = null,
        val weight: Float = 1f,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null
    )

    private lateinit var root: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var status: TextView
    private var mode = KeyboardMode.LETTERS
    private var shift = false
    private var pendingText: String? = null
    private var emojiPage = 0
    private var keyTextSizeSp = 22f
    private var touchTolerancePx = 0f
    private var longPressDurationMs = 450L

    private val handler = Handler(Looper.getMainLooper())
    private val bg = Color.rgb(20, 20, 25)
    private val keyBg = Color.rgb(50, 50, 60)
    private val specialKeyBg = Color.rgb(43, 68, 80)
    private val pressedKeyBg = Color.rgb(93, 74, 196)
    private val purple = Color.rgb(89, 68, 196)

    private val emojiPages = listOf(
        listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "🤩"),
        listOf("😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫡"),
        listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "👏", "🙌", "🫶", "🤝", "🙏", "✍️", "💪", "🦾", "❤️", "🧡", "💛", "💚", "💙"),
        listOf("🎉", "🎊", "🔥", "✨", "⭐", "🌟", "💯", "✅", "❌", "⚠️", "💡", "📌", "🎁", "🎂", "☕", "🍜", "🍕", "🍔", "🚗", "🏍️", "✈️", "🏠", "📱", "💻", "🎮", "⚽", "🎵", "📷", "🌞", "🌙", "🌈")
    )

    override fun onCreateInputView(): View {
        val prefs = getSharedPreferences("riyan_ai", MODE_PRIVATE)
        val activeProvider = AiProvider.fromId(prefs.getString("provider", null))
        val keyboardHeightDp = prefs.getInt("keyboard_height_dp", 350).coerceIn(280, 430)
        keyTextSizeSp = prefs.getInt("key_text_size_sp", 22).coerceIn(16, 30).toFloat()
        val sensitivity = prefs.getInt("touch_sensitivity", 65).coerceIn(20, 100)
        touchTolerancePx = dpFloat(8f + sensitivity * 0.32f)
        longPressDurationMs = prefs.getInt("long_press_ms", 450).coerceIn(200, 900).toLong()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(4), dp(4), dp(4), dp(6))
            minimumHeight = dp(keyboardHeightDp)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(keyboardHeightDp))
        }

        addAiToolbar()
        addUtilityBar(activeProvider)

        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        renderKeyboard()
        return root
    }

    private fun addAiToolbar() {
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Tempel", "Perbaiki", "Balas", "Santai", "Sopan", "Ringkas", "Terjemah").forEach { action ->
            tools.addView(toolButton(action), LinearLayout.LayoutParams(dp(78), dp(38)))
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tools, LinearLayout.LayoutParams(-2, -1))
            },
            LinearLayout.LayoutParams(-1, dp(40))
        )
    }

    private fun addUtilityBar(activeProvider: AiProvider) {
        val utility = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            text = "AI: ${activeProvider.label}"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(3), 0)
            maxLines = 1
            setOnClickListener {
                pendingText?.let { result ->
                    currentInputConnection?.commitText(result, 1)
                    pendingText = null
                    text = "Hasil dimasukkan"
                }
            }
        }
        utility.addView(status, LinearLayout.LayoutParams(0, dp(38), 1f))
        utility.addView(iconButton("⌨") { mode = KeyboardMode.LETTERS; renderKeyboard() })
        utility.addView(iconButton("😊") { mode = KeyboardMode.EMOJI; renderKeyboard() })
        utility.addView(iconButton("📋") { pasteClipboard() })
        utility.addView(iconButton("⚙") { openSettings() })
        root.addView(utility, LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun renderKeyboard() {
        keyboardPanel.removeAllViews()
        when (mode) {
            KeyboardMode.LETTERS -> renderLetters()
            KeyboardMode.SYMBOLS -> renderSymbols()
            KeyboardMode.EMOJI -> renderEmoji()
        }
    }

    private fun renderLetters() {
        val numbers = "1234567890"
        val numberAlt = "!@#$%^&*()"
        addRow(numbers.mapIndexed { index, c ->
            KeySpec(c.toString(), numberAlt[index].toString(), action = { commit(c.toString()) }, longAction = { commit(numberAlt[index].toString()) })
        })

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
                KeySpec("?123", weight = 1.15f, action = { mode = KeyboardMode.SYMBOLS; renderKeyboard() }),
                KeySpec(",", action = { commit(",") }),
                KeySpec("😊", action = { mode = KeyboardMode.EMOJI; renderKeyboard() }),
                KeySpec("spasi", weight = 3.3f, action = { commit(" ") }),
                KeySpec(".", action = { commit(".") }),
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
            alternate = alternate,
            action = {
                commit(shown)
                if (shift) {
                    shift = false
                    renderKeyboard()
                }
            },
            longAction = { commit(alternate) }
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
                KeySpec(":", action = { commit(":") }),
                KeySpec("spasi", weight = 3.1f, action = { commit(" ") }),
                KeySpec("?", action = { commit("?") }),
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
                KeySpec("spasi", weight = 2.8f, action = { commit(" ") }),
                KeySpec("↵", weight = 1.25f, action = { pressEnter() })
            )
        )
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
            background = roundedBackground(normalColor)
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
                textSize = 9f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(2), dp(4), 0)
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
                    view.background = roundedBackground(pressedKeyBg)
                    spec.longAction?.let { longAction ->
                        longRunnable = Runnable {
                            longTriggered = true
                            longAction()
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                    view.background = roundedBackground(normalColor)
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!longTriggered && moved <= touchTolerancePx) spec.action()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    view.background = roundedBackground(normalColor)
                    true
                }
                else -> false
            }
        }
        return frame
    }

    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpFloat(7f)
    }

    private fun iconButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(bg)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(43), dp(38))
    }

    private fun toolButton(label: String) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(purple)
        setOnClickListener {
            if (label == "Tempel") pasteClipboard() else runAi(label)
        }
    }

    private fun pasteClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let(::commit)
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { status.text = "Buka aplikasi Riyan AI Keyboard untuk pengaturan" }
    }

    private fun deleteOne() = currentInputConnection?.deleteSurroundingText(1, 0)

    private fun deleteWord() {
        val before = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty()
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        currentInputConnection?.deleteSurroundingText(count, 0)
    }

    private fun pressEnter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun commit(text: String) = currentInputConnection?.commitText(text, 1)

    private fun context(ic: InputConnection): Pair<String, Boolean> {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) return selected to true
        val before = ic.getTextBeforeCursor(1800, 0)?.toString().orEmpty()
        if (before.isNotBlank()) return before to false
        return getSharedPreferences("riyan_ai", MODE_PRIVATE).getString("shared_context", "").orEmpty() to false
    }

    private fun runAi(action: String) {
        val ic = currentInputConnection ?: return
        val (text, _) = context(ic)
        val prefs = getSharedPreferences("riyan_ai", MODE_PRIVATE)
        status.text = "AI sedang memproses…"
        thread {
            val settings = AiSettings(
                primaryProvider = AiProvider.fromId(prefs.getString("provider", null)),
                openRouterApiKey = prefs.getString("openrouter_api_key", prefs.getString("api_key", "")).orEmpty(),
                openRouterModel = prefs.getString("openrouter_model", "openrouter/free").orEmpty(),
                tabiApiKey = prefs.getString("tabi_api_key", "").orEmpty(),
                tabiBaseUrl = prefs.getString("tabi_base_url", "https://tabitoken.com").orEmpty(),
                tabiModel = prefs.getString("tabi_model", "claude-opus-5").orEmpty(),
                fallbackEnabled = prefs.getBoolean("fallback_enabled", false)
            )
            val result = AiClient.transform(settings, action, text)
            status.post {
                result.onSuccess { response ->
                    pendingText = response.text
                    status.text = "Hasil via ${response.provider.label} · ketuk untuk memasukkan"
                }.onFailure { status.text = it.message ?: "Terjadi kesalahan" }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpFloat(value: Float) = value * resources.displayMetrics.density
}
