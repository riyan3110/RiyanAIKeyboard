package com.riyan.aikeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("riyan_ai", MODE_PRIVATE) }
    private lateinit var contextText: EditText

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val image = InputImage.fromFilePath(this, uri)
        Toast.makeText(this, "Membaca teks gambar…", Toast.LENGTH_SHORT).show()
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener {
                contextText.setText(it.text)
                prefs.edit().putString("shared_context", it.text).apply()
                Toast.makeText(this, "Teks gambar siap dipakai keyboard.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { Toast.makeText(this, it.message ?: "OCR gagal", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Riyan AI Keyboard"; textSize = 27f })
        root.addView(TextView(this).apply {
            text = "Aktifkan keyboard, pilih sebagai keyboard utama, lalu atur OpenRouter atau TabiAI."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
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

        root.addView(sectionTitle("Pengaturan keyboard"))
        root.addView(TextView(this).apply {
            text = "Atur ukuran dan respons tombol. Perubahan dipakai saat keyboard dibuka kembali."
            textSize = 13f
        })
        val keyboardHeight = settingSlider(
            root = root,
            label = "Tinggi keyboard",
            minimum = 280,
            maximum = 430,
            current = prefs.getInt("keyboard_height_dp", 350),
            suffix = " dp"
        )
        val keyTextSize = settingSlider(
            root = root,
            label = "Ukuran huruf tombol",
            minimum = 16,
            maximum = 30,
            current = prefs.getInt("key_text_size_sp", 22),
            suffix = " sp"
        )
        val touchSensitivity = settingSlider(
            root = root,
            label = "Sensitivitas sentuhan",
            minimum = 20,
            maximum = 100,
            current = prefs.getInt("touch_sensitivity", 65),
            suffix = "%"
        )
        val longPressDuration = settingSlider(
            root = root,
            label = "Durasi tekan lama",
            minimum = 200,
            maximum = 900,
            current = prefs.getInt("long_press_ms", 450),
            suffix = " ms"
        )
        root.addView(Button(this).apply {
            text = "Simpan pengaturan keyboard"
            setOnClickListener {
                prefs.edit()
                    .putInt("keyboard_height_dp", keyboardHeight.progress + 280)
                    .putInt("key_text_size_sp", keyTextSize.progress + 16)
                    .putInt("touch_sensitivity", touchSensitivity.progress + 20)
                    .putInt("long_press_ms", longPressDuration.progress + 200)
                    .apply()
                Toast.makeText(
                    this@MainActivity,
                    "Pengaturan tersimpan. Tutup lalu buka keyboard untuk melihat perubahan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

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
        val openRouterKey = secretField(
            "API key OpenRouter",
            prefs.getString("openrouter_api_key", prefs.getString("api_key", ""))
        )
        val openRouterModel = textField(
            "Model OpenRouter",
            prefs.getString("openrouter_model", "openrouter/free")
        )
        root.addView(openRouterKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(openRouterModel, ViewGroup.LayoutParams(-1, -2))

        root.addView(sectionTitle("TabiAI"))
        val tabiKey = secretField("API key TabiAI", prefs.getString("tabi_api_key", ""))
        val tabiBaseUrl = textField(
            "Base URL TabiAI",
            prefs.getString("tabi_base_url", "https://tabitoken.com")
        )
        val tabiModel = textField("Model TabiAI", prefs.getString("tabi_model", "claude-opus-5"))
        root.addView(tabiKey, ViewGroup.LayoutParams(-1, -2))
        root.addView(tabiBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(tabiModel, ViewGroup.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Endpoint Claude: /v1/messages · header: x-api-key"
            textSize = 12f
        })

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
                    .putString(
                        "openrouter_model",
                        openRouterModel.text.toString().trim().ifBlank { "openrouter/free" }
                    )
                    .putString("tabi_api_key", tabiKey.text.toString().trim())
                    .putString(
                        "tabi_base_url",
                        tabiBaseUrl.text.toString().trim().ifBlank { "https://tabitoken.com" }
                    )
                    .putString(
                        "tabi_model",
                        tabiModel.text.toString().trim().ifBlank { "claude-opus-5" }
                    )
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
                prefs.edit().putString("shared_context", contextText.text.toString()).apply()
                Toast.makeText(this@MainActivity, "Konteks tersimpan.", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        setPadding(
            0,
            (18 * resources.displayMetrics.density).toInt(),
            0,
            (6 * resources.displayMetrics.density).toInt()
        )
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
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
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
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValue(progress)
            }

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
}
