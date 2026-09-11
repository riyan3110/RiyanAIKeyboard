#!/usr/bin/env python3
# PUBLIC parity with PRIVATE UI/input/provider/settings while preserving PublicAiPolicy + Google search.
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/riyan/aikeyboard"
SERVICE = SRC / "RiyanKeyboardService.kt"
OVERLAY = SRC / "KeyboardSettingsOverlay.kt"
AI = SRC / "AiClient.kt"


def replace_once(text: str, old: str, new: str, label: str, required: bool = True) -> str:
    if new in text:
        return text
    if old not in text:
        if required:
            raise RuntimeError(f"PUBLIC parity marker missing: {label}")
        return text
    return text.replace(old, new, 1)


def replace_function(text: str, name: str, replacement: str) -> str:
    marker = f"    private fun {name}("
    start = text.find(marker)
    if start < 0:
        raise RuntimeError(f"PUBLIC parity function missing: {name}")
    end = text.find("\n    private fun ", start + len(marker))
    if end < 0:
        raise RuntimeError(f"PUBLIC parity function end missing: {name}")
    return text[:start] + replacement.rstrip() + "\n" + text[end:]


# Keyboard service parity. Do not touch search engine functions or AI policy.
s = SERVICE.read_text(encoding="utf-8")

if "private val privateAiText = PrivateAiTextState()" not in s:
    s = replace_once(
        s,
        "    private val conversationHistory = mutableListOf<Pair<String, String>>()",
        "    private val conversationHistory = mutableListOf<Pair<String, String>>()\n"
        "    private val privateAiText = PrivateAiTextState()",
        "last AI answer state",
    )

shift_old = 'third += KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.72f, action = { handleShiftTap() })'
shift_new = 'third += KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.72f, action = { handleShiftTap() }, longAction = { handleShiftHold() })'
s = replace_once(s, shift_old, shift_new, "Shift long press")

s = replace_function(
    s,
    "letterSpec",
    r'''    private fun letterSpec(char: Char, alternate: String): KeySpec {
        val shown = if (shift) char.uppercaseChar().toString() else char.toString()
        return KeySpec(
            label = shown,
            alternate = if (longPressSymbolsEnabled) alternate else null,
            action = {
                commit(if (shift) char.uppercaseChar().toString() else char.toString())
                autoCapsForceUntilMs = 0L
                if (shift && !capsLock) {
                    shift = false
                    publicManualShiftUntil = SystemClock.uptimeMillis() + 600L
                    refreshPublicCaseLabels()
                }
            },
            longAction = if (longPressSymbolsEnabled) ({ commit(alternate) }) else null
        )
    }''',
)

s = replace_function(
    s,
    "handleShiftTap",
    r'''    private fun handleShiftTap() {
        handler.removeCallbacks(publicAutoShiftRefresh)
        autoCapsForceUntilMs = 0L
        publicManualShiftUntil = SystemClock.uptimeMillis() + 600L
        if (capsLock) {
            capsLock = false
            shift = false
        } else {
            shift = !shift
        }
        lastShiftTapAt = 0L
        refreshPublicCaseLabels()
    }

    private fun handleShiftHold() {
        handler.removeCallbacks(publicAutoShiftRefresh)
        autoCapsForceUntilMs = 0L
        capsLock = true
        shift = true
        lastShiftTapAt = 0L
        publicManualShiftUntil = SystemClock.uptimeMillis() + 600L
        refreshPublicCaseLabels()
    }''',
)

