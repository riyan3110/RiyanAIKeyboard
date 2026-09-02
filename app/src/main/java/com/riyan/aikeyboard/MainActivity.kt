package com.riyan.aikeyboard

import android.content.Intent
import android.content.ComponentName
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
    private lateinit var accessibilityStatus: TextView

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
            text = "AI Ads Kyboard"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Versi 0.7.0 · Keyboard lengkap dengan AI, emoji, simbol, clipboard, serta ukuran yang dapat diubah langsung dari toolbar ↕."
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

        root.addView(sectionTitle("Pemeriksaan Aksesibilitas"))
        accessibilityStatus = TextView(this).apply {
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(accessibilityStatus, ViewGroup.LayoutParams(-1, -2))
        root.addView(description("AI Ads Kyboard v0.7 tidak memiliki layanan Aksesibilitas. Namun aplikasi bank dapat tetap menolak jika TalkBack, aplikasi otomatisasi, perekam layar, atau layanan dari aplikasi lain masih aktif."))
        root.addView(Button(this).apply {
            text = "Buka pengaturan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        updateAccessibilityStatus()

        root.addView(sectionTitle("Ukuran dan respons"))
        root.addView(description("Tinggi potret dan lanskap disimpan terpisah. Rentang lanskap dibuat lebih rendah dan keduanya dapat diubah langsung lewat tombol ↕ pada keyboard."))
        val portraitHeight = settingSlider(root, "Tinggi mode potret", 210, 360, prefs.getInt("keyboard_height_portrait_dp", 250), " dp")
        val landscapeHeight = settingSlider(root, "Tinggi mode lanskap", 135, 220, prefs.getInt("keyboard_height_landscape_dp", 155), " dp")
        val keyTextSize = settingSlider(root, "Ukuran huruf tombol", 16, 28, prefs.getInt("key_text_size_sp", 21), " sp")
        val touchSensitivity = settingSlider(root, "Sensitivitas sentuhan", 20, 100, prefs.getInt("touch_sensitivity", 65), "%")
        val longPressDuration = settingSlider(root, "Penundaan tekan lama", 200, 900, prefs.getInt("long_press_ms", 450), " ms")

        root.addView(sectionTitle("Masukan"))
        val numberRow = switchSetting(root, "Baris nomor", "Tampilkan angka 1–0 di atas huruf.", prefs.getBoolean("number_row_enabled", true))
        val longPressSymbols = switchSetting(root, "Tekan lama untuk simbol", "Tampilkan simbol kecil dan ketik dengan menahan tombol.", prefs.getBoolean("long_press_symbols_enabled", true))
        val automaticCapitalization = switchSetting(root, "Kapitalisasi otomatis", "Gunakan huruf besar di awal kalimat.", prefs.getBoolean("automatic_capitalization_enabled", true))
        val punctuationSpace = switchSetting(root, "Spasi otomatis setelah tanda baca", "Tambahkan spasi setelah tanda baca utama.", prefs.getBoolean("punctuation_space_enabled", false))
        val doubleSpacePeriod = switchSetting(root, "Titik dengan spasi ganda", "Mengetuk spasi dua kali memasukkan titik dan spasi.", prefs.getBoolean("double_space_period_enabled", false))

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
            text = "Simpan pengaturan keyboard"
            setOnClickListener {
                prefs.edit()
                    .putInt("keyboard_height_portrait_dp", portraitHeight.progress + 210)
                    .putInt("keyboard_height_landscape_dp", landscapeHeight.progress + 135)
                    .putInt("keyboard_layout_version", 6)
                    .putInt("key_text_size_sp", keyTextSize.progress + 16)
                    .putInt("touch_sensitivity", touchSensitivity.progress + 20)
                    .putInt("long_press_ms", longPressDuration.progress + 200)
                    .putBoolean("number_row_enabled", numberRow.isChecked)
                    .putBoolean("long_press_symbols_enabled", longPressSymbols.isChecked)
                    .putBoolean("automatic_capitalization_enabled", automaticCapitalization.isChecked)
                    .putBoolean("punctuation_space_enabled", punctuationSpace.isChecked)
                    .putBoolean("double_space_period_enabled", doubleSpacePeriod.isChecked)
                    .putBoolean("sound_enabled", sound.isChecked)
                    .putBoolean("vibration_enabled", vibration.isChecked)
                    .putInt("vibration_duration_ms", vibrationDuration.progress + 5)
                    .putBoolean("clipboard_history_enabled", clipboardHistory.isChecked)
                    .putBoolean("suggestions_enabled", suggestions.isChecked)
                    .putBoolean("personalized_learning_enabled", personalizedLearning.isChecked)
                    .putString("personal_phrases", personalPhrases.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Pengaturan keyboard tersimpan.", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(sectionTitle("Balasan dan bahasa otomatis"))
        root.addView(description("Salin atau bagikan teks ke AI Ads Kyboard, buka kolom balasan, lalu tekan Balas. AI mengenali bahasa pesan dan membalas dengan bahasa serta gaya yang sesuai tanpa layanan Aksesibilitas."))

        root.addView(sectionTitle("Provider AI utama"))
        val provider = Spinner(this)
        val providerOptions = AiProvider.entries.toTypedArray()
        provider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providerOptions.map { if (it == AiProvider.TABIAI) "TabiAI (tabitoken.com)" else it.label }
        )
        provider.setSelection(providerOptions.indexOf(AiProvider.fromId(prefs.getString("provider", null))))
        root.addView(provider, ViewGroup.LayoutParams(-1, -2))

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
        if (prefs.getInt("keyboard_layout_version", 0) >= 6) return
        val old = prefs.getInt("keyboard_height_dp", 350)
        val migrated = if (old == 350) 250 else old.coerceIn(210, 360)
        val oldLandscape = prefs.getInt("keyboard_height_landscape_dp", 205)
        val migratedLandscape = if (oldLandscape >= 175) oldLandscape - 30 else oldLandscape
        prefs.edit()
            .putInt("keyboard_height_portrait_dp", migrated)
            .putInt("keyboard_height_landscape_dp", migratedLandscape.coerceIn(135, 220))
            .putInt("keyboard_layout_version", 6)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityStatus.isInitialized) updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val accessibilityMasterEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').map { it.trim() }.filter { it.isNotBlank() }

        accessibilityStatus.text = if (enabled.isEmpty()) {
            if (accessibilityMasterEnabled) {
                "Sistem masih menandai Aksesibilitas aktif, tetapi nama layanannya tidak terbaca. Buka pengaturan di bawah dan nonaktifkan layanan yang masih menyala."
            } else {
                "✓ Tidak ada layanan Aksesibilitas yang tercatat aktif."
            }
        } else {
            buildString {
                append("Layanan Aksesibilitas yang masih aktif di HP:\n")
                enabled.forEach { flattened ->
                    val component = ComponentName.unflattenFromString(flattened)
                    val packageId = component?.packageName.orEmpty()
                    val label = when {
                        packageId == packageName -> "AI Ads Kyboard (layanan versi lama)"
                        packageId.isBlank() -> flattened
                        else -> runCatching {
                            val app = packageManager.getApplicationInfo(packageId, 0)
                            packageManager.getApplicationLabel(app).toString()
                        }.getOrDefault(packageId)
                    }
                    append("\n• ").append(label)
                }
                append("\n\nMatikan layanan di atas agar aplikasi bank tidak lagi mendeteksinya.")
                if (enabled.any { ComponentName.unflattenFromString(it)?.packageName == packageName }) {
                    append("\nJika layanan versi lama AI Ads Kyboard tidak dapat dimatikan, catat API key lalu hapus aplikasi lama satu kali sebelum memasang v0.7.")
                }
            }
        }
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
}
