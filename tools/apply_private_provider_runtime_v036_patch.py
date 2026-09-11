#!/usr/bin/env python3
"""PRIVATE v0.21.39: dynamic providers are the only runtime source of truth."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/riyan/aikeyboard"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"PRIVATE v036 patch marker not found: {label}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Settings UI: verify a provider before saving it, make fallback immediate,
# stop mirroring dynamic providers into legacy xKiro prefs, and purge old keys.
# ---------------------------------------------------------------------------
overlay_path = SRC / "KeyboardSettingsOverlay.kt"
overlay = overlay_path.read_text(encoding="utf-8")
OVERLAY_MARKER = "// PRIVATE provider source-of-truth v036"
if OVERLAY_MARKER not in overlay:
    overlay = replace_once(
        overlay,
        "    // PRIVATE editable provider panel v034\n",
        "    // PRIVATE editable provider panel v034\n    // PRIVATE provider source-of-truth v036\n",
        "overlay marker",
    )

    old_save = '''        config.addView(actionButton(if (privateProviderBusy) "Mengambil model…" else "Simpan") {
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
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })'''
    new_save = '''        config.addView(actionButton(if (privateProviderBusy) "Memverifikasi…" else "Simpan") {
            if (privateProviderBusy) return@actionButton
            val baseUrl = draft.xKiroBaseUrl.trim()
            val apiKey = draft.xKiroKey.trim()
            if (baseUrl.isBlank()) {
                privateProviderStatus = "Isi Base URL terlebih dahulu."
                renderBody()
                return@actionButton
            }
            privateProviderBusy = true
            privateProviderStatus = "Memverifikasi Base URL, API Key, dan daftar model…"
            renderBody()
            Thread {
                val result = PrivateProviderStore.fetchModels(baseUrl, apiKey)
                post {
                    privateProviderBusy = false
                    result.onSuccess { models ->
                        privateProviderModels = models
                        val selectedModel = draft.xKiroModel.takeIf { it in models }
                            ?: models.firstOrNull().orEmpty()
                        draft.xKiroModel = selectedModel
                        val profile = PrivateProviderStore.save(
                            prefs = prefs,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = selectedModel,
                            models = models
                        )
                        loadPrivateProvider(profile, applyNow = true)
                        privateProviderStatus = "${profile.name} terhubung. ${models.size} model ditemukan."
                        privateProviderMenu = "model"
                    }.onFailure { error ->
                        privateProviderStatus = "Provider tidak disimpan: ${error.message ?: "Base URL/API Key tidak dapat diverifikasi."}"
                    }
                    renderBody()
                }
            }.start()
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })'''
    overlay = replace_once(overlay, old_save, new_save, "verified provider save")

    old_fallback = '''        body.addView(toggleCard(
            "Aktifkan Fallback Penyedia",
            "Otomatis beralih ke provider lain bila provider/model utama gagal.",
            draft.fallbackEnabled
        ) { draft.fallbackEnabled = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
    new_fallback = '''        body.addView(toggleCard(
            "Aktifkan Fallback Penyedia",
            "Otomatis beralih hanya ke provider lain yang masih tersimpan bila provider/model utama gagal.",
            draft.fallbackEnabled
        ) { enabled ->
            draft.fallbackEnabled = enabled
            prefs.edit().putBoolean("fallback_enabled", enabled).commit()
            onApply()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })'''
    overlay = replace_once(overlay, old_fallback, new_fallback, "immediate fallback toggle")

    old_load_apply = '''        if (applyNow) {
            prefs.edit()
                .putString("provider", AiProvider.XKIRO.id)
                .putString("xkiro_api_key", profile.apiKey)
                .putString("xkiro_base_url", profile.baseUrl)
                .putString("xkiro_model", profile.model)
                .apply()
            onApply()
        }'''
    new_load_apply = '''        if (applyNow) {
            // Dynamic profile storage is the only provider source of truth.
            PrivateProviderStore.purgeLegacyProviderState(prefs)
            onApply()
        }'''
    overlay = replace_once(overlay, old_load_apply, new_load_apply, "remove xKiro legacy mirror")

    old_empty_delete = '''                        } else {
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
                        }'''
    new_empty_delete = '''                        } else {
                            privateProviderLoadedId = null
                            privateProviderModels = emptyList()
                            draft.xKiroBaseUrl = ""
                            draft.xKiroKey = ""
                            draft.xKiroModel = ""
                            PrivateProviderStore.purgeLegacyProviderState(prefs)
                            onApply()
                            privateProviderStatus = "${profile.name} dihapus sampai bersih. Belum ada provider aktif."
                        }'''
    overlay = replace_once(overlay, old_empty_delete, new_empty_delete, "root-clean last provider deletion")

    save_start = overlay.index("    private fun saveDraft() {")
    save_end = overlay.index("\n    private fun resetDraft()", save_start)
    save_block = overlay[save_start:save_end]
    save_block_new = save_block.replace(
        "            .apply()\n        onApply()",
        "            .apply()\n        PrivateProviderStore.purgeLegacyProviderState(prefs)\n        onApply()",
        1,
    )
    if save_block_new == save_block:
        raise RuntimeError("PRIVATE v036 patch marker not found: saveDraft purge")
    overlay = overlay[:save_start] + save_block_new + overlay[save_end:]

    overlay_path.write_text(overlay, encoding="utf-8")


# ---------------------------------------------------------------------------
# AiClient: direct OpenAI-compatible vision call for one exact dynamic profile.
# This avoids AiClient.visionProduct's historical all-provider fallback chain.
# ---------------------------------------------------------------------------
ai_path = SRC / "AiClient.kt"
ai = ai_path.read_text(encoding="utf-8")
AI_MARKER = "// PRIVATE exact compatible vision v036"
if AI_MARKER not in ai:
    insert_before = "    private fun execute(\n"
    if insert_before not in ai:
        raise RuntimeError("PRIVATE v036 patch marker not found: AiClient execute")
    helper = '''    // PRIVATE exact compatible vision v036
    internal fun compatibleVision(
        profile: PrivateProviderProfile,
        jpegBase64: String,
        localTextHint: String = ""
    ): Result<String> = runCatching {
        require(jpegBase64.isNotBlank()) { "Gambar kosong." }
        val raw = requestCompatibleVision(
            apiKey = profile.apiKey,
            baseUrl = profile.baseUrl,
            model = profile.model,
            providerLabel = profile.name,
            jpegBase64 = jpegBase64,
            localTextHint = localTextHint
        )
        normalizeVisionResult(raw)?.let {
            VisionSearchEvidence.refineQuery(it, localTextHint)
        } ?: throw IllegalStateException("Model ${profile.name} tidak membuktikan bahwa gambar benar-benar dibaca.")
    }

'''
    ai = ai.replace(insert_before, helper + insert_before, 1)
    ai_path.write_text(ai, encoding="utf-8")


# ---------------------------------------------------------------------------
# Runtime: every AI path uses only profiles that still exist in PrivateProviderStore.
# The selected provider is the sole attempt when fallback is OFF. When ON, only
# the other saved dynamic profiles are eligible. Legacy enum entries are never candidates.
# ---------------------------------------------------------------------------
service_path = SRC / "RiyanKeyboardService.kt"
service = service_path.read_text(encoding="utf-8")
SERVICE_MARKER = "// PRIVATE strict dynamic provider runtime v036"
if SERVICE_MARKER not in service:
    ai_settings_marker = "    private fun aiSettings() = getSharedPreferences(PREFS, MODE_PRIVATE).let { prefs ->\n"
    if ai_settings_marker not in service:
        raise RuntimeError("PRIVATE v036 patch marker not found: aiSettings")
    helpers = '''    // PRIVATE strict dynamic provider runtime v036
    private fun <T> runPrivateProviderRequest(
        request: (PrivateProviderProfile) -> Result<T>
    ): Result<PrivateProviderExecution<T>> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val profiles = PrivateProviderStore.load(prefs)
        val selected = PrivateProviderStore.selected(prefs, profiles)
        val fallbackEnabled = prefs.getBoolean("fallback_enabled", false)
        return PrivateProviderRuntime.execute(
            profiles = profiles,
            selectedId = selected?.id,
            fallbackEnabled = fallbackEnabled,
            request = request
        )
    }

    private fun privateSettingsFor(profile: PrivateProviderProfile): AiSettings =
        aiSettings().copy(
            primaryProvider = AiProvider.XKIRO,
            xKiroApiKey = profile.apiKey,
            xKiroBaseUrl = profile.baseUrl,
            xKiroModel = profile.model,
            fallbackEnabled = false
        )

    private fun runPrivateTransform(action: String, text: String): Result<PrivateProviderAiResponse> =
        runPrivateProviderRequest { profile ->
            AiClient.transform(privateSettingsFor(profile), action, text).map { it.text }
        }.map { PrivateProviderAiResponse(it.value, it.profile) }

    private fun runPrivateChat(prompt: String, context: String, history: String): Result<PrivateProviderAiResponse> =
        runPrivateProviderRequest { profile ->
            AiClient.chat(privateSettingsFor(profile), prompt, context, history).map { it.text }
        }.map { PrivateProviderAiResponse(it.value, it.profile) }

    private fun runPrivateVoiceCorrection(transcript: String): Result<PrivateProviderAiResponse> =
        runPrivateProviderRequest { profile ->
            AiClient.correctVoiceSearch(privateSettingsFor(profile), transcript).map { it.text }
        }.map { PrivateProviderAiResponse(it.value, it.profile) }

    private fun runPrivateVision(jpegBase64: String, localTextHint: String): Result<AiResponse> =
        runPrivateProviderRequest { profile ->
            AiClient.compatibleVision(profile, jpegBase64, localTextHint)
        }.map { AiResponse(it.value, AiProvider.XKIRO) }

'''
    service = service.replace(ai_settings_marker, helpers + ai_settings_marker, 1)

    replacements = [
        ("AiClient.correctVoiceSearch(settings, cleanTranscript).getOrNull()?.text", "runPrivateVoiceCorrection(cleanTranscript).getOrNull()?.text"),
        ("AiClient.visionProduct(aiSettings(), encoded, localHint)", "runPrivateVision(encoded, localHint)"),
        ("AiClient.visionProduct(aiSettings(), encoded, \"\")", "runPrivateVision(encoded, \"\")"),
        ("AiClient.transform(settings, action, input)", "runPrivateTransform(action, input)"),
        ("AiClient.transform(aiSettings(), action, input)", "runPrivateTransform(action, input)"),
        ("AiClient.chat(aiSettings(), prompt, appContext, history)", "runPrivateChat(prompt, appContext, history)"),
    ]
    for old, new in replacements:
        if old in service:
            service = service.replace(old, new)

    # Make fallback success reporting show the provider/model that actually answered,
    # rather than the selected provider when a fallback profile handled the request.
    service = service.replace(
        'aiStatus.text = "Model: ${activeModelName()} · ketuk jawaban atau Pakai"',
        'aiStatus.text = "Model: ${response.profile.model} · ${response.profile.name} · ketuk jawaban atau Pakai"'
    )

    # At least the four user-facing AI routes must have been redirected.
    required = [
        "runPrivateVoiceCorrection(cleanTranscript)",
        "runPrivateVision(encoded, localHint)",
        "runPrivateTransform(action, input)",
        "runPrivateChat(prompt, appContext, history)",
    ]
    missing = [item for item in required if item not in service]
    if missing:
        raise RuntimeError("PRIVATE v036 runtime redirects missing: " + ", ".join(missing))

    service_path.write_text(service, encoding="utf-8")

print("Applied PRIVATE v0.21.39 strict dynamic provider runtime")