s = replace_function(
    s,
    "updateAutomaticShift",
    r'''    private fun updateAutomaticShift() {
        if (capsLock) return
        if (SystemClock.uptimeMillis() < autoCapsForceUntilMs) {
            shift = true
            return
        }

        val internal = activeInternalInput()
        if (internal != null) {
            val cursor = internal.selectionStart.coerceIn(0, internal.text.length)
            val before = internal.text.subSequence(0, cursor).toString()
            shift = before.trimEnd().lastOrNull() != '.' && shouldCapitalizeAfter(before)
            return
        }

        val ic = currentInputConnection
        val before = runCatching { ic?.getTextBeforeCursor(160, 0)?.toString().orEmpty() }.getOrDefault("")
        val systemCaps = runCatching {
            ic?.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) ?: 0
        }.getOrDefault(0)
        shift = before.trimEnd().lastOrNull() != '.' && (systemCaps != 0 || shouldCapitalizeAfter(before))
    }''',
)
s = s.replace(
    "before.isBlank() || before.endsWith(\"\\n\") || before.trimEnd().lastOrNull() in listOf('.', '?', '!')",
    "before.isBlank() || before.endsWith(\"\\n\") || before.trimEnd().lastOrNull() in listOf('?', '!')",
)
s = replace_function(
    s,
    "commitPunctuation",
    r'''    private fun commitPunctuation(mark: String) {
        learnCurrentBoundary(completed = mark in listOf(".", "?", "!"), terminalMark = mark)
        commit(if (activeInternalInput() == null && punctuationSpaceEnabled) "$mark " else mark)
        if (mark == ".") {
            autoCapsForceUntilMs = 0L
            shift = false
            publicManualShiftUntil = SystemClock.uptimeMillis() + 600L
            if (mode == KeyboardMode.LETTERS) refreshPublicCaseLabels()
        } else if (automaticCapitalizationEnabled && mark in listOf("?", "!")) {
            shift = true
            if (mode == KeyboardMode.LETTERS) refreshPublicCaseLabels()
        }
    }''',
)

s = s.replace(
    '''                if (automaticCapitalizationEnabled) shift = true
                if (mode == KeyboardMode.LETTERS) refreshPublicCaseLabels()
                return''',
    '''                autoCapsForceUntilMs = 0L
                shift = false
                publicManualShiftUntil = SystemClock.uptimeMillis() + 600L
                if (mode == KeyboardMode.LETTERS) refreshPublicCaseLabels()
                return''',
    1,
)

s = s.replace(
    'listOf("Perbaiki", "Balas", "Terjemah", "Ringkas", "Santai", "Sopan").forEach',
    'listOf("Perbaiki", "Balas", "Terjemah", "Ringkas", "Santai", "Inggris").forEach',
)
s = s.replace(
    "            conversationHistory.clear()\n            pendingText = null",
    "            conversationHistory.clear()\n            privateAiText.clear()\n            pendingText = null",
    1,
)

ai_settings_start = s.find("    private fun aiSettings() =")
if ai_settings_start < 0:
    raise RuntimeError("PUBLIC parity function missing: aiSettings")
ai_settings_end = s.find("\n    private fun ", ai_settings_start + 10)
if ai_settings_end < 0:
    raise RuntimeError("PUBLIC parity function end missing: aiSettings")
old_ai_settings = s[ai_settings_start:ai_settings_end]
if "PrivateProviderStore.selected" not in old_ai_settings:
    new_ai_settings = old_ai_settings.replace(
        "    private fun aiSettings() = getSharedPreferences(PREFS, MODE_PRIVATE).let { prefs ->\n"
        "        AiSettings(",
        "    private fun aiSettings() = getSharedPreferences(PREFS, MODE_PRIVATE).let { prefs ->\n"
        "        val privateProfile = PrivateProviderStore.selected(prefs, PrivateProviderStore.load(prefs))\n"
        "        AiSettings(",
        1,
    )
    new_ai_settings = new_ai_settings.replace(
        'primaryProvider = AiProvider.fromId(prefs.getString("provider", null)),',
        'primaryProvider = if (privateProfile != null) AiProvider.XKIRO else AiProvider.fromId(prefs.getString("provider", null)),',
        1,
    )
    new_ai_settings = new_ai_settings.replace(
        'xKiroApiKey = prefs.getString("xkiro_api_key", "").orEmpty(),',
        'xKiroApiKey = privateProfile?.apiKey ?: prefs.getString("xkiro_api_key", "").orEmpty(),',
        1,
    ).replace(
        'xKiroBaseUrl = prefs.getString("xkiro_base_url", "https://api.xkiro.com/v1").orEmpty(),',
        'xKiroBaseUrl = privateProfile?.baseUrl ?: prefs.getString("xkiro_base_url", "https://api.xkiro.com/v1").orEmpty(),',
        1,
    ).replace(
        'xKiroModel = prefs.getString("xkiro_model", "openai/gpt-5.6-sol").orEmpty(),',
        'xKiroModel = privateProfile?.model ?: prefs.getString("xkiro_model", "openai/gpt-5.6-sol").orEmpty(),',
        1,
    )
    s = s[:ai_settings_start] + new_ai_settings + s[ai_settings_end:]

