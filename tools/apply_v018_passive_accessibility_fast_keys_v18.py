from pathlib import Path

service_path = Path('app/src/main/java/com/riyan/aikeyboard/ScreenTextAccessibilityService.kt')
xml_path = Path('app/src/main/res/xml/screen_accessibility_service.xml')
keyboard_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')

s = service_path.read_text(encoding='utf-8')
x = xml_path.read_text(encoding='utf-8')
k = keyboard_path.read_text(encoding='utf-8')

# -----------------------------------------------------------------------------
# 1) Accessibility must be passive while the user is typing.
#    All expensive work is already available through captureNow(), which AI actions call on demand.
# -----------------------------------------------------------------------------
start = s.find('    override fun onAccessibilityEvent(event: AccessibilityEvent?) {')
end = s.find('\n\n    override fun onInterrupt()', start)
if start < 0 or end < 0:
    raise RuntimeError('onAccessibilityEvent block not found')
s = s[:start] + '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately passive. Never traverse the app tree, take screenshots, run OCR,
        // or mutate caches from background accessibility events while the user is typing.
        // AI actions call captureNow() explicitly when screen context is actually needed.
    }''' + s[end:]

# Subscribe only to window-state changes. The handler above intentionally does no work; keeping
# one lightweight event type preserves a normal connected AccessibilityService without event storms.
x = x.replace(
    'android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled"',
    'android:accessibilityEventTypes="typeWindowStateChanged"'
)
x = x.replace('android:notificationTimeout="500"', 'android:notificationTimeout="1000"')

# -----------------------------------------------------------------------------
# 2) On-demand capture must describe what is visible NOW, not stale background cache fragments.
# -----------------------------------------------------------------------------
s = s.replace(
    '        val cachedEntries = recentEntriesFor(packageId)\n',
    '        val cachedEntries = emptyList<VisibleText>()\n',
    1
)

old_latest = '''        val latestIncoming = latestIncomingText(
            packageId = packageId,
            currentEntries = currentEntries,
            fallbackEntries = cachedEntries,
            primaryText = primaryText
        )
        val structuredText = buildString {
            if (latestIncoming.isNotBlank()) {
                append("[PESAN ATAU POSTINGAN UTAMA]\\n")
                append(latestIncoming)
            }
            if (primaryText.isNotBlank() && !equivalentText(primaryText, latestIncoming)) {
                if (isNotEmpty()) append("\\n\\n")
                append("[KONTEKS TEKS LAYAR LENGKAP]\\n")
                append(primaryText)
            }'''
new_latest = '''        val rawLatestIncoming = latestIncomingText(
            packageId = packageId,
            currentEntries = currentEntries,
            fallbackEntries = cachedEntries,
            primaryText = primaryText
        )
        // Long posts/articles are commonly split into several OCR/accessibility blocks. In that
        // situation the old "latest incoming" heuristic incorrectly selected only the last paragraph.
        // Use the complete visible post instead. Short chat bubbles still keep latest-message priority.
        val substantialBlocks = currentEntries.count {
            !it.editable && isMeaningfulContent(it.normalized) && it.normalized.length >= 80
        }
        val mainScreenText = if (primaryText.length >= 260 && substantialBlocks >= 2) {
            primaryText
        } else {
            rawLatestIncoming.ifBlank { primaryText }
        }
        val structuredText = buildString {
            if (mainScreenText.isNotBlank()) {
                append("[POSTINGAN ATAU PESAN UTAMA SAAT INI]\\n")
                append(mainScreenText)
            }
            if (primaryText.isNotBlank() && !equivalentText(primaryText, mainScreenText)) {
                if (isNotEmpty()) append("\\n\\n")
                append("[KONTEKS TEKS LAYAR LENGKAP]\\n")
                append(primaryText)
            }'''
if old_latest not in s:
    raise RuntimeError('latestIncoming capture block not found')
s = s.replace(old_latest, new_latest, 1)

s = s.replace(
    '            latestIncomingText = latestIncoming\n',
    '            latestIncomingText = mainScreenText\n',
    1
)

# Do not keep new automatic snapshots after on-demand capture. Current capture itself is authoritative.
s = s.replace('        rememberSnapshot(packageId, currentEntries)\n', '', 1)

# -----------------------------------------------------------------------------
# 3) Remove key popups and expensive per-tap drawable recreation.
# -----------------------------------------------------------------------------
preview_start = k.find('    private fun showKeyPreview(anchor: View, spec: KeySpec) {')
preview_end = k.find('    private fun updateKeyPreview(label: String) {', preview_start)
if preview_start < 0 or preview_end < 0:
    raise RuntimeError('key preview function markers not found')
k = k[:preview_start] + '''    private fun showKeyPreview(anchor: View, spec: KeySpec) {
        // v18: popup huruf/angka/simbol dinonaktifkan untuk respons tombol maksimal.
    }

''' + k[preview_end:]

# Long-press alternate preview also stays hidden.
update_start = k.find('    private fun updateKeyPreview(label: String) {')
update_end = k.find('\n    private fun ', update_start + 10)
if update_start >= 0 and update_end > update_start:
    k = k[:update_start] + '''    private fun updateKeyPreview(label: String) {
        // No popup preview in v18.
    }
''' + k[update_end:]

# Creating a new GradientDrawable on ACTION_DOWN/ACTION_UP is surprisingly costly on rapid taps.
k = k.replace('                    keyFace.background = referenceBubbleKeyBackground(pressed = true)\n', '')
k = k.replace('                    keyFace.background = referenceBubbleKeyBackground(pressed = false)\n', '')
k = k.replace('                        keyFace.background = referenceBubbleKeyBackground(pressed = false)\n', '')

# Predictions wait for an actual pause instead of competing with the IME while typing quickly.
k = k.replace('handler.postDelayed(suggestionRefreshRunnable, 260L)', 'handler.postDelayed(suggestionRefreshRunnable, 450L)')

service_path.write_text(s, encoding='utf-8')
xml_path.write_text(x, encoding='utf-8')
keyboard_path.write_text(k, encoding='utf-8')
print('Applied v0.18 v18: passive accessibility, current full-post capture, no key popups, lower tap overhead.')
