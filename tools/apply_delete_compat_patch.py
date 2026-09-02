from pathlib import Path
import re

path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = path.read_text(encoding="utf-8")

if "import android.view.KeyEvent\n" not in text:
    text = text.replace("import android.view.HapticFeedbackConstants\n", "import android.view.HapticFeedbackConstants\nimport android.view.KeyEvent\n", 1)

replacement = r'''    private fun deleteOne() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            when {
                start != end -> editable.delete(minOf(start, end), maxOf(start, end))
                start > 0 -> editable.delete(start - 1, start)
            }
        } else {
            val ic = currentInputConnection ?: return
            if (!deleteSelectedText(ic)) deletePreviousCharacterCompat(ic)
        }
        refreshSuggestionsSoon()
    }

    private fun deleteWord() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            if (start != end) {
                editable.delete(minOf(start, end), maxOf(start, end))
            } else if (start > 0) {
                val before = editable.substring(0, start)
                val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
                editable.delete((start - count).coerceAtLeast(0), start)
            }
            refreshSuggestionsSoon()
            return
        }

        val ic = currentInputConnection ?: return
        if (deleteSelectedText(ic)) {
            refreshSuggestionsSoon()
            return
        }
        val before = runCatching { ic.getTextBeforeCursor(100, 0)?.toString().orEmpty() }.getOrDefault("")
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }
        refreshSuggestionsSoon()
    }

    /**
     * Some browser/WebView/OTP fields ignore deleteSurroundingText even though normal editors do not.
     * Send a real DEL key event first, then fall back to InputConnection deletion when the editor
     * rejects the key event or when readable cursor text proves that nothing changed.
     */
    private fun deletePreviousCharacterCompat(ic: InputConnection): Boolean {
        val before = runCatching { ic.getTextBeforeCursor(24, 0)?.toString().orEmpty() }.getOrDefault("")
        val keyAccepted = sendDeleteKeyEvent(ic)
        val afterKey = runCatching { ic.getTextBeforeCursor(24, 0)?.toString().orEmpty() }.getOrDefault("")
        val keyVerified = before.isNotEmpty() && afterKey != before
        if (keyAccepted && (before.isEmpty() || keyVerified)) return true

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }.getOrDefault(false)
    }

    private fun sendDeleteKeyEvent(ic: InputConnection): Boolean = runCatching {
        val now = SystemClock.uptimeMillis()
        val down = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
        val up = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        down || up
    }.getOrDefault(false)

    private fun deleteSelectedText'''

pattern = re.compile(
    r"    private fun deleteOne\(\) \{.*?\n    private fun deleteSelectedText",
    re.S,
)
new_text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("Could not locate deleteOne/deleteWord block")

path.write_text(new_text, encoding="utf-8")
print("Applied compatible delete/backspace handling")