s = replace_function(
    s,
    "activeProviderLabel",
    r'''    private fun activeModelName(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        PrivateProviderStore.selected(prefs, PrivateProviderStore.load(prefs))
            ?.model
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return when (prefs.getString("provider", null).orEmpty()) {
            "openrouter" -> prefs.getString("openrouter_model", "openrouter/free").orEmpty()
            "tabiai" -> prefs.getString("tabi_model", "").orEmpty()
            "9router" -> prefs.getString("9router_model", "").orEmpty()
            "bluesminds" -> prefs.getString("bluesminds_model", "").orEmpty()
            "bai" -> prefs.getString("bai_model", "").orEmpty()
            "xkiro" -> prefs.getString("xkiro_model", "").orEmpty()
            "orcarouter" -> prefs.getString("orcarouter_model", "").orEmpty()
            "aihorde" -> prefs.getString("aihorde_model", "").orEmpty().substringBefore(',')
            else -> prefs.getString("xkiro_model", "").orEmpty()
        }.trim().ifBlank { "Belum memilih model" }
    }

    private fun activeProviderLabel(): String = "Model: ${activeModelName()}"''',
)

s = replace_function(
    s,
    "runAi",
    r'''    private fun runAi(action: String) {
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
        pendingText = null
        aiStatus.text = "$action sedang diproses…"
        aiAnswer.text = "Menunggu jawaban…"
        aiInput.setText("")
        aiComposeActive = true
        aiInput.requestFocus()

        thread {
            val result = AiClient.transform(aiSettings(), action, input)
            aiStatus.post {
                if (!privateAiText.finish(requestId, result.getOrNull()?.text)) return@post
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Model: ${activeModelName()} · ketuk jawaban atau Pakai"
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
    }''',
)
s = replace_function(
    s,
    "runAiConversation",
    r'''    private fun runAiConversation() {
        if (privateAiText.busy) {
            aiStatus.text = "Tunggu jawaban AI selesai terlebih dahulu."
            return
        }
        val prompt = aiInput.text.toString().trim()
        if (prompt.isBlank()) {
            aiStatus.text = "Tulis pesan untuk AI terlebih dahulu."
            aiInput.requestFocus()
            aiComposeActive = true
            return
        }
        if (styleMemoryEnabled) {
            TypingStyleMemory.observeCompletedText(getSharedPreferences(PREFS, MODE_PRIVATE), prompt)
        }
        val history = conversationHistory.takeLast(4).joinToString("\n") { (role, text) -> "$role: $text" }
        val requestId = privateAiText.begin()
        pendingText = null
        aiStatus.text = "AI sedang menjawab…"
        aiAnswer.text = "Menunggu jawaban…"
        aiInput.setText("")
        aiComposeActive = false
        aiInput.clearFocus()

        thread {
            // Keep PUBLIC chat policy/persona intact; this still calls the same AiClient.chat.
            val result = AiClient.chat(aiSettings(), prompt, "", history)
            aiStatus.post {
                if (!privateAiText.finish(requestId, result.getOrNull()?.text)) return@post
                result.onSuccess { response ->
                    conversationHistory += "Pengguna" to prompt
                    conversationHistory += "AI" to response.text
                    while (conversationHistory.size > 8) conversationHistory.removeAt(0)
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Model: ${activeModelName()} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    pendingText = privateAiText.answer.takeIf { it.isNotBlank() }
                    aiAnswer.text = privateAiText.answer.ifBlank { "Jawaban AI akan muncul di sini." }
                    aiStatus.text = error.message ?: "Terjadi kesalahan"
                }
            }
        }
    }''',
)

