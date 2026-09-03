from pathlib import Path
import re

keyboard_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
main_path = Path('app/src/main/java/com/riyan/aikeyboard/MainActivity.kt')
manifest_path = Path('app/src/main/AndroidManifest.xml')

k = keyboard_path.read_text(encoding='utf-8')
m = main_path.read_text(encoding='utf-8')
manifest = manifest_path.read_text(encoding='utf-8')

# -----------------------------------------------------------------------------
# 1) Remove AccessibilityService from the app manifest completely.
#    The source file may remain for compatibility, but Android will no longer expose/start it.
# -----------------------------------------------------------------------------
service_pattern = re.compile(
    r'\n\s*<service\s+\n\s*android:name="\.ScreenTextAccessibilityService".*?</service>',
    re.DOTALL,
)
manifest, removed = service_pattern.subn('', manifest, count=1)
if removed == 0:
    # Also handle a compact service declaration if formatting changes later.
    service_pattern2 = re.compile(
        r'\n\s*<service[^>]*android:name="\.ScreenTextAccessibilityService".*?</service>',
        re.DOTALL,
    )
    manifest, removed = service_pattern2.subn('', manifest, count=1)
if removed == 0 and '.ScreenTextAccessibilityService' in manifest:
    raise RuntimeError('AccessibilityService manifest block could not be removed')

# -----------------------------------------------------------------------------
# 2) Remove accessibility controls/status from Settings UI.
# -----------------------------------------------------------------------------
access_start = m.find('        root.addView(sectionTitle("Akses teks layar"))')
access_end = m.find('        root.addView(sectionTitle("Tema keyboard"))', access_start)
if access_start >= 0 and access_end > access_start:
    m = m[:access_start] + access_end * 0 * '' + m[access_end:]

m = m.replace('import android.content.ComponentName\n', '')
m = m.replace('    private lateinit var accessibilityStatus: TextView\n', '')

# Remove the now-unused onResume/updateAccessibilityStatus block if present.
resume_start = m.find('    override fun onResume() {')
if resume_start >= 0:
    next_after_status = m.find('\n    private fun ', resume_start + 10)
    # If the first private fun is updateAccessibilityStatus, remove through the following private fun marker.
    if next_after_status >= 0 and 'updateAccessibilityStatus' in m[resume_start:next_after_status + 80]:
        status_start = m.find('    private fun updateAccessibilityStatus()', resume_start)
        if status_start >= 0:
            status_end = m.find('\n    private fun ', status_start + 20)
            if status_end > status_start:
                m = m[:resume_start] + m[status_end:]

# Remove any stale accessibility-specific descriptions that survived formatting differences.
m = m.replace(
    '            text = "Versi 0.18.0 · Kamera dan web di panel atas, pemindaian lengkap, pembacaan layar, dan mode coding."',
    '            text = "Versi 0.18.0 · Kamera dan web di panel atas, AI manual, dan mode coding."'
)
old_auto = '        root.addView(sectionTitle("Balasan dan bahasa otomatis"))\n        root.addView(description("Dengan Akses Teks Layar aktif, Balas memprioritaskan pesan masuk terakhir yang belum dibalas. Terjemah selalu menghasilkan Bahasa Indonesia dari bahasa apa pun. Tombol UI dan draf pengguna disaring; teks pilihan, Bagikan, dan clipboard tetap menjadi cadangan."))\n'
new_auto = '        root.addView(sectionTitle("AI dari teks manual"))\n        root.addView(description("Tempel atau ketik teks langsung di kotak Obrolan AI. Perbaiki, Balas, Terjemah, Ringkas, Santai, dan Sopan hanya memproses teks di kotak AI dan tidak membaca layar aplikasi."))\n'
if old_auto in m:
    m = m.replace(old_auto, new_auto, 1)

# -----------------------------------------------------------------------------
# 3) Quick actions use ONLY aiInput text. No screen, editor, selected text, clipboard, or shared context.
# -----------------------------------------------------------------------------
run_start = k.find('    private fun runAi(action: String) {')
run_end = k.find('\n    private fun runAiConversation()', run_start)
if run_start < 0 or run_end < 0:
    raise RuntimeError('runAi function markers not found')
new_run_ai = '''    private fun runAi(action: String) {
        if (!aiPanelVisible) toggleAiPanel(true)

        val input = aiInput.text.toString().trim()
        if (input.isBlank()) {
            aiStatus.text = "Tempel atau ketik teks di kotak AI terlebih dahulu."
            aiAnswer.text = "Jawaban AI akan muncul di sini."
            aiInput.requestFocus()
            aiComposeActive = true
            return
        }

        aiComposeActive = false
        aiInput.clearFocus()
        if (styleMemoryEnabled) {
            TypingStyleMemory.observeCompletedText(getSharedPreferences(PREFS, MODE_PRIVATE), input)
        }
        aiStatus.text = "$action sedang diproses dari teks yang kamu masukkan…"
        aiAnswer.text = "Menunggu jawaban…"

        thread {
            val result = AiClient.transform(aiSettings(), action, input)
            aiStatus.post {
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiAnswer.text = ""
                    aiStatus.text = error.message ?: "Permintaan AI gagal."
                }
            }
        }
    }
'''
k = k[:run_start] + new_run_ai + k[run_end:]

# Conversation mode may still be used for normal AI chat, but it must never read app/screen context.
k = k.replace('        val appContext = conversationContext(ic)\n', '        val appContext = ""\n')

# No hidden fallback to screen context from the generic context helper.
context_start = k.find('    private fun context(ic: InputConnection): String {')
context_end = k.find('\n    private fun automaticReplyContext(', context_start)
if context_start >= 0 and context_end > context_start:
    k = k[:context_start] + '''    private fun context(ic: InputConnection): String {
        return aiInput.text.toString().trim()
    }
''' + k[context_end:]

# Explicitly make these legacy automatic helpers manual-only as a safety net if another button calls them.
reply_start = k.find('    private fun automaticReplyContext(ic: InputConnection): String {')
reply_end = k.find('\n    /**', reply_start)
if reply_start >= 0 and reply_end > reply_start:
    k = k[:reply_start] + '''    private fun automaticReplyContext(ic: InputConnection): String {
        return aiInput.text.toString().trim()
    }
''' + k[reply_end:]

translation_start = k.find('    private fun automaticTranslationContext(ic: InputConnection): String {')
if translation_start >= 0:
    translation_end = k.find('\n    private fun ', translation_start + 20)
    if translation_end > translation_start:
        k = k[:translation_start] + '''    private fun automaticTranslationContext(ic: InputConnection): String {
        return aiInput.text.toString().trim()
    }
''' + k[translation_end:]

# Status text in the AI composer should explain the new behavior.
k = k.replace('"Jawaban via ${activeProviderLabel()} · ketuk jawaban atau Pakai"', '"${activeProviderLabel()} · AI hanya membaca teks di kotak ini"')

keyboard_path.write_text(k, encoding='utf-8')
main_path.write_text(m, encoding='utf-8')
manifest_path.write_text(manifest, encoding='utf-8')
print('Applied v0.18 v19: AccessibilityService removed; AI quick actions are manual-input only.')
