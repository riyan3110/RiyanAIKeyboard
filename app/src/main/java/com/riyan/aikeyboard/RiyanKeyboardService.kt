package com.riyan.aikeyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.*
import kotlin.concurrent.thread

class RiyanKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var shift = false
    private var pendingText: String? = null
    private val bg = Color.rgb(27, 27, 33)
    private val keyBg = Color.rgb(56, 56, 66)

    override fun onCreateInputView(): View {
        val activeProvider = AiProvider.fromId(
            getSharedPreferences("riyan_ai", MODE_PRIVATE).getString("provider", null)
        )
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(5, 6, 5, 8) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Tempel", "Perbaiki", "Balas", "Santai", "Sopan", "Ringkas", "Terjemah").forEach { action ->
            tools.addView(toolButton(action), LinearLayout.LayoutParams(dp(78), dp(38)))
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools, LinearLayout.LayoutParams(-2, -1)) }, LinearLayout.LayoutParams(-1, dp(42)))
        status = TextView(this).apply {
            text = "AI: ${activeProvider.label} · pilih teks lalu tekan fitur"; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
            setOnClickListener {
                pendingText?.let { result ->
                    currentInputConnection?.commitText(result, 1)
                    pendingText = null
                    text = "Hasil dimasukkan"
                }
            }
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(32)))
        addRow("qwertyuiop")
        addRow("asdfghjkl")
        addRow("zxcvbnm")
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key("⇧") { shift = !shift; rebuildLetters() }, LinearLayout.LayoutParams(0, dp(48), 1.1f))
        bottom.addView(key(",") { commit(",") }, LinearLayout.LayoutParams(0, dp(48), .8f))
        bottom.addView(key("spasi") { commit(" ") }, LinearLayout.LayoutParams(0, dp(48), 3.5f))
        bottom.addView(key(".") { commit(".") }, LinearLayout.LayoutParams(0, dp(48), .8f))
        bottom.addView(key("⌫") { currentInputConnection?.deleteSurroundingText(1, 0) }, LinearLayout.LayoutParams(0, dp(48), 1.1f))
        bottom.addView(key("↵") { currentInputConnection?.commitText("\n", 1) }, LinearLayout.LayoutParams(0, dp(48), 1.1f))
        root.addView(bottom)
        return root
    }

    private fun rebuildLetters() { setInputView(onCreateInputView()) }

    private fun addRow(chars: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chars.forEach { c ->
            val shown = if (shift) c.uppercaseChar() else c
            row.addView(key(shown.toString()) { commit(shown.toString()); if (shift) { shift = false; rebuildLetters() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(row)
    }

    private fun toolButton(label: String) = Button(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(89, 68, 196))
        setOnClickListener {
            if (label == "Tempel") {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@RiyanKeyboardService)?.toString()?.let(::commit)
            } else runAi(label)
        }
    }

    private fun key(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = if (label.length == 1) 19f else 13f; isAllCaps = false
        setTextColor(Color.WHITE); setBackgroundColor(keyBg); setOnClickListener { action() }
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
                openRouterApiKey = prefs.getString(
                    "openrouter_api_key",
                    prefs.getString("api_key", "")
                ).orEmpty(),
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
}
