#!/usr/bin/env python3
"""PRIVATE 0.21.34 final provider UI pass. Runs after all legacy provider generators."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/riyan/aikeyboard"

# The in-keyboard settings panel is created by the v034 Shift/provider patch. Make a
# Model tap refresh from the currently entered provider every time so migrated/cached
# profiles never show an incomplete old list.
overlay_path = SRC / "KeyboardSettingsOverlay.kt"
overlay = overlay_path.read_text(encoding="utf-8")
old_model_button = '''        controls.addView(actionButton("Model") {
            if (privateProviderModels.isEmpty() && !privateProviderBusy && draft.xKiroBaseUrl.isNotBlank()) {
                refreshPrivateProviderModels(openMenu = true)
            } else {
                privateProviderMenu = if (privateProviderMenu == "model") null else "model"
                renderBody()
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })'''
new_model_button = '''        controls.addView(actionButton("Model") {
            if (!privateProviderBusy && draft.xKiroBaseUrl.isNotBlank()) {
                refreshPrivateProviderModels(openMenu = true)
            } else {
                privateProviderMenu = "model"
                renderBody()
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })'''
if old_model_button in overlay:
    overlay = overlay.replace(old_model_button, new_model_button, 1)
elif new_model_button not in overlay:
    raise RuntimeError("PRIVATE model refresh button marker not found")

overlay_path.write_text(overlay, encoding="utf-8")

# MainActivity still receives the old hard-coded provider list from compatibility
# patches. Replace only that provider section after those patches have finished.
main_path = SRC / "MainActivity.kt"
main = main_path.read_text(encoding="utf-8")
marker = "// PRIVATE simple provider activity v034"
if marker not in main:
    start_marker = '        root.addView(sectionTitle("Provider AI utama"))\n'
    end_marker = '        root.addView(sectionTitle("Konteks dari gambar atau teks"))\n'
    start = main.index(start_marker)
    end = main.index(end_marker, start)
    replacement = r'''        // PRIVATE simple provider activity v034
        root.addView(sectionTitle("Penyedia AI"))
        root.addView(description("Masukkan Base URL dan API Key. Model akan diambil langsung dari API provider setelah disimpan atau saat tombol Model ditekan."))

        val migratedProfiles = PrivateProviderStore.ensureMigrated(prefs)
        var activeProfile = PrivateProviderStore.selected(prefs, migratedProfiles)
        var availableModels = activeProfile?.models.orEmpty()

        val providerBaseUrl = textField("Base URL", activeProfile?.baseUrl.orEmpty())
        val providerApiKey = secretField("API Key", activeProfile?.apiKey.orEmpty())
        root.addView(providerBaseUrl, ViewGroup.LayoutParams(-1, -2))
        root.addView(providerApiKey, ViewGroup.LayoutParams(-1, -2))

        val providerStatus = TextView(this).apply {
            text = if (activeProfile != null) {
                "Aktif: ${activeProfile!!.name} · Model: ${activeProfile!!.model.ifBlank { "belum dipilih" }}"
            } else {
                "Belum ada provider tersimpan."
            }
            textSize = 13f
            setPadding(0, dp(7), 0, dp(7))
        }
        root.addView(providerStatus, ViewGroup.LayoutParams(-1, -2))

        val providerChoiceBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(providerChoiceBox, ViewGroup.LayoutParams(-1, -2))

        fun activatePrivateProfile(profile: PrivateProviderProfile) {
            activeProfile = profile
            availableModels = profile.models
            PrivateProviderStore.select(prefs, profile.id)
            prefs.edit()
                .putString("provider", AiProvider.XKIRO.id)
                .putString("xkiro_api_key", profile.apiKey)
                .putString("xkiro_base_url", profile.baseUrl)
                .putString("xkiro_model", profile.model)
                .apply()
            providerBaseUrl.setText(profile.baseUrl)
            providerApiKey.setText(profile.apiKey)
            providerStatus.text = "Aktif: ${profile.name} · Model: ${profile.model.ifBlank { "belum dipilih" }}"
        }

        fun fetchPrivateModels(openList: Boolean) {
            val baseUrl = providerBaseUrl.text.toString().trim()
            val apiKey = providerApiKey.text.toString().trim()
            if (baseUrl.isBlank()) {
                providerStatus.text = "Isi Base URL terlebih dahulu."
                return
            }
            providerStatus.text = "Mengambil daftar model…"
            Thread {
                val result = PrivateProviderStore.fetchModels(baseUrl, apiKey)
                runOnUiThread {
                    result.onSuccess { models ->
                        availableModels = models
                        val currentModel = activeProfile?.takeIf {
                            PrivateProviderStore.normalizeBaseUrl(it.baseUrl) == PrivateProviderStore.normalizeBaseUrl(baseUrl)
                        }?.model.orEmpty()
                        val selectedModel = currentModel.takeIf { it in models } ?: models.firstOrNull().orEmpty()
                        val saved = PrivateProviderStore.save(prefs, baseUrl, apiKey, selectedModel, models)
                        activatePrivateProfile(saved)
                        providerStatus.text = "${models.size} model ditemukan · ${saved.name}"
                        if (openList) {
                            providerChoiceBox.removeAllViews()
                            models.forEach { model ->
                                providerChoiceBox.addView(Button(this).apply {
                                    text = model
                                    isAllCaps = false
                                    setOnClickListener {
                                        val chosen = PrivateProviderStore.save(
                                            prefs,
                                            providerBaseUrl.text.toString(),
                                            providerApiKey.text.toString(),
                                            model,
                                            availableModels
                                        )
                                        activatePrivateProfile(chosen)
                                        providerChoiceBox.removeAllViews()
                                        Toast.makeText(this@MainActivity, "Model $model dipilih.", Toast.LENGTH_SHORT).show()
                                    }
                                }, ViewGroup.LayoutParams(-1, -2))
                            }
                        }
                    }.onFailure { error ->
                        providerStatus.text = error.message ?: "Daftar model gagal diambil."
                        if (openList && availableModels.isNotEmpty()) {
                            providerChoiceBox.removeAllViews()
                            availableModels.forEach { model ->
                                providerChoiceBox.addView(Button(this).apply {
                                    text = model
                                    isAllCaps = false
                                    setOnClickListener {
                                        val chosen = PrivateProviderStore.save(
                                            prefs,
                                            providerBaseUrl.text.toString(),
                                            providerApiKey.text.toString(),
                                            model,
                                            availableModels
                                        )
                                        activatePrivateProfile(chosen)
                                        providerChoiceBox.removeAllViews()
                                    }
                                }, ViewGroup.LayoutParams(-1, -2))
                            }
                        }
                    }
                }
            }.start()
        }

        root.addView(Button(this).apply {
            text = "Simpan"
            isAllCaps = false
            setOnClickListener {
                val result = runCatching {
                    PrivateProviderStore.save(
                        prefs,
                        providerBaseUrl.text.toString(),
                        providerApiKey.text.toString(),
                        activeProfile?.model.orEmpty(),
                        availableModels
                    )
                }
                result.onSuccess {
                    activatePrivateProfile(it)
                    providerChoiceBox.removeAllViews()
                    fetchPrivateModels(openList = true)
                }.onFailure {
                    providerStatus.text = it.message ?: "Provider tidak bisa disimpan."
                }
            }
        }, ViewGroup.LayoutParams(-1, -2))

        val providerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        providerActions.addView(Button(this).apply {
            text = "Provider"
            isAllCaps = false
            setOnClickListener {
                providerChoiceBox.removeAllViews()
                val profiles = PrivateProviderStore.load(prefs)
                if (profiles.isEmpty()) {
                    providerStatus.text = "Belum ada provider tersimpan."
                }
                profiles.forEach { profile ->
                    providerChoiceBox.addView(Button(this@MainActivity).apply {
                        text = "${profile.name} · ${profile.baseUrl}"
                        isAllCaps = false
                        setOnClickListener {
                            activatePrivateProfile(profile)
                            providerChoiceBox.removeAllViews()
                        }
                    }, ViewGroup.LayoutParams(-1, -2))
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        providerActions.addView(Button(this).apply {
            text = "Model"
            isAllCaps = false
            setOnClickListener { fetchPrivateModels(openList = true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        providerActions.addView(Button(this).apply {
            text = "Hapus"
            isAllCaps = false
            setOnClickListener {
                providerChoiceBox.removeAllViews()
                val profiles = PrivateProviderStore.load(prefs)
                if (profiles.isEmpty()) {
                    providerStatus.text = "Tidak ada provider untuk dihapus."
                }
                profiles.forEach { profile ->
                    providerChoiceBox.addView(Button(this@MainActivity).apply {
                        text = profile.name
                        isAllCaps = false
                        setOnClickListener {
                            val remaining = PrivateProviderStore.delete(prefs, profile.id)
                            val next = PrivateProviderStore.selected(prefs, remaining)
                            providerChoiceBox.removeAllViews()
                            if (next != null) {
                                activatePrivateProfile(next)
                                providerStatus.text = "${profile.name} dihapus · ${next.name} sekarang aktif."
                            } else {
                                activeProfile = null
                                availableModels = emptyList()
                                providerBaseUrl.setText("")
                                providerApiKey.setText("")
                                prefs.edit()
                                    .putString("provider", AiProvider.XKIRO.id)
                                    .putString("xkiro_api_key", "")
                                    .putString("xkiro_base_url", "")
                                    .putString("xkiro_model", "")
                                    .apply()
                                providerStatus.text = "${profile.name} dihapus · belum ada provider aktif."
                            }
                        }
                    }, ViewGroup.LayoutParams(-1, -2))
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(providerActions, ViewGroup.LayoutParams(-1, -2))

        root.addView(sectionTitle("Sumber URL untuk pencarian AI"))
        root.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. Gunakan {query} pada URL pencarian."))
        val referenceUrls = EditText(this).apply {
            hint = "https://sumber.com/search?q={query}"
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("reference_urls", ""))
        }
        root.addView(referenceUrls, ViewGroup.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Simpan URL Referensi"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putString("reference_urls", referenceUrls.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "URL referensi tersimpan.", Toast.LENGTH_SHORT).show()
            }
        }, ViewGroup.LayoutParams(-1, -2))

'''
    main = main[:start] + replacement + main[end:]
    main_path.write_text(main, encoding="utf-8")

print("Applied PRIVATE standalone provider UI and fresh model-list behavior")
