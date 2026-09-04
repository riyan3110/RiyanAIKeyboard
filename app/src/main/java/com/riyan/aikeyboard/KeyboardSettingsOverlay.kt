package com.riyan.aikeyboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class KeyboardSettingsOverlay(
    context: Context,
    private val prefs: SharedPreferences,
    private val onApply: () -> Unit,
    private val onClose: () -> Unit,
    private val onInputFocusChanged: (Boolean) -> Unit = {}
) : FrameLayout(context) {

    enum class Tab { MODEL, MEMORY, THEME, TYPING }

    private data class Draft(
        var provider: AiProvider,
        var openRouterKey: String,
        var openRouterModel: String,
        var tabiKey: String,
        var tabiBaseUrl: String,
        var tabiModel: String,
        var nineRouterKey: String,
        var nineRouterBaseUrl: String,
        var nineRouterModel: String,
        var bluesMindsKey: String,
        var bluesMindsBaseUrl: String,
        var bluesMindsModel: String,
        var fallbackEnabled: Boolean,
        var referenceUrls: String,
        var styleMemoryEnabled: Boolean,
        var personalPhrases: String,
        var themeMode: String,
        var themeColor: String,
        var portraitHeight: Int,
        var landscapeHeight: Int,
        var keyTextSize: Int,
        var keyBoxScale: Int,
        var longPressMs: Int,
        var numberRow: Boolean,
        var longPressSymbols: Boolean,
        var sound: Boolean,
        var vibration: Boolean,
        var vibrationDuration: Int,
        var autoCaps: Boolean,
        var punctuationSpace: Boolean,
        var doubleSpacePeriod: Boolean,
        var enterAction: Boolean,
        var suggestions: Boolean,
        var personalizedLearning: Boolean,
        var clipboardHistory: Boolean
    )

    private val accent = Color.rgb(103, 82, 255)
    private val panel = Color.rgb(24, 23, 32)
    private val card = Color.rgb(29, 28, 36)
    private val field = Color.rgb(18, 17, 24)
    private val muted = Color.rgb(155, 151, 170)
    private val border = Color.rgb(57, 54, 70)

    private lateinit var body: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var memoryStatus: TextView
    private var currentTab = Tab.MODEL
    private var draft = loadDraft()
    var activeInput: EditText? = null
        private set

    init {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(panel, 18f, border, 1)
        buildShell()
    }

    fun show(tab: Tab = currentTab) {
        currentTab = tab
        visibility = View.VISIBLE
        renderTabs()
        renderBody()
    }

    fun hidePanel() {
        activeInput?.clearFocus()
        activeInput = null
        onInputFocusChanged(false)
        visibility = View.GONE
        onClose()
    }

    fun refreshExternalChanges() {
        val currentMode = prefs.getString("keyboard_theme_mode", draft.themeMode).orEmpty()
        if (currentMode.isNotBlank()) draft.themeMode = currentMode
        if (currentTab == Tab.THEME && visibility == View.VISIBLE) renderBody()
    }

    private fun buildShell() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        addView(column, LayoutParams(-1, -1))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(2), dp(4))
        }
        header.addView(TextView(context).apply {
            text = "⚙  Pengaturan AI Ads Keyboard"
            textSize = 16.5f
            setTextColor(Color.rgb(140, 126, 255))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(TextView(context).apply {
            text = "v0.20"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            background = rounded(Color.rgb(48, 46, 55), 6f)
        }, LinearLayout.LayoutParams(dp(54), dp(28)).apply { rightMargin = dp(8) })
        header.addView(iconTextButton("×", dp(42)) { hidePanel() })
        column.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        column.addView(tabRow, LinearLayout.LayoutParams(-1, dp(48)))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        footer.addView(TextView(context).apply {
            text = "Reset Bawaan"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { resetDraft() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        footer.addView(footerButton("Batal", false) { hidePanel() }, LinearLayout.LayoutParams(dp(74), dp(40)).apply {
            rightMargin = dp(8)
        })
        footer.addView(footerButton("Simpan & Terapkan", true) { saveDraft() }, LinearLayout.LayoutParams(dp(156), dp(40)))
        column.addView(footer, LinearLayout.LayoutParams(-1, dp(56)))

        renderTabs()
        renderBody()
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        val tabs = listOf(
            Tab.MODEL to "🤖 Model AI",
            Tab.MEMORY to "✍ Memori Gaya",
            Tab.THEME to "🎨 Tema & Tampilan",
            Tab.TYPING to "⌨ Ketikan & Saran"
        )
        tabs.forEach { (tab, label) ->
            val selected = tab == currentTab
            tabRow.addView(TextView(context).apply {
                text = label
                textSize = 10.2f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (selected) Color.rgb(150, 135, 255) else Color.rgb(195, 190, 204))
                background = if (selected) rounded(Color.rgb(38, 34, 59), 7f, accent, 1) else null
                setOnClickListener {
                    if (currentTab != tab) {
                        activeInput?.clearFocus()
                        activeInput = null
                        currentTab = tab
                        renderTabs()
                        renderBody()
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
    }

    private fun renderBody() {
        body.removeAllViews()
        when (currentTab) {
            Tab.MODEL -> renderModelTab()
            Tab.MEMORY -> renderMemoryTab()
            Tab.THEME -> renderThemeTab()
            Tab.TYPING -> renderTypingTab()
        }
    }

    private fun renderModelTab() {
        body.addView(section("Penyedia AI Utama"))
        val providers = listOf(AiProvider.OPENROUTER, AiProvider.TABIAI, AiProvider.NINEROUTER, AiProvider.BLUESMINDS)
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        providers.chunked(2).forEach { rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { provider ->
                val selected = draft.provider == provider
                row.addView(TextView(context).apply {
                    text = when (provider) {
                        AiProvider.NINEROUTER -> "9Router"
                        else -> provider.label
                    }
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(if (selected) Color.rgb(190, 180, 255) else Color.rgb(210, 207, 218))
                    background = rounded(if (selected) Color.rgb(33, 29, 55) else card, 10f, if (selected) accent else border, 1)
                    setOnClickListener {
                        draft.provider = provider
                        renderBody()
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            if (rowItems.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(grid)

        val config = cardContainer()
        config.addView(section("${providerLabel(draft.provider)} Konfigurasi", compact = true))
        when (draft.provider) {
            AiProvider.OPENROUTER -> {
                config.addView(textInput("API Key OpenRouter", draft.openRouterKey, secret = true) { draft.openRouterKey = it })
                config.addView(textInput("Nama Model", draft.openRouterModel) { draft.openRouterModel = it })
            }
            AiProvider.TABIAI -> {
                config.addView(textInput("API Key TabiAI", draft.tabiKey, secret = true) { draft.tabiKey = it })
                config.addView(textInput("Base URL", draft.tabiBaseUrl) { draft.tabiBaseUrl = it })
                config.addView(textInput("Nama Model", draft.tabiModel) { draft.tabiModel = it })
            }
            AiProvider.NINEROUTER -> {
                config.addView(textInput("API Key 9Router", draft.nineRouterKey, secret = true) { draft.nineRouterKey = it })
                config.addView(textInput("Base URL Gateway", draft.nineRouterBaseUrl) { draft.nineRouterBaseUrl = it })
                config.addView(textInput("Nama Model / Combo", draft.nineRouterModel) { draft.nineRouterModel = it })
            }
            AiProvider.BLUESMINDS -> {
                config.addView(textInput("API Key BluesMinds", draft.bluesMindsKey, secret = true) { draft.bluesMindsKey = it })
                config.addView(textInput("Base URL BluesMinds", draft.bluesMindsBaseUrl) { draft.bluesMindsBaseUrl = it })
                config.addView(textInput("Nama Model", draft.bluesMindsModel) { draft.bluesMindsModel = it })
            }
        }
        body.addView(config, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        body.addView(toggleCard(
            "Aktifkan Fallback Penyedia",
            "Otomatis beralih ke provider lain bila provider utama gagal.",
            draft.fallbackEnabled
        ) { draft.fallbackEnabled = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val urls = cardContainer()
        urls.addView(section("URL Referensi Domain", compact = true))
        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))
        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })
        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun renderMemoryTab() {
        body.addView(toggleCard(
            "Pelajari & Tiru Gaya Ketikan Pribadi",
            "AI membaca nada bicara, sapaan, singkatan, dan kebiasaan tanda baca secara lokal.",
            draft.styleMemoryEnabled
        ) { draft.styleMemoryEnabled = it })

        val statusCard = cardContainer()
        statusCard.addView(section("Status Memori Gaya Saat Ini", compact = true))
        memoryStatus = TextView(context).apply {
            text = TypingStyleMemory.summary(prefs)
            textSize = 12.5f
            setTextColor(Color.rgb(220, 216, 228))
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(Color.rgb(16, 15, 22), 8f)
        }
        statusCard.addView(memoryStatus, LinearLayout.LayoutParams(-1, -2))
        statusCard.addView(actionButton("Hapus Memori Gaya", danger = true) {
            TypingStyleMemory.clear(prefs)
            memoryStatus.text = TypingStyleMemory.summary(prefs)
        }, LinearLayout.LayoutParams(-2, dp(40)).apply {
            gravity = Gravity.END
            topMargin = dp(8)
        })
        body.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val importCard = cardContainer()
        importCard.addView(section("Impor Contoh Tulisan", compact = true))
        importCard.addView(description("Tempelkan contoh chat atau postingan agar keyboard langsung mengenali pola tulisanmu."))
        var sampleField: EditText? = null
        sampleField = textInput("Contoh: Halo kak, mau nanya nih…", "", multiline = true) { }
        importCard.addView(sampleField)
        importCard.addView(actionButton("Pelajari Teks Ini") {
            val value = sampleField.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(context, "Masukkan contoh tulisan terlebih dahulu.", Toast.LENGTH_SHORT).show()
            } else {
                TypingStyleMemory.observeCompletedText(prefs, value)
                memoryStatus.text = TypingStyleMemory.summary(prefs)
                Toast.makeText(context, "Contoh gaya sudah dipelajari.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, dp(42)).apply {
            gravity = Gravity.END
            topMargin = dp(8)
        })
        body.addView(importCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val phraseCard = cardContainer()
        phraseCard.addView(section("Kalimat Tersimpan", compact = true))
        phraseCard.addView(textInput("Email atau kalimat tersimpan, satu per baris", draft.personalPhrases, multiline = true) { draft.personalPhrases = it })
        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun renderThemeTab() {
        body.addView(section("Tema Keyboard"))
        val customPreview = runCatching { Color.parseColor(KeyboardTheme.normalizeColor(draft.themeColor)) }.getOrDefault(accent)
        val themeOptions = listOf(
            Triple(KeyboardTheme.MODE_DARK, "Gelap Modern (Default)", Color.rgb(138, 112, 255)),
            Triple(KeyboardTheme.MODE_BLUE, "Deep Navy", Color.rgb(52, 166, 255)),
            Triple(KeyboardTheme.MODE_PURPLE, "Cyber Purple", Color.rgb(197, 73, 255)),
            Triple(KeyboardTheme.MODE_ROSE, "Ruby Crimson", Color.rgb(255, 73, 98)),
            Triple(KeyboardTheme.MODE_GREEN, "Emerald Forest", Color.rgb(48, 215, 132)),
            Triple(KeyboardTheme.MODE_AMOLED, "Amoled Pitch Black", Color.rgb(151, 116, 255)),
            Triple(KeyboardTheme.MODE_CUSTOM, "Kustomisasi Warna", customPreview),
            Triple(KeyboardTheme.MODE_PHOTO, "Foto dari Perangkat", Color.rgb(255, 183, 77))
        )
        themeOptions.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (mode, label, previewColor) ->
                val selected = draft.themeMode == mode
                row.addView(TextView(context).apply {
                    text = themePreviewText(label, previewColor)
                    gravity = Gravity.CENTER
                    textSize = 11.5f
                    setTextColor(Color.WHITE)
                    background = rounded(if (selected) Color.rgb(31, 28, 55) else card, 12f, if (selected) accent else border, if (selected) 1 else 1)
                    setOnClickListener {
                        draft.themeMode = mode
                        renderBody()
                    }
                }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            body.addView(row)
        }

        if (draft.themeMode == KeyboardTheme.MODE_CUSTOM) {
            body.addView(textInput("Warna, contoh #5D4AC4", draft.themeColor) { draft.themeColor = it }, LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(8)
            })
        }

        if (draft.themeMode == KeyboardTheme.MODE_PHOTO) {
            val photoCard = cardContainer()
            photoCard.addView(section("Foto Latar dari Perangkat", compact = true))
            val hasPhoto = !prefs.getString("keyboard_theme_image_uri", "").isNullOrBlank()
            photoCard.addView(description(if (hasPhoto) "✓ Foto perangkat sudah dipilih. Tekan tombol di bawah untuk mengganti." else "Pilih foto langsung dari galeri HP untuk dijadikan latar keyboard."))
            photoCard.addView(actionButton(if (hasPhoto) "Ganti Foto dari HP" else "Pilih Foto dari HP") {
                draft.themeMode = KeyboardTheme.MODE_PHOTO
                val pickerIntent = Intent(context, ThemePhotoPickerActivity::class.java)
                if (context !is android.app.Activity) {
                    pickerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(pickerIntent)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
            if (hasPhoto) {
                photoCard.addView(actionButton("Hapus Foto", danger = true) {
                    prefs.edit().remove("keyboard_theme_image_uri").apply()
                    draft.themeMode = KeyboardTheme.MODE_DARK
                    renderBody()
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
            }
            body.addView(photoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        val sizeCard = cardContainer()
        sizeCard.addView(sliderRow("Tinggi Keyboard (Portrait)", 170, 330, draft.portraitHeight, " dp") { draft.portraitHeight = it })
        sizeCard.addView(sliderRow("Tinggi Keyboard (Landscape)", 90, 190, draft.landscapeHeight, " dp") { draft.landscapeHeight = it })
        body.addView(sizeCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val keyCard = cardContainer()
        keyCard.addView(sliderRow("Ukuran Teks Tombol", 16, 28, draft.keyTextSize, " sp") { draft.keyTextSize = it })
        keyCard.addView(sliderRow("Skala Kotak Tombol", 65, 110, draft.keyBoxScale, "%") { draft.keyBoxScale = it })
        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })
        body.addView(keyCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        body.addView(toggleCard("Tampilkan Baris Angka (1–0)", "Menyematkan baris angka di atas QWERTY.", draft.numberRow) { draft.numberRow = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun renderTypingTab() {
        body.addView(toggleCard("Efek Suara Tombol (Click Sound)", "Memainkan suara klik saat tombol disentuh.", draft.sound) { draft.sound = it })
        body.addView(toggleCard("Getaran / Umpan Balik Haptik", "Memberikan getaran taktil saat mengetik.", draft.vibration) { draft.vibration = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val vibrationCard = cardContainer()
        vibrationCard.addView(sliderRow("Durasi Getar", 5, 80, draft.vibrationDuration, " ms") { draft.vibrationDuration = it })
        body.addView(vibrationCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Huruf Kapital Otomatis", "Mengkapitalkan huruf pertama kalimat.", draft.autoCaps) { draft.autoCaps = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Titik Spasi Ganda", "Ketuk spasi dua kali untuk memasukkan titik dan spasi.", draft.doubleSpacePeriod) { draft.doubleSpacePeriod = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Saran Teks Prediktif & Koreksi Ketik", "Menampilkan rekomendasi kata berikutnya dan koreksi typo.", draft.suggestions) { draft.suggestions = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Riwayat Papan Klip", "Menyimpan teks yang disalin agar dapat ditempel kembali.", draft.clipboardHistory) { draft.clipboardHistory = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Tekan Lama untuk Simbol", "Simbol kecil di sudut tombol dapat diketik dengan menahan tombol.", draft.longPressSymbols) { draft.longPressSymbols = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Pelajari Tulisan yang Sering Dipakai", "Menyimpan kata dan frasa secara lokal untuk prediksi.", draft.personalizedLearning) { draft.personalizedLearning = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Spasi Otomatis Setelah Tanda Baca", "Menambahkan spasi setelah tanda baca utama.", draft.punctuationSpace) { draft.punctuationSpace = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Enter Menjalankan Aksi Aplikasi", "Gunakan aksi Kirim/Cari/Selesai dari aplikasi jika tersedia.", draft.enterAction) { draft.enterAction = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun saveDraft() {
        prefs.edit()
            .putString("provider", draft.provider.id)
            .putString("openrouter_api_key", draft.openRouterKey.trim())
            .putString("openrouter_model", draft.openRouterModel.trim().ifBlank { "openrouter/free" })
            .putString("tabi_api_key", draft.tabiKey.trim())
            .putString("tabi_base_url", draft.tabiBaseUrl.trim().ifBlank { "https://tabitoken.com" })
            .putString("tabi_model", draft.tabiModel.trim().ifBlank { "claude-opus-5" })
            .putString("9router_api_key", draft.nineRouterKey.trim())
            .putString("9router_base_url", draft.nineRouterBaseUrl.trim().ifBlank { "http://43.159.50.231:20130/v1" })
            .putString("9router_model", draft.nineRouterModel.trim().ifBlank { "cc/claude-sonnet-4-20250514" })
            .putString("bluesminds_api_key", draft.bluesMindsKey.trim())
            .putString("bluesminds_base_url", draft.bluesMindsBaseUrl.trim().ifBlank { "https://api.bluesminds.com/v1" })
            .putString("bluesminds_model", draft.bluesMindsModel.trim().ifBlank { "deepseek-ai/deepseek-v4-flash" })
            .putBoolean("fallback_enabled", draft.fallbackEnabled)
            .putString("reference_urls", draft.referenceUrls.trim())
            .putBoolean("style_memory_enabled", draft.styleMemoryEnabled)
            .putString("personal_phrases", draft.personalPhrases.trim())
            .putString("keyboard_theme_mode", draft.themeMode)
            .putString("keyboard_theme_color", KeyboardTheme.normalizeColor(draft.themeColor))
            .putInt("keyboard_height_portrait_dp", draft.portraitHeight)
            .putInt("keyboard_height_landscape_dp", draft.landscapeHeight)
            .putInt("keyboard_layout_version", 9)
            .putInt("key_text_size_sp", draft.keyTextSize)
            .putInt("key_box_scale_percent", draft.keyBoxScale)
            .putInt("long_press_ms", draft.longPressMs)
            .putBoolean("number_row_enabled", draft.numberRow)
            .putBoolean("long_press_symbols_enabled", draft.longPressSymbols)
            .putBoolean("sound_enabled", draft.sound)
            .putBoolean("vibration_enabled", draft.vibration)
            .putInt("vibration_duration_ms", draft.vibrationDuration)
            .putBoolean("automatic_capitalization_enabled", draft.autoCaps)
            .putBoolean("punctuation_space_enabled", draft.punctuationSpace)
            .putBoolean("double_space_period_enabled", draft.doubleSpacePeriod)
            .putBoolean("enter_action_enabled", draft.enterAction)
            .putBoolean("suggestions_enabled", draft.suggestions)
            .putBoolean("personalized_learning_enabled", draft.personalizedLearning)
            .putBoolean("clipboard_history_enabled", draft.clipboardHistory)
            .apply()
        onApply()
        Toast.makeText(context, "Pengaturan diterapkan.", Toast.LENGTH_SHORT).show()
        hidePanel()
    }

    private fun resetDraft() {
        draft = defaultDraft()
        activeInput?.clearFocus()
        activeInput = null
        renderBody()
        Toast.makeText(context, "Nilai bawaan dimuat. Tekan Simpan & Terapkan untuk menyimpan.", Toast.LENGTH_SHORT).show()
    }

    private fun loadDraft() = Draft(
        provider = AiProvider.fromId(prefs.getString("provider", null)),
        openRouterKey = prefs.getString("openrouter_api_key", prefs.getString("api_key", "")).orEmpty(),
        openRouterModel = prefs.getString("openrouter_model", "openrouter/free").orEmpty(),
        tabiKey = prefs.getString("tabi_api_key", "").orEmpty(),
        tabiBaseUrl = prefs.getString("tabi_base_url", "https://tabitoken.com").orEmpty(),
        tabiModel = prefs.getString("tabi_model", "claude-opus-5").orEmpty(),
        nineRouterKey = prefs.getString("9router_api_key", "").orEmpty(),
        nineRouterBaseUrl = prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1").orEmpty(),
        nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),
        bluesMindsKey = prefs.getString("bluesminds_api_key", "").orEmpty(),
        bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),
        bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),
        fallbackEnabled = prefs.getBoolean("fallback_enabled", false),
        referenceUrls = prefs.getString("reference_urls", "").orEmpty(),
        styleMemoryEnabled = prefs.getBoolean("style_memory_enabled", true),
        personalPhrases = prefs.getString("personal_phrases", "").orEmpty(),
        themeMode = prefs.getString("keyboard_theme_mode", KeyboardTheme.MODE_DARK).orEmpty(),
        themeColor = prefs.getString("keyboard_theme_color", "#5D4AC4").orEmpty(),
        portraitHeight = prefs.getInt("keyboard_height_portrait_dp", 220),
        landscapeHeight = prefs.getInt("keyboard_height_landscape_dp", 120),
        keyTextSize = prefs.getInt("key_text_size_sp", 21),
        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),
        longPressMs = prefs.getInt("long_press_ms", 450),
        numberRow = prefs.getBoolean("number_row_enabled", true),
        longPressSymbols = prefs.getBoolean("long_press_symbols_enabled", true),
        sound = prefs.getBoolean("sound_enabled", false),
        vibration = prefs.getBoolean("vibration_enabled", true),
        vibrationDuration = prefs.getInt("vibration_duration_ms", 28),
        autoCaps = prefs.getBoolean("automatic_capitalization_enabled", true),
        punctuationSpace = prefs.getBoolean("punctuation_space_enabled", false),
        doubleSpacePeriod = prefs.getBoolean("double_space_period_enabled", false),
        enterAction = prefs.getBoolean("enter_action_enabled", false),
        suggestions = prefs.getBoolean("suggestions_enabled", true),
        personalizedLearning = prefs.getBoolean("personalized_learning_enabled", true),
        clipboardHistory = prefs.getBoolean("clipboard_history_enabled", true)
    )

    private fun defaultDraft() = Draft(
        provider = AiProvider.OPENROUTER,
        openRouterKey = draft.openRouterKey,
        openRouterModel = "openrouter/free",
        tabiKey = draft.tabiKey,
        tabiBaseUrl = "https://tabitoken.com",
        tabiModel = "claude-opus-5",
        nineRouterKey = draft.nineRouterKey,
        nineRouterBaseUrl = "http://43.159.50.231:20130/v1",
        nineRouterModel = "cc/claude-sonnet-4-20250514",
        bluesMindsKey = draft.bluesMindsKey,
        bluesMindsBaseUrl = "https://api.bluesminds.com/v1",
        bluesMindsModel = "deepseek-ai/deepseek-v4-flash",
        fallbackEnabled = false,
        referenceUrls = "",
        styleMemoryEnabled = true,
        personalPhrases = draft.personalPhrases,
        themeMode = KeyboardTheme.MODE_DARK,
        themeColor = "#5D4AC4",
        portraitHeight = 220,
        landscapeHeight = 120,
        keyTextSize = 21,
        keyBoxScale = 100,
        longPressMs = 450,
        numberRow = true,
        longPressSymbols = true,
        sound = false,
        vibration = true,
        vibrationDuration = 28,
        autoCaps = true,
        punctuationSpace = false,
        doubleSpacePeriod = false,
        enterAction = false,
        suggestions = true,
        personalizedLearning = true,
        clipboardHistory = true
    )

    private fun providerLabel(provider: AiProvider) = when (provider) {
        AiProvider.OPENROUTER -> "OpenRouter"
        AiProvider.TABIAI -> "TabiAI"
        AiProvider.NINEROUTER -> "9Router"
        AiProvider.BLUESMINDS -> "BluesMinds"
    }

    private fun textInput(
        hintText: String,
        initial: String,
        secret: Boolean = false,
        multiline: Boolean = false,
        onChanged: (String) -> Unit
    ): EditText = EditText(context).apply {
        hint = hintText
        setText(initial)
        textSize = 12.5f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(110, 106, 121))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minLines = if (multiline) 3 else 1
        maxLines = if (multiline) 6 else 1
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        showSoftInputOnFocus = false
        background = rounded(field, 8f, border, 1)
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeInput = this
                onInputFocusChanged(true)
            } else if (activeInput === this) {
                activeInput = null
                onInputFocusChanged(false)
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { onChanged(s?.toString().orEmpty()) }
        })
        layoutParams = LinearLayout.LayoutParams(-1, if (multiline) dp(84) else dp(46)).apply { topMargin = dp(6) }
    }

    private fun section(text: String, compact: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = if (compact) 13f else 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (compact) Color.rgb(155, 142, 255) else Color.rgb(224, 220, 231))
        setPadding(0, if (compact) dp(2) else dp(6), 0, dp(4))
    }

    private fun description(textValue: String) = TextView(context).apply {
        text = textValue
        textSize = 11f
        setTextColor(muted)
        setPadding(0, 0, 0, dp(4))
    }

    private fun cardContainer() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(card, 12f, border, 1)
    }

    private fun toggleCard(title: String, detail: String, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = rounded(card, 12f, border, 1)
        }
        val check = CheckBox(context).apply {
            isChecked = checked
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(check, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = detail
                textSize = 10.5f
                setTextColor(muted)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { check.isChecked = !check.isChecked }
        return row
    }

    private fun sliderRow(label: String, min: Int, max: Int, current: Int, suffix: String, onChanged: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val value = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(197, 189, 255))
        }
        val slider = SeekBar(context).apply {
            this.max = max - min
            progress = current.coerceIn(min, max) - min
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        fun refresh(p: Int) { value.text = "$label     ${p + min}$suffix" }
        refresh(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refresh(progress)
                onChanged(progress + min)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        wrap.addView(value, LinearLayout.LayoutParams(-1, dp(28)))
        wrap.addView(slider, LinearLayout.LayoutParams(-1, dp(34)))
        return wrap
    }

    private fun themePreviewText(label: String, previewColor: Int): SpannableString {
        val value = SpannableString("Aa 123\n$label")
        value.setSpan(ForegroundColorSpan(previewColor), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        value.setSpan(ForegroundColorSpan(Color.WHITE), 7, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return value
    }

    private fun actionButton(label: String, danger: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 11.5f
        isAllCaps = false
        setTextColor(if (danger) Color.rgb(255, 110, 145) else Color.WHITE)
        background = rounded(if (danger) Color.rgb(42, 22, 31) else accent, 8f, if (danger) Color.rgb(105, 35, 55) else accent, 1)
        setOnClickListener { action() }
    }

    private fun footerButton(label: String, primary: Boolean, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 11.5f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(if (primary) Color.rgb(85, 61, 245) else Color.rgb(50, 48, 57), 9f)
        setOnClickListener { action() }
    }

    private fun iconTextButton(label: String, size: Int, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(190, 186, 200))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpFloat(radius)
        if (stroke != null && strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpFloat(value: Float) = value * resources.displayMetrics.density
}
