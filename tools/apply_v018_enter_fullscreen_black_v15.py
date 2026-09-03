from pathlib import Path

path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = path.read_text(encoding='utf-8')

# 1) AI conversation background: make the large conversation surface true black.
s = s.replace(
    'background = roundedBackground(Color.rgb(31, 31, 36), 9f)',
    'background = roundedBackground(Color.BLACK, 9f)',
    1,
)
s = s.replace(
    'background = roundedBackground(Color.rgb(38, 38, 43), 10f)',
    'background = roundedBackground(Color.BLACK, 10f)',
    1,
)
# Keep the composer visually separated, but near-black instead of gray.
s = s.replace(
    'background = roundedBackground(Color.rgb(45, 45, 51), 15f)',
    'background = roundedBackground(Color.rgb(14, 14, 14), 15f)',
    1,
)

# 2) Fullscreen AI must keep exactly the same keyboard-panel height as normal mode.
# baseKeyboardHeightDp already includes the utility bar in normal mode; brandBar is added
# outside it, so subtracting brandBar again compressed the keys in fullscreen.
old_fullscreen = '''    private fun fullscreenKeyboardPanelHeightDp(): Int =
        (baseKeyboardHeightDp - utilityHeightDp() - toolbarKeyboardGapDp() - brandBarHeightDp())
            .coerceAtLeast(if (isLandscape()) 72 else 105)'''
new_fullscreen = '''    private fun fullscreenKeyboardPanelHeightDp(): Int =
        (baseKeyboardHeightDp - utilityHeightDp() - toolbarKeyboardGapDp())
            .coerceAtLeast(if (isLandscape()) 72 else 105)'''
if old_fullscreen not in s:
    raise RuntimeError('fullscreenKeyboardPanelHeightDp marker not found')
s = s.replace(old_fullscreen, new_fullscreen, 1)

# 3) Multiline editors must retain a real Enter/new-line key even when an app incorrectly
# advertises IME_ACTION_DONE/GO/etc. This is what happens in some notes/editing apps.
marker = '    private fun enterKeyLabel(): String {'
if marker not in s:
    raise RuntimeError('enterKeyLabel marker not found')
helper = '''    private fun editorSupportsNewLine(): Boolean {
        if (activeInternalInput() != null) return false
        val info = currentInputEditorInfo ?: return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        val multiline = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val imeMultiline = info.inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        return multiline || imeMultiline || noEnterAction
    }

'''
s = s.replace(marker, helper + marker, 1)

old_label = '''    private fun enterKeyLabel(): String {
        if (searchComposeActive) return "🔍"
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        return when (action) {'''
new_label = '''    private fun enterKeyLabel(): String {
        if (searchComposeActive) return "🔍"
        if (aiComposeActive) return "↑"
        if (editorSupportsNewLine()) return "↵"
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        return when (action) {'''
if old_label not in s:
    raise RuntimeError('enterKeyLabel body marker not found')
s = s.replace(old_label, new_label, 1)

old_press = '''        learnCurrentBoundary(completed = true)
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\\n", 1)
        }'''
new_press = '''        learnCurrentBoundary(completed = true)
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (editorSupportsNewLine()) {
            val ic = currentInputConnection
            val committed = runCatching { ic?.commitText("\\n", 1) == true }.getOrDefault(false)
            if (!committed && ic != null) {
                val now = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
            }
        } else if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\\n", 1)
        }'''
if old_press not in s:
    raise RuntimeError('pressEnter action marker not found')
s = s.replace(old_press, new_press, 1)

path.write_text(s, encoding='utf-8')
print('Applied v0.18 v15: multiline Enter, stable fullscreen keyboard height, black AI chat.')
