package com.riyan.aikeyboard

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("riyan_ai", MODE_PRIVATE) }
    private lateinit var contextText: EditText
    private lateinit var themeModeSpinner: Spinner
    private lateinit var themeImageStatus: TextView

    private val themeImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit()
            .putString("keyboard_theme_image_uri", uri.toString())
            .putString("keyboard_theme_mode", KeyboardTheme.MODE_PHOTO)
            .apply()
        if (::themeModeSpinner.isInitialized) {
            themeModeSpinner.setSelection(KeyboardTheme.modes.indexOfFirst { it.first == KeyboardTheme.MODE_PHOTO })
        }
        if (::themeImageStatus.isInitialized) {
            themeImageStatus.text = "✓ Foto galeri dipilih dan akan dipakai sebagai latar keyboard."
        }
        Toast.makeText(this, "Foto tema tersimpan.", Toast.LENGTH_SHORT).show()
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val image = InputImage.fromFilePath(this, uri)
        Toast.makeText(this, "Membaca teks gambar…", Toast.LENGTH_SHORT).show()
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener {
                contextText.setText(it.text)
                prefs.edit()
                    .putString("shared_context", it.text)
                    .putLong("shared_context_updated_at", System.currentTimeMillis())
                    .apply()
                Toast.makeText(this, "Teks gambar siap dipakai keyboard.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { Toast.makeText(this, it.message ?: "OCR gagal", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateOldHeight()

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(40))
        }
        root.addView(TextView(this).apply {
            text = "AI Ads Keyboard"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Versi 0.18.0 · Kamera dan web di panel atas, AI manual, dan mode coding."
            textSize = 14f
            setPadding(0, dp(7), 0, dp(14))
        })

        root.addView(Button(this).apply {
            text = "1. Aktifkan keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Pilih keyboard"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showInputMethodPicker()
            }
        })

        root.addView(sectionTitle("Tema keyboard"))
        root.addView(description("Pilih warna siap pakai, warna sendiri, atau gunakan foto dari galeri sebagai latar. Foto tetap tersimpan setelah HP dimulai ulang."))
        themeModeSpinner = Spinner(this)
        themeModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            KeyboardTheme.modes.map { it.second }
        )
        val currentTheme = prefs.getString("keyboard_theme_mode", KeyboardTheme.MODE_DARK)
        themeModeSpinner.setSelection(KeyboardTheme.modes.indexOfFirst { it.first == currentTheme }.coerceAtLeast(0))
        root.addView(themeModeSpinner, ViewGroup.LayoutParams(-1, -2))

        val customThemeColor = EditText(this).apply {
            hint = "Warna sendiri, contoh #5D4AC4"
            setText(prefs.getString("keyboard_theme_color", "#5D4AC4"))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(customThemeColor, ViewGroup.LayoutParams(-1, -2))
        val photoDim = settingSlider(root, "Kegelapan foto agar huruf terbaca", 10, 85, prefs.getInt("keyboard_theme_photo_dim", 48), "%")
        themeImageStatus = TextView(this).apply {
            text = if (prefs.getString("keyboard_theme_image_uri", "").isNullOrBlank()) {
                "Belum ada foto tema yang dipilih."
            } else {
                "✓ Foto galeri sudah tersimpan."
            }
            textSize = 13f
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(themeImageStatus, ViewGroup.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Pilih foto dari galeri"
            setOnClickListener { themeImagePicker.launch(arrayOf("image/*")) }
        })
        root.addView(Button(this).apply {
            text = "Hapus foto tema"
            setOnClickListener {
                val oldUri = prefs.getString("keyboard_theme_image_uri", "").orEmpty()
                if (oldUri.isNotBlank()) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            android.net.Uri.parse(oldUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                prefs.edit()
                    .remove("keyboard_theme_image_uri")
                    .putString("keyboard_theme_mode", KeyboardTheme.MODE_DARK)
                    .apply()
                themeModeSpinner.setSelection(0)
                themeImageStatus.text = "Foto tema dihapus."
            }
        })

        root.addView(sectionTitle("Ukuran dan respons"))
        root.addView(description("Tinggi potret dan lanskap disimpan terpisah. Batas minimum kembali diturunkan dan keduanya dapat diubah langsung lewat tombol ↕ pada keyboard."))
        val portraitHeight = settingSlider(root, "Tinggi mode potret", 170, 330, prefs.getInt("keyboard_height_portrait_dp", 220), " dp")
        val landscapeHeight = settingSlider(root, "Tinggi mode lanskap", 90, 190, prefs.getInt("keyboard_height_landscape_dp", 120), " dp")
        val keyTextSize = settingSlider(root, "Ukuran huruf tombol", 16, 28, prefs.getInt("key_text_size_sp", 21), " sp")
        val keyBoxScale = settingSlider(root, "Ukuran kotak tombol", 65, 110, prefs.getInt("key_box_scale_percent", 100), "%")
        root.addView(description("Naikkan sampai 110% agar badan tombol lebih besar. Area sentuh tetap dipisahkan supaya tombol di sebelahnya tidak mudah ikut tertekan."))
        val touchSensitivity = settingSlider(root, "Sensitivitas dan kecepatan tombol", 20, 400, prefs.getInt("touch_sensitivity", 100), "%")
        root.addView(description("Mulai 150%, huruf langsung masuk saat tombol disentuh tanpa menunggu jari dilepas. Rentang gerakan juga diperluas sampai 400% agar ketukan cepat tidak terlewat."))
        val longPressDuration = settingSlider(root, "Penundaan tekan lama", 200, 900, prefs.getInt("long_press_ms", 450), " ms")

        root.addView(sectionTitle("Masukan"))
        val numberRow = switchSetting(root, "Baris nomor", "Tampilkan angka 1–0 di atas huruf.", prefs.getBoolean("number_row_enabled", true))
        val longPressSymbols = switchSetting(root, "Tekan lama untuk simbol", "Tampilkan simbol kecil dan ketik dengan menahan tombol.", prefs.getBoolean("long_press_symbols_enabled", true))
        val automaticCapitalization = switchSetting(root, "Kapitalisasi otomatis", "Gunakan huruf besar di awal kalimat.", prefs.getBoolean("automatic_capitalization_enabled", true))
        val punctuationSpace = switchSetting(root, "Spasi otomatis setelah tanda baca", "Tambahkan spasi setelah tanda baca utama.", prefs.getBoolean("punctuation_space_enabled", false))
        val doubleSpacePeriod = switchSetting(root, "Titik dengan spasi ganda", "Mengetuk spasi dua kali memasukkan titik dan spasi.", prefs.getBoolean("double_space_period_enabled", false))
        val enterAction = switchSetting(root, "Enter menjalankan aksi aplikasi", "Matikan agar Enter selalu membuat baris baru dan tidak menutup keyboard. Aktifkan hanya jika Enter ingin dipakai sebagai Kirim/Selesai.", prefs.getBoolean("enter_action_enabled", false))

        root.addView(sectionTitle("Suara dan getaran"))
        val sound = switchSetting(root, "Suara saat tombol ditekan", "Putar suara klik saat mengetik.", prefs.getBoolean("sound_enabled", false))
        val vibration = switchSetting(root, "Getar saat tombol ditekan", "Berikan umpan balik getar pada setiap tombol.", prefs.getBoolean("vibration_enabled", true))
        val vibrationDuration = settingSlider(root, "Durasi getar", 5, 80, prefs.getInt("vibration_duration_ms", 28), " ms")

        root.addView(sectionTitle("Clipboard"))
        val clipboardHistory = switchSetting(root, "Simpan riwayat clipboard", "Simpan hingga 12 klip secara lokal dan izinkan klip disematkan.", prefs.getBoolean("clipboard_history_enabled", true))
        root.addView(Button(this).apply {
            text = "Hapus seluruh riwayat clipboard"
            setOnClickListener {
                prefs.edit().putString("clipboard_items", "[]").apply()
                Toast.makeText(this@MainActivity, "Riwayat clipboard dihapus.", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(sectionTitle("Koreksi dan kalimat tersimpan"))
        val suggestions = switchSetting(root, "Prediksi dan koreksi otomatis", "Bar koreksi hanya muncul setelah ada 2–3 huruf yang cocok, lalu tersembunyi lagi saat tidak diperlukan.", prefs.getBoolean("suggestions_enabled", true))
        val personalizedLearning = switchSetting(root, "Pelajari tulisan yang sering dipakai", "Simpan kata, email, dan frasa yang sering diketik hanya di perangkat. Kolom sandi tidak pernah dipelajari.", prefs.getBoolean("personalized_learning_enabled", true))
        val styleMemory = switchSetting(root, "Memori gaya untuk AI", "Pelajari pilihan kata, sapaan, singkatan, panjang kalimat, dan tingkat formalitas secara lokal agar hasil AI mengikuti gaya ketikanmu. Kolom sandi tidak dipelajari.", prefs.getBoolean("style_memory_enabled", true))
        val styleMemoryStatus = TextView(this).apply {
            text = TypingStyleMemory.summary(prefs)
            textSize = 13f
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        root.addView(styleMemoryStatus, ViewGroup.LayoutParams(-1, -2))
        val personalPhrases = EditText(this).apply {
            hint = "Email atau kalimat tersimpan, satu per baris"
            minLines = 3
            maxLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("personal_phrases", ""))
        }
        root.addView(personalPhrases, ViewGroup.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Hapus hasil pembelajaran otomatis"
            setOnClickListener {
                prefs.edit().remove("learned_suggestions").remove("learned_words").apply()
                Toast.makeText(this@MainActivity, "Kata dan kalimat yang dipelajari telah dihapus.", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Hapus memori gaya AI"
            setOnClickListener {
                TypingStyleMemory.clear(prefs)
                styleMemoryStatus.text = TypingStyleMemory.summary(prefs)
                Toast.makeText(this@MainActivity, "Memori gaya AI telah dihapus.", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "Simpan pengaturan keyboard"
            setOnClickListener {
                prefs.edit()
                    .putInt("keyboard_height_portrait_dp", portraitHeight.progress + 170)
                    .putInt("keyboard_height_landscape_dp", landscapeHeight.progress + 90)
                    .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                    .putInt("key_text_size_sp", keyTextSize.progress + 16)
                    .putInt("key_box_scale_percent", keyBoxScale.progress + 65)
                    .putInt("touch_sensitivity", touchSensitivity.progress + 20)
                    .putInt("long_press_ms", longPressDuration.progress + 200)
                    .putString("keyboard_theme_mode", KeyboardTheme.modes[themeModeSpinner.selectedItemPosition].first)
                    .putString("keyboard_theme_color", KeyboardTheme.normalizeColor(customThemeColor.text.toString()))
                    .putInt("keyboard_theme_photo_dim", photoDim.progress + 10)
                    .putBoolean("number_row_enabled", numberRow.isChecked)
                    .putBoolean("long_press_symbols_enabled", longPressSymbols.isChecked)
                    .putBoolean("automatic_capitalization_enabled", automaticCapitalization.isChecked)
                    .putBoolean("punctuation_space_enabled", punctuationSpace.isChecked)
                    .putBoolean("double_space_period_enabled", doubleSpacePeriod.isChecked)
                    .putBoolean("enter_action_enabled", enterAction.isChecked)
                    .putBoolean("sound_enabled", sound.isChecked)
                    .putBoolean("vibration_enabled", vibration.isChecked)
                    .putInt("vibration_duration_ms", vibrationDuration.progress + 5)
                    .putBoolean("clipboard_history_enabled", clipboardHistory.isChecked)
                    .putBoolean("suggestions_enabled", suggestions.isChecked)
                    .putBoolean("personalized_learning_enabled", personalizedLearning.isChecked)
                    .putBoolean("style_memory_enabled", styleMemory.isChecked)
                    .putString("personal_phrases", personalPhrases.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Pengaturan keyboard tersimpan.", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(sectionTitle("AI dari teks manual"))
        root.addView(description("Tempel atau ketik teks langsung di kotak Obrolan AI. Perbaiki, Balas, Terjemah, Ringkas, Santai, dan Sopan hanya memproses teks di kotak AI dan tidak membaca layar aplikasi."))

        root.addView(sectionTitle("Provider AI utama"))
        val provider = Spinner(this)
        val providerOptions = AiProvider.entries.toTypedArray()
        provider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providerOptions.map {
                when (it) {
                    AiProvider.TABIAI -> "TabiAI (tabitoken.com)"
                    AiProvider.NINEROUTER -> "9Router (OpenAI-compatible)"
                    else -> it.label
                }
            }
        )
        provider.setSelection(providerOptions.indexOf(AiProvider.fromId(prefs.getString("provider", null))))
        root.addView(provider, ViewGroup.LayoutParams(-1, -2))

        root.addView(sectionTitle("Sumber URL untuk pencarian AI"))
        root.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. Gunakan {query} pada URL pencarian agar kata tersebut otomatis diganti dengan pertanyaanmu. Contoh: https://contoh.com/search?q={query}"))
        val referenceUrls = EditText(this).apply {
            hint = "https://sumber-1.com/search?q={query}\nhttps://sumber-2.com/"
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("reference_urls", ""))
        }
        root.addView(referenceUrls, ViewGroup.LayoutParams(-1, -2))

        root.addView(sectionTitle("OpenRouter"))
        val openRouterKey = secretField("API key OpenRouter", prefs.getString("openrouter_api_key", prefs.getString("api_key", "")))
        val openRouterModel = textField("Model OpenRouter", prefs.getString("openrouter_model", "openrouter/free"))
        root.addView(openRouterKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(openRouterModel, ViewGroup.LayoutParams(-1, -2))

        root.addView(sectionTitle("TabiAI"))
        val tabiKey = secretField("API key TabiAI", prefs.getString("tabi_api_key", ""))
        val tabiBaseUrl = textField("Base URL TabiAI", prefs.getString("tabi_base_url", "https://tabitoken.com"))
        val tabiModel = textField("Model TabiAI", prefs.getString("tabi_model", "claude-opus-5"))
        root.addView(tabiKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(tabiBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(tabiModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("Endpoint Claude: /v1/messages · header: x-api-key"))

        root.addView(sectionTitle("9Router"))
        val nineRouterKey = secretField("API key 9Router", prefs.getString("9router_api_key", ""))
        val nineRouterBaseUrl = textField("Base URL 9Router (Gateway API)", prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1"))
        val nineRouterModel = textField("Model 9Router", prefs.getString("9router_model", "cc/claude-sonnet-4-20250514"))
        root.addView(nineRouterKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(nineRouterBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(nineRouterModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("OpenAI-compatible · dashboard :20128 otomatis dialihkan ke gateway API :20130. Nama Combo seperti My2 dipakai persis. Untuk kamera, aktifkan Vision Adapter/model vision di 9Router."))

        root.addView(sectionTitle("xKiro"))
        val xKiroKey = secretField("API key xKiro", prefs.getString("xkiro_api_key", ""))
        val xKiroBaseUrl = textField("Base URL xKiro", prefs.getString("xkiro_base_url", "https://api.xkiro.com/v1"))
        val xKiroModel = textField("Model xKiro", prefs.getString("xkiro_model", "openai/gpt-5.6-sol"))
        root.addView(xKiroKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(xKiroBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(xKiroModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("OpenAI-compatible · endpoint /v1/chat/completions · Authorization Bearer."))

        root.addView(sectionTitle("OrcaRouter"))
        val orcaRouterKey = secretField("API key OrcaRouter", prefs.getString("orcarouter_api_key", ""))
        val orcaRouterBaseUrl = textField("Base URL OrcaRouter", prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1"))
        val orcaRouterModel = textField("Model OrcaRouter", prefs.getString("orcarouter_model", "orcarouter/free"))
        root.addView(orcaRouterKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(orcaRouterBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(orcaRouterModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("OpenAI-compatible · model bebas diganti sesuai katalog OrcaRouter."))

        val fallback = CheckBox(this).apply {
            text = "Coba provider lain jika provider utama gagal"
            isChecked = prefs.getBoolean("fallback_enabled", false)
        }
        root.addView(fallback)
        root.addView(Button(this).apply {
            text = "Simpan pengaturan AI"
            setOnClickListener {
                val selectedProvider = providerOptions[provider.selectedItemPosition]
                prefs.edit()
                    .putString("provider", selectedProvider.id)
                    .putString("openrouter_api_key", openRouterKey.text.toString().trim())
                    .putString("openrouter_model", openRouterModel.text.toString().trim().ifBlank { "openrouter/free" })
                    .putString("tabi_api_key", tabiKey.text.toString().trim())
                    .putString("tabi_base_url", tabiBaseUrl.text.toString().trim().ifBlank { "https://tabitoken.com" })
                    .putString("tabi_model", tabiModel.text.toString().trim().ifBlank { "claude-opus-5" })
                    .putString("9router_api_key", nineRouterKey.text.toString().trim())
                    .putString("9router_base_url", nineRouterBaseUrl.text.toString().trim().ifBlank { "http://43.159.50.231:20130/v1" })
                    .putString("9router_model", nineRouterModel.text.toString().trim().ifBlank { "cc/claude-sonnet-4-20250514" })
                    .putString("xkiro_api_key", xKiroKey.text.toString().trim())
                    .putString("xkiro_base_url", xKiroBaseUrl.text.toString().trim().ifBlank { "https://api.xkiro.com/v1" })
                    .putString("xkiro_model", xKiroModel.text.toString().trim().ifBlank { "openai/gpt-5.6-sol" })
                    .putString("orcarouter_api_key", orcaRouterKey.text.toString().trim())
                    .putString("orcarouter_base_url", orcaRouterBaseUrl.text.toString().trim().ifBlank { "https://api.orcarouter.ai/v1" })
                    .putString("orcarouter_model", orcaRouterModel.text.toString().trim().ifBlank { "orcarouter/free" })
                    .putString("reference_urls", referenceUrls.text.toString().trim())
                    .putBoolean("fallback_enabled", fallback.isChecked)
                    .apply()
                Toast.makeText(this@MainActivity, "Pengaturan AI tersimpan.", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(sectionTitle("Konteks dari gambar atau teks"))
        root.addView(Button(this).apply {
            text = "Pilih screenshot untuk OCR"
            setOnClickListener { imagePicker.launch("image/*") }
        })
        contextText = EditText(this).apply {
            hint = "Teks dari screenshot atau konteks yang ditempel"
            minLines = 4
            setText(prefs.getString("shared_context", ""))
        }
        root.addView(contextText, ViewGroup.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Simpan konteks untuk keyboard"
            setOnClickListener {
                prefs.edit()
                    .putString("shared_context", contextText.text.toString())
                    .putLong("shared_context_updated_at", System.currentTimeMillis())
                    .apply()
                Toast.makeText(this@MainActivity, "Konteks tersimpan.", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun migrateOldHeight() {
        if (prefs.getInt("keyboard_layout_version", 0) >= KEYBOARD_LAYOUT_VERSION) return
        val old = prefs.getInt("keyboard_height_dp", 350)
        val currentPortrait = prefs.getInt("keyboard_height_portrait_dp", if (old == 350) 220 else old)
        val currentLandscape = prefs.getInt("keyboard_height_landscape_dp", 155)
        val migratedPortrait = if (currentPortrait <= 210) 185 else currentPortrait.coerceIn(170, 330)
        val migratedLandscape = if (currentLandscape <= 135) 105 else currentLandscape.coerceIn(90, 190)
        prefs.edit()
            .putInt("keyboard_height_portrait_dp", migratedPortrait)
            .putInt("keyboard_height_landscape_dp", migratedLandscape)
            .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
            .apply()
    }


    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun description(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setPadding(0, 0, 0, dp(6))
    }

    private fun switchSetting(
        root: LinearLayout,
        title: String,
        detail: String,
        current: Boolean
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 12f
            })
        }
        val toggle = Switch(this).apply {
            isChecked = current
            contentDescription = title
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        root.addView(row, ViewGroup.LayoutParams(-1, -2))
        return toggle
    }

    private fun settingSlider(
        root: LinearLayout,
        label: String,
        minimum: Int,
        maximum: Int,
        current: Int,
        suffix: String
    ): SeekBar {
        val valueLabel = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, 0)
        }
        val slider = SeekBar(this).apply {
            max = maximum - minimum
            progress = current.coerceIn(minimum, maximum) - minimum
            contentDescription = label
        }
        fun updateValue(progress: Int) {
            valueLabel.text = "$label: ${progress + minimum}$suffix"
        }
        updateValue(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateValue(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(valueLabel, ViewGroup.LayoutParams(-1, -2))
        root.addView(slider, ViewGroup.LayoutParams(-1, -2))
        return slider
    }

    private fun textField(label: String, value: String?) = EditText(this).apply {
        hint = label
        setText(value.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun secretField(label: String, value: String?) = EditText(this).apply {
        hint = label
        setText(value.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEYBOARD_LAYOUT_VERSION = 9
    }
}
