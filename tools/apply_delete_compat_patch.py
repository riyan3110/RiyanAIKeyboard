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
     * Some browser/WebView/OTP fields need a real DEL event. Trust the event result and never
     * immediately follow it with deleteSurroundingText: many editors apply the key event
     * asynchronously, and doing both can delete two characters for a single tap.
     */
    private fun deletePreviousCharacterCompat(ic: InputConnection): Boolean {
        if (sendDeleteKeyEvent(ic)) return true
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

    /**
     * When instant response is enabled, the normal letter is committed on ACTION_DOWN before a
     * long-press can fire. Replacing it with the alternate symbol must delete exactly that one
     * freshly committed character. Do not use the general backspace compatibility path here,
     * because a hardware DEL event can race with commitText and remove the previous character too.
     */
    private fun deleteInstantCommittedCharacter(): Boolean {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            return when {
                start != end -> {
                    editable.delete(minOf(start, end), maxOf(start, end))
                    true
                }
                start > 0 -> {
                    editable.delete(start - 1, start)
                    true
                }
                else -> false
            }
        }
        val ic = currentInputConnection ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }.getOrDefault(false)
    }

    private fun deleteSelectedText'''

pattern = re.compile(
    r"    private fun deleteOne\(\) \{.*?\n    private fun deleteSelectedText",
    re.S,
)
new_text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("Could not locate deleteOne/deleteWord block")

old_long_press = "if (actionTriggered && spec.alternate != null) deleteOne()"
new_long_press = "if (actionTriggered && spec.alternate != null) deleteInstantCommittedCharacter()"
if old_long_press not in new_text and new_long_press not in new_text:
    raise SystemExit("Could not locate instant long-press replacement")
new_text = new_text.replace(old_long_press, new_long_press, 1)

path.write_text(new_text, encoding="utf-8")
print("Applied single-fire backspace handling and safe long-press symbol replacement")
