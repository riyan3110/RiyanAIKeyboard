from pathlib import Path
import re

path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
source = path.read_text()

old_state = """    private var voiceGeneration = 0
    private var voiceWorking = false
    private var voiceFallback: Runnable? = null"""
new_state = """    private var voiceGeneration = 0
    private var voiceWorking = false
    private var voiceModeActive = false
    private var voiceSearchRequested = false
    private var voiceReadyQuery = ""
    private var voiceRawTranscript = ""
    private var scannerSearchGeneration = 0
    private var voiceFallback: Runnable? = null"""
if old_state not in source:
    raise SystemExit("voice state anchor not found")
source = source.replace(old_state, new_state, 1)

voice_block = r'''    private fun cancelVoiceSearch() {
        voiceGeneration++
        voiceFallback?.let(handler::removeCallbacks)
        voiceFallback = null
        voiceCapture?.cancel()
        voiceCapture = null
        voiceWorking = false
        voiceModeActive = false
        voiceSearchRequested = false
        voiceReadyQuery = ""
        voiceRawTranscript = ""
        voiceMicButton?.apply {
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Mulai pencarian suara"
        }
    }

    private fun restoreScannerAfterVoiceMode() {
        if (!searchSurfaceVisible || !scannerActive || scannerGalleryUri != null) return
        scannerPreviewView?.post { startEmbeddedScanner() }
    }

    private fun toggleVoiceSearch() {
        if (voiceModeActive) {
            cancelVoiceSearch()
            scannerResultText?.text = scannerSelectedQuery.ifBlank {
                if (scannerGalleryUri != null) "AI Vision akan mengenali gambar dari galeri"
                else "Arahkan objek ke kotak, lalu tekan Cari."
            }
            restoreScannerAfterVoiceMode()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        voiceModeActive = true
        voiceSearchRequested = false
        voiceReadyQuery = ""
        voiceRawTranscript = ""
        scannerSearchGeneration++
        stopEmbeddedScanner(keepRequested = true)
        scannerSearchButton?.text = "Cari"
        scannerSearchButton?.isEnabled = true

        val generation = ++voiceGeneration
        voiceMicButton?.apply {
            imageTintList = ColorStateList.valueOf(Color.rgb(255, 92, 125))
            contentDescription = "Matikan mode mikrofon"
        }
        scannerResultText?.text = "Mendengarkan… tekan Cari untuk mencari suara"
        voiceCapture = PrivateVoiceCapture(this,
            onPartial = { text ->
                if (generation == voiceGeneration && voiceModeActive) scannerResultText?.text = text
            },
            onError = { message ->
                if (generation == voiceGeneration) {
                    cancelVoiceSearch()
                    scannerResultText?.text = message
                    restoreScannerAfterVoiceMode()
                }
            },
            onResult = { transcript ->
                if (generation == voiceGeneration && voiceModeActive) {
                    voiceCapture = null
                    refineVoiceSearch(transcript, generation)
                }
            })
        voiceCapture?.start()
    }

    private fun performVoiceSearchOnly() {
        if (!voiceModeActive) return
        voiceSearchRequested = true

        val ready = voiceReadyQuery.trim()
        if (ready.isNotBlank() && !voiceWorking && voiceCapture == null) {
            openSearchResults(ready)
            return
        }

        voiceCapture?.let { capture ->
            scannerResultText?.text = "Menyelesaikan suara…"
            capture.finish()
            return
        }

        if (voiceWorking) {
            scannerResultText?.text = voiceRawTranscript.ifBlank { "Merapikan suara…" }
            return
        }

        if (voiceRawTranscript.isNotBlank()) {
            openSearchResults(voiceRawTranscript)
            return
        }
        scannerResultText?.text = "Ketuk mic dan bicara dulu"
    }

    private fun completeVoiceQuery(query: String, generation: Int) {
        if (generation != voiceGeneration || !voiceModeActive || !searchSurfaceVisible || !isInputViewShown) return
        voiceFallback?.let(handler::removeCallbacks)
        voiceFallback = null
        voiceReadyQuery = query.trim().ifBlank { voiceRawTranscript.trim() }
        voiceWorking = false
        if (voiceReadyQuery.isBlank()) {
            scannerResultText?.text = "Suara belum terbaca · ketuk mic lalu coba lagi"
            return
        }
        scannerResultText?.text = "Suara siap: $voiceReadyQuery"
        if (voiceSearchRequested) openSearchResults(voiceReadyQuery)
    }

    private fun refineVoiceSearch(transcript: String, generation: Int) {
        val cleanTranscript = transcript.trim()
        voiceWorking = true
        voiceRawTranscript = cleanTranscript
        voiceReadyQuery = cleanTranscript
        voiceMicButton?.contentDescription = "Matikan mode mikrofon"
        scannerResultText?.text = "Merapikan ucapan: $cleanTranscript"
        // Keep only the latest transcription locally; never retain background microphone audio.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("private_last_voice_transcript", cleanTranscript).apply()
        val settings = aiSettings()
        voiceFallback = Runnable { completeVoiceQuery(cleanTranscript, generation) }
            .also { handler.postDelayed(it, 5000) }
        // Do not queue unlimited AI calls when an endpoint is slow.
        if (voiceCorrectionRunning.compareAndSet(false, true)) {
            voiceExecutor.execute {
                val result = try { AiClient.correctVoiceSearch(settings, cleanTranscript).getOrNull()?.text }
                    catch (_: Exception) { null }
                    finally { voiceCorrectionRunning.set(false) }
                handler.post { completeVoiceQuery(PrivateVoiceQuery.choose(cleanTranscript, result), generation) }
            }
        }
    }
    private val voiceCorrectionRunning = java.util.concurrent.atomic.AtomicBoolean(false)
'''
voice_pattern = re.compile(
    r"    private fun cancelVoiceSearch\(\) \{.*?    private val voiceCorrectionRunning = java\.util\.concurrent\.atomic\.AtomicBoolean\(false\)\n",
    re.S,
)
source, replaced = voice_pattern.subn(voice_block, source, count=1)
if replaced != 1:
    raise SystemExit(f"voice block replacement failed: {replaced}")