s = s.replace(
    '''                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
                renderKeyboard()''',
    '''                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
                if (::aiStatus.isInitialized) aiStatus.text = activeProviderLabel()
                renderKeyboard()''',
    1,
)

SERVICE.write_text(s, encoding="utf-8")

# Settings UI parity.
k = OVERLAY.read_text(encoding="utf-8")

if "import android.provider.Settings" not in k:
    k = replace_once(k, "import android.graphics.drawable.GradientDrawable\n",
                     "import android.graphics.drawable.GradientDrawable\nimport android.provider.Settings\n", "Settings import")
if "import android.view.inputmethod.InputMethodManager" not in k:
    k = replace_once(k, "import android.view.ViewGroup\n",
                     "import android.view.ViewGroup\nimport android.view.inputmethod.InputMethodManager\n", "InputMethodManager import")

if "private var privateProviderLoadedId" not in k:
    k = replace_once(
        k,
        "    private var draft = loadDraft()\n    var activeInput: EditText? = null",
        '''    private var draft = loadDraft()
    private var privateProviderLoadedId: String? = null
    private var privateProviderMenu: String? = null
    private var privateProviderModels: List<String> = emptyList()
    private var privateProviderStatus = ""
    private var privateProviderBusy = false
    var activeInput: EditText? = null''',
        "provider UI state",
    )

k = k.replace("enum class Tab { MODEL, MEMORY, THEME, TYPING }", "enum class Tab { THEME, TYPING, MEMORY, MODEL }", 1)
k = k.replace("private var currentTab = Tab.MODEL", "private var currentTab = Tab.THEME", 1)
k = replace_once(k, '''        val tabs = listOf(
            Tab.MODEL to "🤖 Model AI",
            Tab.MEMORY to "✍ Memori Gaya",
            Tab.THEME to "🎨 Tema & Tampilan",
            Tab.TYPING to "⌨ Ketikan & Saran"
        )''', '''        val tabs = listOf(
            Tab.THEME to "🎨 Tema & Tampilan",
            Tab.TYPING to "⌨ Ketikan & Saran",
            Tab.MEMORY to "✍ Memori Gaya",
            Tab.MODEL to "🤖 Model AI"
        )''', "tab order")

k = replace_once(k, '''    fun show(tab: Tab = currentTab) {
        currentTab = tab
        visibility = View.VISIBLE
        renderTabs()
        renderBody()
    }''', '''    fun show(tab: Tab = currentTab) {
        draft = loadDraft()
        privateProviderLoadedId = null
        privateProviderMenu = null
        privateProviderModels = emptyList()
        privateProviderStatus = ""
        privateProviderBusy = false
        currentTab = tab
        visibility = View.VISIBLE
        renderTabs()
        renderBody()
    }''', "reload settings on show")

if "var touchSensitivity: Int" not in k:
    k = replace_once(k, "        var keyBoxScale: Int,\n        var longPressMs: Int,",
                     "        var keyBoxScale: Int,\n        var touchSensitivity: Int,\n        var longPressMs: Int,", "touch sensitivity field")

