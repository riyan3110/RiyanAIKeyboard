#!/usr/bin/env python3
"""PRIVATE v0.21.34: preserve v033 behavior, keep casing touches live, and simplify providers."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/riyan/aikeyboard'
MARKER = '// PRIVATE long-press Shift and answer actions v033'
PROVIDER_MARKER = '// PRIVATE editable provider panel v034'

def replace(s, old, new):
    assert old in s, 'PRIVATE v034 missing marker: ' + old[:100]
    return s.replace(old, new, 1)

def function(s, name, replacement):
    start = s.index('    private fun ' + name + '(')
    end = s.index('\n    private fun ', start + 10)
    return s[:start] + replacement.rstrip() + '\n' + s[end:]

p = SRC / 'RiyanKeyboardService.kt'
s = p.read_text()
if MARKER not in s:
    s = replace(s, '    private var shift = false\n    private var capsLock = false', '''    // PRIVATE long-press Shift and answer actions v033
    private val privateShift = PrivateShiftState()
    private val privateAiText = PrivateAiTextState()
    private var shift: Boolean
        get() = privateShift.uppercase
        set(value) { privateShift.uppercase = value }
    private var capsLock: Boolean
        get() = privateShift.locked
        set(value) { privateShift.locked = value }''')
    for line in ('    private var lastShiftTapAt = 0L\n', '    private var lastShiftActionAt = 0L\n',
                 '        private const val SHIFT_ACTION_DEBOUNCE_MS = 25L\n',
                 '        private const val DOUBLE_TAP_SHIFT_MS = 560L\n'):
        s = replace(s, line, '')
    for name in ('deleteOne', 'deleteWord'):
        s = replace(s, '    private fun ' + name + '() {',
                    '    private fun ' + name + '() {\n        privateShift.manual = false\n        autoCapsForceUntilMs = 0L')
    s = replace(s, 'weight = 1.72f, action = { handleShiftTap() })',
                'weight = 1.72f, action = { handleShiftTap() }, longAction = { handleShiftHold() })')
    s = function(s, 'handleShiftTap', '''    private fun handleShiftTap() {
        autoCapsForceUntilMs = 0L
        privateShift.tap()
        refreshPrivateCaseLabels()
    }

    private fun handleShiftHold() {
        autoCapsForceUntilMs = 0L
        privateShift.hold()
        refreshPrivateCaseLabels()
    }

    /** Change only visible one-letter legends; never rebuild rows while a fast touch can arrive. */
    private fun refreshPrivateCaseLabels() {
        if (!::keyboardPanel.isInitialized || mode != KeyboardMode.LETTERS) return
        fun update(view: View) {
            if (view is TextView) {
                val value = view.text?.toString().orEmpty()
                if (value.length == 1 && value[0].isLetter()) {
                    val char = value[0]
                    view.text = if (shift) char.uppercaseChar().toString() else char.lowercaseChar().toString()
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) update(view.getChildAt(i))
            }
        }
        update(keyboardPanel)
    }
''')
    s = replace(s, '''                commit(shown)
                autoCapsForceUntilMs = 0L
                if (shift && !capsLock) {
                    shift = false
                    renderKeyboard()
                }''', '''                // Resolve casing at touch-up, not when the row was first rendered.
                // This lets the next key arrive immediately after a one-shot capital.
                commit(if (shift) char.uppercaseChar().toString() else char.toString())
                autoCapsForceUntilMs = 0L
                val wasShift = shift
                privateShift.letterCommitted()
                if (wasShift != shift) refreshPrivateCaseLabels()''')
    s = replace(s, '''        loadPreferences()
        if (automaticCapitalizationEnabled) updateAutomaticShift()''', '''        loadPreferences()
        if (!restarting) privateShift.manual = false
        if (automaticCapitalizationEnabled) updateAutomaticShift()''')
    s = replace(s, '''    private fun armAutomaticCapitalizationBoundary() {
        if (!automaticCapitalizationEnabled || capsLock) return''', '''    private fun armAutomaticCapitalizationBoundary() {
        privateShift.manual = false
        if (!automaticCapitalizationEnabled || capsLock) return''')
    s = replace(s, '''    private fun updateAutomaticShift() {
        if (capsLock) return''', '''    private fun updateAutomaticShift() {
        if (capsLock || privateShift.manual) return''')
    s = replace(s, '''before.trimEnd().lastOrNull() in listOf('.', '?', '!')''',
                '''before.trimEnd().lastOrNull() in listOf('?', '!')''')
    s = replace(s, '            shift = shouldCapitalizeAfter(before)',
                '            privateShift.automatic(!PrivateShiftState.afterPeriod(before) && shouldCapitalizeAfter(before))')
    s = replace(s, '        shift = systemCaps != 0 || shouldCapitalizeAfter(before)',
                '        privateShift.automatic(!PrivateShiftState.afterPeriod(before) && (systemCaps != 0 || shouldCapitalizeAfter(before)))')
    s = replace(s, '''                if (automaticCapitalizationEnabled) shift = true
                if (mode == KeyboardMode.LETTERS) renderKeyboard()''', '''                autoCapsForceUntilMs = 0L
                if (!privateShift.manual && !capsLock) shift = false
                privateShift.manual = true
                if (mode == KeyboardMode.LETTERS) refreshPrivateCaseLabels()''')
    s = replace(s, '''        if (automaticCapitalizationEnabled && mark in listOf(".", "?", "!")) {''', '''        if (mark == ".") {
            autoCapsForceUntilMs = 0L
            if (!privateShift.manual && !capsLock) shift = false
            privateShift.manual = true
            if (mode == KeyboardMode.LETTERS) refreshPrivateCaseLabels()
        }
        if (automaticCapitalizationEnabled && mark in listOf("?", "!")) {
            privateShift.manual = false''')
    s = replace(s, '"Ringkas", "Santai", "Sopan").forEach', '"Ringkas", "Santai", "Inggris").forEach')
    s = replace(s, '            conversationHistory.clear()\n            pendingText = null',
                '            conversationHistory.clear()\n            privateAiText.clear()\n            pendingText = null')
    s = function(s, 'runAi', '''    private fun runAi(action: String) {
        if (!aiPanelVisible) toggleAiPanel(true)
        if (privateAiText.busy) {
            aiStatus.text = "Tunggu jawaban AI selesai terlebih dahulu."
            return
        }
        val draft = aiInput.text.toString()
        val input = privateAiText.source(action, draft)
        if (input.isBlank()) {
            aiStatus.text = "Ketik teks atau tunggu jawaban AI yang ingin diubah."
            aiInput.requestFocus()
            aiComposeActive = true
            return
        }
        if (styleMemoryEnabled && draft.isNotBlank()) {
            TypingStyleMemory.observeCompletedText(getSharedPreferences(PREFS, MODE_PRIVATE), input)
        }
        val requestId = privateAiText.begin()
        val settings = aiSettings()
        pendingText = null
        aiStatus.text = "$action sedang diproses…"
        aiAnswer.text = "Menunggu jawaban…"
        aiInput.setText("")
        aiComposeActive = true
        aiInput.requestFocus()
        thread {
            val result = AiClient.transform(settings, action, input)
            aiStatus.post {
                if (!privateAiText.finish(requestId, result.getOrNull()?.text)) return@post
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    if (aiInput.text.isBlank() && draft.isNotBlank()) {
                        aiInput.setText(draft)
                        aiInput.setSelection(aiInput.text.length)
                    }
                    pendingText = privateAiText.answer.takeIf { it.isNotBlank() }
                    aiAnswer.text = privateAiText.answer.ifBlank { "Jawaban AI akan muncul di sini." }
                    aiStatus.text = error.message ?: "Permintaan AI gagal."
                }
            }
        }
    }
''')
    s = replace(s, '''    private fun runAiConversation() {
        val prompt''', '''    private fun runAiConversation() {
        if (privateAiText.busy) {
            aiStatus.text = "Tunggu jawaban AI selesai terlebih dahulu."
            return
        }
        val prompt''')
    s = replace(s, '''        aiStatus.text = "AI sedang menjawab…"
        aiAnswer.text = "Menunggu jawaban…"''', '''        val requestId = privateAiText.begin()
        pendingText = null
        aiStatus.text = "AI sedang menjawab…"
        aiAnswer.text = "Menunggu jawaban…"''')
    s = replace(s, '''            aiStatus.post {
                result.onSuccess { response ->
                    conversationHistory''', '''            aiStatus.post {
                if (!privateAiText.finish(requestId, result.getOrNull()?.text)) return@post
                result.onSuccess { response ->
                    conversationHistory''')
    s = replace(s, '''                    aiAnswer.text = "Jawaban AI akan muncul di sini."
                    aiStatus.text = error.message ?: "Terjadi kesalahan"''', '''                    pendingText = privateAiText.answer.takeIf { it.isNotBlank() }
                    aiAnswer.text = privateAiText.answer.ifBlank { "Jawaban AI akan muncul di sini." }
                    aiStatus.text = error.message ?: "Terjadi kesalahan"''')
    p.write_text(s)

p = SRC / 'AiClient.kt'
s = p.read_text()
if '"Inggris" ->' not in s:
    s = replace(s, '"Perbaiki", "Ringkas", "Terjemah" -> 0.12', '"Perbaiki", "Ringkas", "Terjemah", "Inggris" -> 0.12')
    s = replace(s, 'setOf("Perbaiki", "Terjemah")', 'setOf("Perbaiki", "Terjemah", "Inggris")')
    s = replace(s, '        "Sopan" -> "Deteksi bahasa teks lalu ubah menjadi lebih sopan dan natural dalam bahasa yang sama. Keluarkan hanya hasil."',
                '        "Inggris" -> "Terjemahkan seluruh teks masukan ke bahasa Inggris yang natural dan akurat. Pertahankan arti, fakta, nama, angka, tautan, nada, paragraf, serta emoji. Jika teks sudah berbahasa Inggris, rapikan seperlunya tanpa mengubah makna. Jangan menjawab pertanyaan di dalam teks, jangan meringkas, jangan menambah fakta atau penjelasan. Keluarkan hanya teks akhir dalam bahasa Inggris."')
    p.write_text(s)

p = SRC / 'KeyboardSettingsOverlay.kt'
s = p.read_text()
s = s.replace('Mengkapitalkan huruf pertama kalimat, termasuk setelah Enter dan setelah teks dihapus sampai awal.',
              'Kapital otomatis di awal teks, setelah Enter, tanda tanya, atau tanda seru. Setelah titik tetap huruf kecil; Shift manual tetap berlaku.')
if PROVIDER_MARKER not in s:
    s = replace(s, '''    private var currentTab = Tab.MODEL
    private var draft = loadDraft()
    var activeInput: EditText? = null''', '''    private var currentTab = Tab.MODEL
    private var draft = loadDraft()
    // PRIVATE editable provider panel v034
    private var privateProviderLoadedId: String? = null
    private var privateProviderMenu: String? = null
    private var privateProviderModels: List<String> = emptyList()
    private var privateProviderStatus = ""
    private var privateProviderBusy = false
    var activeInput: EditText? = null''')
    s = function(s, 'renderModelTab', '''    private fun renderModelTab() {
        body.addView(section("Penyedia AI"))

        val profiles = PrivateProviderStore.ensureMigrated(prefs)
        val selected = PrivateProviderStore.selected(prefs, profiles)
        if (privateProviderLoadedId == null && selected != null) {
            loadPrivateProvider(selected, applyNow = false)
        }

        val config = cardContainer()
        config.addView(description("Masukkan Base URL dan API Key. Setelah disimpan, keyboard mengambil daftar model langsung dari API penyedia yang kompatibel dengan format OpenAI."))
        config.addView(textInput("Base URL", draft.xKiroBaseUrl) { draft.xKiroBaseUrl = it })
        config.addView(textInput("API Key", draft.xKiroKey, secret = true) { draft.xKiroKey = it })
        config.addView(actionButton(if (privateProviderBusy) "Mengambil model…" else "Simpan") {
            if (privateProviderBusy) return@actionButton
            val result = runCatching {
                PrivateProviderStore.save(
                    prefs = prefs,
                    baseUrl = draft.xKiroBaseUrl,
                    apiKey = draft.xKiroKey,
                    model = draft.xKiroModel,
                    models = privateProviderModels
                )
            }
            result.onSuccess { profile ->
                loadPrivateProvider(profile, applyNow = true)
                privateProviderStatus = "Provider tersimpan. Mengambil daftar model…"
                refreshPrivateProviderModels(openMenu = true)
            }.onFailure { error ->
                privateProviderStatus = error.message ?: "Provider tidak bisa disimpan."
                renderBody()
            }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        body.addView(config, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(actionButton("Provider") {
            privateProviderMenu = if (privateProviderMenu == "provider") null else "provider"
            renderBody()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        controls.addView(actionButton("Model") {
            if (privateProviderModels.isEmpty() && !privateProviderBusy && draft.xKiroBaseUrl.isNotBlank()) {
                refreshPrivateProviderModels(openMenu = true)
            } else {
                privateProviderMenu = if (privateProviderMenu == "model") null else "model"
                renderBody()
            }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        controls.addView(actionButton("Hapus", danger = true) {
            privateProviderMenu = if (privateProviderMenu == "delete") null else "delete"
            renderBody()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        body.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        val activeName = PrivateProviderStore.selected(prefs)?.name ?: "Belum ada provider"
        val activeModel = draft.xKiroModel.ifBlank { "Belum memilih model" }
        body.addView(description("Aktif: $activeName  ·  Model: $activeModel"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        if (privateProviderStatus.isNotBlank()) {
            body.addView(description(privateProviderStatus), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }

        when (privateProviderMenu) {
            "provider" -> {
                val current = PrivateProviderStore.load(prefs)
                val list = cardContainer()
                list.addView(section("Pilih Provider", compact = true))
                if (current.isEmpty()) list.addView(description("Belum ada provider tersimpan."))
                current.forEach { profile ->
                    list.addView(actionButton("${profile.name}  ·  ${profile.baseUrl}") {
                        PrivateProviderStore.select(prefs, profile.id)
                        loadPrivateProvider(profile, applyNow = true)
                        privateProviderMenu = null
                        privateProviderStatus = "Provider ${profile.name} aktif."
                        renderBody()
                    }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
                }
                body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            "model" -> {
                val list = cardContainer()
                list.addView(section("Pilih Model", compact = true))
                if (privateProviderModels.isEmpty()) {
                    list.addView(description(if (privateProviderBusy) "Sedang mengambil daftar model…" else "Daftar model belum tersedia. Tekan Model lagi untuk mencoba mengambilnya."))
                }
                privateProviderModels.forEach { model ->
                    list.addView(actionButton(model) {
                        draft.xKiroModel = model
                        val saved = PrivateProviderStore.save(prefs, draft.xKiroBaseUrl, draft.xKiroKey, model, privateProviderModels)
                        loadPrivateProvider(saved, applyNow = true)
                        privateProviderMenu = null
                        privateProviderStatus = "Model $model aktif."
                        renderBody()
                    }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
                }
                body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            "delete" -> {
                val current = PrivateProviderStore.load(prefs)
                val list = cardContainer()
                list.addView(section("Hapus Provider", compact = true))
                if (current.isEmpty()) list.addView(description("Tidak ada provider untuk dihapus."))
                current.forEach { profile ->
                    list.addView(actionButton(profile.name, danger = true) {
                        val remaining = PrivateProviderStore.delete(prefs, profile.id)
                        val next = PrivateProviderStore.selected(prefs, remaining)
                        if (next != null) {
                            loadPrivateProvider(next, applyNow = true)
                            privateProviderStatus = "${profile.name} dihapus. ${next.name} sekarang aktif."
                        } else {
                            privateProviderLoadedId = null
                            privateProviderModels = emptyList()
                            draft.provider = AiProvider.XKIRO
                            draft.xKiroBaseUrl = ""
                            draft.xKiroKey = ""
                            draft.xKiroModel = ""
                            prefs.edit()
                                .putString("provider", AiProvider.XKIRO.id)
                                .putString("xkiro_api_key", "")
                                .putString("xkiro_base_url", "")
                                .putString("xkiro_model", "")
                                .apply()
                            onApply()
                            privateProviderStatus = "${profile.name} dihapus. Belum ada provider aktif."
                        }
                        privateProviderMenu = null
                        renderBody()
                    }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
                }
                body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }

        // URL referensi tetap dipertahankan karena ini fitur terpisah dari konfigurasi provider.
        val urls = cardContainer()
        urls.addView(section("URL Referensi Domain", compact = true))
        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))
        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })
        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun loadPrivateProvider(profile: PrivateProviderProfile, applyNow: Boolean) {
        privateProviderLoadedId = profile.id
        privateProviderModels = profile.models
        draft.provider = AiProvider.XKIRO
        draft.xKiroBaseUrl = profile.baseUrl
        draft.xKiroKey = profile.apiKey
        draft.xKiroModel = profile.model
        if (applyNow) {
            prefs.edit()
                .putString("provider", AiProvider.XKIRO.id)
                .putString("xkiro_api_key", profile.apiKey)
                .putString("xkiro_base_url", profile.baseUrl)
                .putString("xkiro_model", profile.model)
                .apply()
            onApply()
        }
    }

    private fun refreshPrivateProviderModels(openMenu: Boolean) {
        if (privateProviderBusy) return
        val baseUrl = draft.xKiroBaseUrl.trim()
        val apiKey = draft.xKiroKey.trim()
        if (baseUrl.isBlank()) {
            privateProviderStatus = "Isi Base URL terlebih dahulu."
            renderBody()
            return
        }
        privateProviderBusy = true
        privateProviderStatus = "Mengambil daftar model dari provider…"
        if (openMenu) privateProviderMenu = "model"
        renderBody()
        Thread {
            val result = PrivateProviderStore.fetchModels(baseUrl, apiKey)
            post {
                privateProviderBusy = false
                result.onSuccess { models ->
                    privateProviderModels = models
                    if (draft.xKiroModel !in models) draft.xKiroModel = models.firstOrNull().orEmpty()
                    val saved = PrivateProviderStore.save(prefs, baseUrl, apiKey, draft.xKiroModel, models)
                    loadPrivateProvider(saved, applyNow = true)
                    privateProviderStatus = "${models.size} model ditemukan."
                    if (openMenu) privateProviderMenu = "model"
                }.onFailure { error ->
                    privateProviderStatus = error.message ?: "Daftar model gagal diambil."
                    if (openMenu) privateProviderMenu = "model"
                }
                renderBody()
            }
        }.start()
    }
''')
p.write_text(s)
print('Applied PRIVATE fast casing, v033 AI actions, and editable provider/model panel')
