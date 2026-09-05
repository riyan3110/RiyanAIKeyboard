from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
CORE_PATCH = ROOT / "tools/apply_bluesminds_provider_patch_core.py"
KEYBOARD_SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"

# Preserve the existing BluesMinds patch exactly, then apply only the requested
# keyboard visual/touch fixes on top of the generated service source.
runpy.run_path(str(CORE_PATCH), run_name="__main__")

text = KEYBOARD_SERVICE.read_text()


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    if old not in source:
        raise RuntimeError(f"Keyboard visual patch marker not found: {label}")
    return source.replace(old, new, 1)


def patch_key_view(source: str) -> str:
    start_marker = "    private fun keyView(spec: KeySpec): View {"
    end_marker = "\n    private fun keyFeedback(view: View, longPress: Boolean) {"
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: keyView block")

    block = source[start:end]

    # Keep the previous optical centering correction for descender letters.
    old_label = '            if (spec.alternate != null) translationY = dpFloat(4f)'
    new_label = '''            if (spec.label in setOf("q", "y", "p", "g", "j")) {
                translationY = dpFloat(-2f)
            } else if (spec.alternate != null && spec.label.none { it.isLetterOrDigit() }) translationY = dpFloat(4f)'''
    block = replace_once(block, old_label, new_label, "descender centering")

    # Keep the proven working touch path on the actual key frame. The border is
    # part of this frame, so pressing anywhere on the visible face/border uses
    # the same listener and action as the legend area.
    block = replace_once(
        block,
        '''                    actionTriggered = false
                    if (instantKeyResponse) {''',
        '''                    actionTriggered = false
                    keyFace.background = referenceBubbleKeyBackground(pressed = true)
                    if (instantKeyResponse) {''',
        "pressed key border feedback",
    )
    block = replace_once(
        block,
        '''                    dismissKeyPreview()
                    val moved = hypot(event.x - downX, event.y - downY)''',
        '''                    dismissKeyPreview()
                    keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    val moved = hypot(event.x - downX, event.y - downY)''',
        "release key border feedback",
    )
    block = replace_once(
        block,
        '''                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    dismissKeyPreview()
                    true''',
        '''                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    dismissKeyPreview()
                    keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    true''',
        "cancel key border feedback",
    )

    return source[:start] + block + source[end:]


def patch_neon_key_border(source: str) -> str:
    start_marker = "    private fun referenceBubbleKeyBackground(pressed: Boolean): GradientDrawable {"
    end_marker = "\n    private fun referenceTopRimBackground(): GradientDrawable ="
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: reference key background")

    block = source[start:end]
    old_stroke = '''            setStroke(
                dp(1),
                if (pressed) Color.rgb(67, 65, 77) else Color.rgb(97, 94, 108)
            )'''
    new_stroke = '''            setStroke(
                dp(if (pressed) 3 else 2),
                if (pressed) Color.rgb(205, 124, 255) else Color.rgb(181, 86, 249)
            )'''
    block = replace_once(block, old_stroke, new_stroke, "reference neon purple border")
    return source[:start] + block + source[end:]


def patch_cursor_keys(source: str) -> str:
    # Cursor-arrow buttons keep their original proven touch listener directly on
    # the visible frame, while using the same thinner neon purple border.
    start_marker = "    private fun cursorDirectionButton(label: String, keyCode: Int): View {"
    end_marker = "\n    private fun cursorPadTouchListener(): View.OnTouchListener {"
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: cursorDirectionButton")

    block = source[start:end]
    block = replace_once(
        block,
        "            background = roundedBackground(specialKeyBg, 11f)\n",
        "            background = roundedStrokedBackground(specialKeyBg, 11f, Color.rgb(181, 86, 249), 2)\n",
        "cursor normal purple border",
    )
    block = replace_once(
        block,
        "                    view.background = roundedBackground(pressedKeyBg, 11f)\n",
        "                    view.background = roundedStrokedBackground(pressedKeyBg, 11f, Color.rgb(205, 124, 255), 3)\n",
        "cursor pressed border",
    )
    block = replace_once(
        block,
        "                    view.background = roundedBackground(specialKeyBg, 11f)\n",
        "                    view.background = roundedStrokedBackground(specialKeyBg, 11f, Color.rgb(181, 86, 249), 2)\n",
        "cursor released border",
    )
    source = source[:start] + block + source[end:]

    # Touchpad/d-pad container borders use the same thinner reference purple.
    old_cursor_stroke = "        setStroke(dp(2), if (pressed) Color.rgb(214, 133, 255) else Color.rgb(127, 86, 180))"
    new_cursor_stroke = "        setStroke(dp(if (pressed) 3 else 2), if (pressed) Color.rgb(205, 124, 255) else Color.rgb(181, 86, 249))"
    source = replace_once(source, old_cursor_stroke, new_cursor_stroke, "cursor pad neon border")
    return source


text = patch_key_view(text)
text = patch_neon_key_border(text)
text = patch_cursor_keys(text)
KEYBOARD_SERVICE.write_text(text)

print("Keyboard thinner neon border + restored direct touch patch applied")