barcode_anchor = "    private fun publishScannerBarcode(barcode: Barcode) {\n"
if barcode_anchor not in source:
    raise SystemExit("barcode function anchor not found")
source = source.replace(
    barcode_anchor,
    barcode_anchor + "        if (voiceModeActive) return\n",
    1,
)

visual_start = source.index("    private fun publishScannerVisualResult(")
visual_end = source.index("    private fun scannerTargetRect", visual_start)
visual_block = source[visual_start:visual_end]
visual_anchor = "    ) {\n        val target = scannerTargetRect(frameWidth, frameHeight)"
if visual_anchor not in visual_block:
    raise SystemExit("visual-result body anchor not found")
visual_block = visual_block.replace(
    visual_anchor,
    "    ) {\n        if (voiceModeActive) return\n        val target = scannerTargetRect(frameWidth, frameHeight)",
    1,
)
source = source[:visual_start] + visual_block + source[visual_end:]

camera_start = source.index("    private fun performScannerSearch() {")
camera_end = source.index("    private fun performGalleryAiVisionSearch", camera_start)
camera_block = source[camera_start:camera_end]
camera_header = """    private fun performScannerSearch() {
        // Barcode/QR/direct URL remains exact and never needs AI vision."""
if camera_header not in camera_block:
    raise SystemExit("camera Search header anchor not found")
camera_block = camera_block.replace(
    camera_header,
    """    private fun performScannerSearch() {
        if (voiceModeActive) {
            performVoiceSearchOnly()
            return
        }
        val searchGeneration = ++scannerSearchGeneration

        // Barcode/QR/direct URL remains exact and never needs AI vision.""",
    1,
)
if "performGalleryAiVisionSearch(it)" not in camera_block:
    raise SystemExit("gallery call anchor not found")
camera_block = camera_block.replace(
    "performGalleryAiVisionSearch(it)",
    "performGalleryAiVisionSearch(it, searchGeneration)",
    1,
)
preview_anchor = """        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()"""
if preview_anchor not in camera_block:
    raise SystemExit("camera preview anchor not found")
camera_block = camera_block.replace(
    preview_anchor,
    """        preview.post {
            if (searchGeneration != scannerSearchGeneration || voiceModeActive) return@post
            val frame = runCatching { preview.bitmap }.getOrNull()""",
    1,
)
handler_anchor = """                handler.post {
                    val response = result.getOrNull()"""
if handler_anchor not in camera_block:
    raise SystemExit("camera result-handler anchor not found")
camera_block = camera_block.replace(
    handler_anchor,
    """                handler.post {
                    if (searchGeneration != scannerSearchGeneration || voiceModeActive || !searchSurfaceVisible) return@post
                    val response = result.getOrNull()""",
    1,
)
source = source[:camera_start] + camera_block + source[camera_end:]

gallery_start = source.index("    private fun performGalleryAiVisionSearch")
gallery_end = source.index("    private fun scaleBitmapForAiVision", gallery_start)
gallery_block = source[gallery_start:gallery_end]
gallery_header = "    private fun performGalleryAiVisionSearch(uri: Uri) {"
if gallery_header not in gallery_block:
    raise SystemExit("gallery function header anchor not found")
gallery_block = gallery_block.replace(
    gallery_header,
    """    private fun performGalleryAiVisionSearch(uri: Uri, searchGeneration: Int) {
        if (voiceModeActive || searchGeneration != scannerSearchGeneration) return""",
    1,
)
gallery_failure_anchor = """                handler.post {
                    scannerSearchButton?.text = "Cari""""
if gallery_failure_anchor not in gallery_block:
    raise SystemExit("gallery failure-handler anchor not found")
gallery_block = gallery_block.replace(
    gallery_failure_anchor,
    """                handler.post {
                    if (searchGeneration != scannerSearchGeneration || voiceModeActive || !searchSurfaceVisible) return@post
                    scannerSearchButton?.text = "Cari"""",
    1,
)
gallery_result_anchor = """            handler.post {
                if (scannerGalleryUri != uri) return@post"""
if gallery_result_anchor not in gallery_block:
    raise SystemExit("gallery result-handler anchor not found")
gallery_block = gallery_block.replace(
    gallery_result_anchor,
    """            handler.post {
                if (searchGeneration != scannerSearchGeneration || voiceModeActive || !searchSurfaceVisible) return@post
                if (scannerGalleryUri != uri) return@post""",
    1,
)
source = source[:gallery_start] + gallery_block + source[gallery_end:]

# Sanity checks: Search must have exactly one mode switch and both camera/gallery late-result guards.
checks = [
    "private var voiceModeActive = false",
    "private fun performVoiceSearchOnly()",
    "if (voiceModeActive) {\n            performVoiceSearchOnly()",
    "performGalleryAiVisionSearch(it, searchGeneration)",
    "if (searchGeneration != scannerSearchGeneration || voiceModeActive || !searchSurfaceVisible) return@post",
]
for check in checks:
    if check not in source:
        raise SystemExit(f"sanity check missing: {check}")

path.write_text(source)
Path("app/build-version.txt").write_text("0.21.31\n")
Path("app/build-code.txt").write_text("61\n")
print("PRIVATE mic Search isolation patch applied successfully")