k = replace_function(
    k,
    "renderModelTab",
    r'''    private fun renderModelTab() {
        body.addView(section("Penyedia AI"))

        val profiles = PrivateProviderStore.ensureMigrated(prefs)
        val selected = PrivateProviderStore.selected(prefs, profiles)
        if (privateProviderLoadedId == null && selected != null) {
            loadPrivateProvider(selected, applyNow = false)
        }

        val config = cardContainer()
        config.addView(textInput("Base URL", draft.xKiroBaseUrl) { draft.xKiroBaseUrl = it })
        config.addView(textInput("API Key", draft.xKiroKey, secret = true) { draft.xKiroKey = it })
        config.addView(actionButton(if (privateProviderBusy) "Mengambil model…" else "Simpan") {
            if (privateProviderBusy) return@actionButton
            runCatching {
                PrivateProviderStore.save(
                    prefs = prefs,
                    baseUrl = draft.xKiroBaseUrl,
                    apiKey = draft.xKiroKey,
                    model = draft.xKiroModel,
                    models = privateProviderModels
                )
            }.onSuccess { profile ->
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
        body.addView(description("Aktif: $activeName  ·  Model: $activeModel"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        if (privateProviderStatus.isNotBlank()) {
            body.addView(description(privateProviderStatus),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }

        when (privateProviderMenu) {
            "provider" -> {
                val list = cardContainer()
                list.addView(section("Pilih Provider", compact = true))
                val current = PrivateProviderStore.load(prefs)
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
                    list.addView(description(if (privateProviderBusy) "Sedang mengambil daftar model…" else "Daftar model belum tersedia."))
                }
                privateProviderModels.forEach { model ->
                    list.addView(actionButton(model) {
                        draft.xKiroModel = model
                        val saved = PrivateProviderStore.save(
                            prefs, draft.xKiroBaseUrl, draft.xKiroKey, model, privateProviderModels
                        )
                        loadPrivateProvider(saved, applyNow = true)
                        privateProviderMenu = null
                        privateProviderStatus = "Model $model aktif."
                        renderBody()
                    }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
                }
                body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            "delete" -> {
                val list = cardContainer()
                list.addView(section("Hapus Provider", compact = true))
                val current = PrivateProviderStore.load(prefs)
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

        body.addView(toggleCard(
            "Aktifkan Fallback Penyedia",
            "Otomatis beralih ke provider lain bila provider/model utama gagal.",
            draft.fallbackEnabled
        ) { draft.fallbackEnabled = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
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
    }''',
)

memory_end = '''        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }'''
memory_new = '''        body.addView(phraseCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val urls = cardContainer()
        urls.addView(section("URL Referensi Domain", compact = true))
        urls.addView(description("Masukkan hingga 6 URL HTTPS, satu per baris. {query} boleh dipakai untuk URL pencarian."))
        urls.addView(textInput("https://sumber.com/search?q={query}", draft.referenceUrls, multiline = true) { draft.referenceUrls = it })
        body.addView(urls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }'''
k = replace_once(k, memory_end, memory_new, "URL references in memory")

if 'sliderRow("Sensitivitas dan Kecepatan Tombol"' not in k:
    k = replace_once(k, '''        keyCard.addView(sliderRow("Skala Kotak Tombol (Maks. 150%)", 65, 150, draft.keyBoxScale, "%") { draft.keyBoxScale = it })
        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })''',
                     '''        keyCard.addView(sliderRow("Skala Kotak Tombol (Maks. 150%)", 65, 150, draft.keyBoxScale, "%") { draft.keyBoxScale = it })
        keyCard.addView(sliderRow("Sensitivitas dan Kecepatan Tombol", 20, 400, draft.touchSensitivity, "%") { draft.touchSensitivity = it })
        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })''', "sensitivity in theme")

k = replace_once(k, '''        body.addView(toggleCard("Tampilkan Baris Angka (1–0)", "Menyematkan baris angka di atas QWERTY.", draft.numberRow) { draft.numberRow = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun renderTypingTab() {''', '''        body.addView(toggleCard("Tampilkan Baris Angka (1–0)", "Menyematkan baris angka di atas QWERTY.", draft.numberRow) { draft.numberRow = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val backupCard = cardContainer()
        backupCard.addView(section("Backup & Pulihkan Pengaturan", compact = true))
        val backupButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backupButtons.addView(actionButton("Buat Backup") {
            val intent = Intent(context, SettingsBackupTransferActivity::class.java)
                .putExtra(SettingsBackupTransferActivity.EXTRA_MODE, SettingsBackupTransferActivity.MODE_EXPORT)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            hidePanel()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        backupButtons.addView(actionButton("Pulihkan Backup") {
            val intent = Intent(context, SettingsBackupTransferActivity::class.java)
                .putExtra(SettingsBackupTransferActivity.EXTRA_MODE, SettingsBackupTransferActivity.MODE_IMPORT)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            hidePanel()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        backupCard.addView(backupButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        body.addView(backupCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun renderTypingTab() {''', "backup in theme")

