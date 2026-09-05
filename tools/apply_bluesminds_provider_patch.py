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

    # Separate the visible scaled keycap from its touch target. The outer wrapper
    # stays at the full row-cell size, so every point up to/around the visible
    # border activates the same key, including special keys and long-press.
    frame_marker = "        val frame = FrameLayout(this).apply {\n"
    touch_wrapper = '''        val touchFrame = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = spec.label
        }
        val frame = FrameLayout(this).apply {
'''
    block = replace_once(block, frame_marker, touch_wrapper, "full-cell touch wrapper")

    block = replace_once(
        block,
        "            isClickable = true\n            isFocusable = false\n            clipChildren = false\n",
        "            isClickable = false\n            isFocusable = false\n            clipChildren = false\n",
        "inner visual frame not clickable",
    )

    touch_attach_marker = '''


        var downX = 0f
'''
    touch_attach = '''

        touchFrame.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        var downX = 0f
'''
    block = replace_once(block, touch_attach_marker, touch_attach, "attach visual keycap to touch wrapper")
    block = replace_once(block, "        frame.setOnTouchListener { view, event ->\n", "        touchFrame.setOnTouchListener { view, event ->\n", "listener on full touch wrapper")

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
    block = replace_once(block, "        return frame\n", "        return touchFrame\n", "return full touch wrapper")

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
                dp(if (pressed) 4 else 3),
                if (pressed) Color.rgb(205, 124, 255) else Color.rgb(181, 86, 249)
            )'''
    block = replace_once(block, old_stroke, new_stroke, "reference neon purple border")
    return source[:start] + block + source[end:]


def patch_cursor_keys(source: str) -> str:
    # Cursor-arrow buttons use the same full-frame touch rule and purple border.
    start_marker = "    private fun cursorDirectionButton(label: String, keyCode: Int): View {"
    end_marker = "\n    private fun cursorPadTouchListener(): View.OnTouchListener {"
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: cursorDirectionButton")

    block = source[start:end]
    block = replace_once(
        block,
        "        val frame = FrameLayout(this).apply {\n",
        '''        val touchFrame = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val frame = FrameLayout(this).apply {
''',
        "cursor full-cell touch wrapper",
    )
    block = replace_once(block, "            isClickable = true\n", "            isClickable = false\n", "cursor inner visual not clickable")
    block = replace_once(
        block,
        "            background = roundedBackground(specialKeyBg, 11f)\n",
        "            background = roundedStrokedBackground(specialKeyBg, 11f, Color.rgb(181, 86, 249), 3)\n",
        "cursor normal purple border",
    )
    block = replace_once(
        block,
        '''        }, FrameLayout.LayoutParams(-1, -1))

        var repeatRunnable: Runnable? = null''',
        '''        }, FrameLayout.LayoutParams(-1, -1))
        touchFrame.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        var repeatRunnable: Runnable? = null''',
        "attach cursor visual to touch wrapper",
    )
    block = replace_once(block, "        frame.setOnTouchListener { view, event ->\n", "        touchFrame.setOnTouchListener { view, event ->\n", "cursor listener on wrapper")
    block = replace_once(
        block,
        "                    view.background = roundedBackground(pressedKeyBg, 11f)\n",
        "                    frame.background = roundedStrokedBackground(pressedKeyBg, 11f, Color.rgb(205, 124, 255), 4)\n",
        "cursor pressed border",
    )
    block = replace_once(
        block,
        "                    view.background = roundedBackground(specialKeyBg, 11f)\n",
        "                    frame.background = roundedStrokedBackground(specialKeyBg, 11f, Color.rgb(181, 86, 249), 3)\n",
        "cursor released border",
    )
    block = replace_once(block, "        return frame\n", "        return touchFrame\n", "return cursor touch wrapper")
    source = source[:start] + block + source[end:]

    # Touchpad/d-pad container borders use the same sampled reference purple.
    old_cursor_stroke = "        setStroke(dp(2), if (pressed) Color.rgb(214, 133, 255) else Color.rgb(127, 86, 180))"
    new_cursor_stroke = "        setStroke(dp(if (pressed) 4 else 3), if (pressed) Color.rgb(205, 124, 255) else Color.rgb(181, 86, 249))"
    source = replace_once(source, old_cursor_stroke, new_cursor_stroke, "cursor pad neon border")
    return source


text = patch_key_view(text)
text = patch_neon_key_border(text)
text = patch_cursor_keys(text)
KEYBOARD_SERVICE.write_text(text)

print("Keyboard neon border + full-frame touch patch applied")
