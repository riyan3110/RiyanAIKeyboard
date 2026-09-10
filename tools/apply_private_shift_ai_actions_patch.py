#!/usr/bin/env python3
"""PRIVATE v0.21.33: run after legacy generators so requested behavior survives builds."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/riyan/aikeyboard'
MARKER = '// PRIVATE long-press Shift and answer actions v033'

def replace(s, old, new):
    assert old in s, 'PRIVATE v033 missing marker: ' + old[:100]
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
    # Touch listener already delivers exactly one tap OR hold. No double-tap timer is needed.
    for line in ('    private var lastShiftTapAt = 0L\n', '    private var lastShiftActionAt = 0L\n',
                 '        private const val SHIFT_ACTION_DEBOUNCE_MS = 25L\n',
                 '        private const val DOUBLE_TAP_SHIFT_MS = 560L\n'):
        s = replace(s, line, '')
    # A deliberate edit releases the stale-letter guard, preserving existing auto-caps
    # after deleting back to the beginning. A later manual Shift still wins callbacks.
    for name in ('deleteOne', 'deleteWord'):
        s = replace(s, '    private fun ' + name + '() {',
                    '    private fun ' + name + '() {\n        privateShift.manual = false\n        autoCapsForceUntilMs = 0L')
    s = replace(s, 'weight = 1.72f, action = { handleShiftTap() })',
                'weight = 1.72f, action = { handleShiftTap() }, longAction = { handleShiftHold() })')
    s = function(s, 'handleShiftTap', '''    private fun handleShiftTap() {
        autoCapsForceUntilMs = 0L
        privateShift.tap()
        renderKeyboard()
    }

    private fun handleShiftHold() {
        autoCapsForceUntilMs = 0L
        privateShift.hold()
        renderKeyboard()
    }
''')
    s = replace(s, '''                autoCapsForceUntilMs = 0L
                if (shift && !capsLock) {
                    shift = false
                    renderKeyboard()
                }''', '''                autoCapsForceUntilMs = 0L
                val wasShift = shift
                privateShift.letterCommitted()
                if (wasShift != shift) renderKeyboard()''')
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
    # Some hosts still report CAP_SENTENCES after a period, so do not trust that flag there.
    s = replace(s, '        shift = systemCaps != 0 || shouldCapitalizeAfter(before)',
                '        privateShift.automatic(!PrivateShiftState.afterPeriod(before) && (systemCaps != 0 || shouldCapitalizeAfter(before)))')
    s = replace(s, '''                if (automaticCapitalizationEnabled) shift = true
                if (mode == KeyboardMode.LETTERS) renderKeyboard()''', '''                autoCapsForceUntilMs = 0L
                if (!privateShift.manual && !capsLock) shift = false
                privateShift.manual = true
                if (mode == KeyboardMode.LETTERS) renderKeyboard()''')
    s = replace(s, '''        if (automaticCapitalizationEnabled && mark in listOf(".", "?", "!")) {''', '''        if (mark == ".") {
            autoCapsForceUntilMs = 0L
            if (!privateShift.manual && !capsLock) shift = false
            privateShift.manual = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
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
        // Consume the source draft; the next action can use the newly produced answer.
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
s = p.read_text().replace('Mengkapitalkan huruf pertama kalimat, termasuk setelah Enter dan setelah teks dihapus sampai awal.',
                         'Kapital otomatis di awal teks, setelah Enter, tanda tanya, atau tanda seru. Setelah titik tetap huruf kecil; Shift manual tetap berlaku.')
p.write_text(s)
print('Applied PRIVATE long-press Shift, stable manual casing, answer actions and English translation')