if 'section("Akses Keyboard", compact = true)' not in k:
    k = replace_once(k, "    private fun renderTypingTab() {\n", '''    private fun renderTypingTab() {
        val keyboardAccess = cardContainer()
        keyboardAccess.addView(section("Akses Keyboard", compact = true))
        keyboardAccess.addView(actionButton("Aktifkan Keyboard") {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        keyboardAccess.addView(actionButton("Pilih Keyboard") {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        body.addView(keyboardAccess, LinearLayout.LayoutParams(-1, -2))

''', "keyboard access in typing")

if '.putInt("touch_sensitivity", draft.touchSensitivity)' not in k:
    k = replace_once(k, '            .putInt("key_box_scale_percent", draft.keyBoxScale)\n            .putInt("long_press_ms", draft.longPressMs)',
                     '            .putInt("key_box_scale_percent", draft.keyBoxScale)\n            .putInt("touch_sensitivity", draft.touchSensitivity)\n            .putInt("long_press_ms", draft.longPressMs)', "save touch sensitivity")
if 'touchSensitivity = prefs.getInt("touch_sensitivity", 100)' not in k:
    k = replace_once(k, '        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),\n        longPressMs = prefs.getInt("long_press_ms", 450),',
                     '        keyBoxScale = prefs.getInt("key_box_scale_percent", 100),\n        touchSensitivity = prefs.getInt("touch_sensitivity", 100),\n        longPressMs = prefs.getInt("long_press_ms", 450),', "load touch sensitivity")
if "        touchSensitivity = 100," not in k:
    k = replace_once(k, "        keyBoxScale = 100,\n        longPressMs = 450,",
                     "        keyBoxScale = 100,\n        touchSensitivity = 100,\n        longPressMs = 450,", "default touch sensitivity")

OVERLAY.write_text(k, encoding="utf-8")

# Add only the English quick action. PublicAiPolicy.CHAT and .VISION stay untouched.
a = AI.read_text(encoding="utf-8")
a = a.replace('"Perbaiki", "Ringkas", "Terjemah" -> 0.12', '"Perbaiki", "Ringkas", "Terjemah", "Inggris" -> 0.12', 1)
a = a.replace('if (action in setOf("Perbaiki", "Terjemah")) 8192 else 4096',
              'if (action in setOf("Perbaiki", "Terjemah", "Inggris")) 8192 else 4096', 1)
if '"Inggris" ->' not in a:
    sopan = '        "Sopan" -> "Deteksi bahasa teks lalu ubah menjadi lebih sopan dan natural dalam bahasa yang sama. Keluarkan hanya hasil."'
    english = '        "Inggris" -> "Terjemahkan seluruh teks masukan ke bahasa Inggris yang natural dan akurat. Pertahankan arti, fakta, nama, angka, tautan, nada, paragraf, serta emoji. Jika teks sudah berbahasa Inggris, rapikan seperlunya tanpa mengubah makna. Jangan menjawab pertanyaan di dalam teks, jangan meringkas, jangan menambah fakta atau penjelasan. Keluarkan hanya teks akhir dalam bahasa Inggris."'
    if sopan not in a:
        raise RuntimeError("PUBLIC parity marker missing: English action instruction")
    a = a.replace(sopan, english, 1)

if "PublicAiPolicy.CHAT" not in a or "PublicAiPolicy.VISION" not in a:
    raise RuntimeError("PUBLIC AI policy missing after parity patch; refusing to continue.")
AI.write_text(a, encoding="utf-8")

print("Applied PUBLIC feature parity while preserving PublicAiPolicy and existing Google/browser routing.")
