from pathlib import Path

path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = path.read_text(encoding="utf-8")

# Reference-inspired key styling: dark charcoal pill/bubble keys while the keyboard/root
# background remains untouched (including user photo themes).
old_frame = '''        val frame = FrameLayout(this).apply {
            scaleX = keyBoxScale
            scaleY = keyBoxScale
            isClickable = true
            isFocusable = false
            background = roundedBackground(normalColor, 7f)
        }'''
new_frame = '''        val frame = FrameLayout(this).apply {
            scaleX = keyBoxScale
            scaleY = keyBoxScale
            isClickable = true
            isFocusable = false
            background = referenceBubbleKeyBackground(pressed = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpFloat(3.6f)
            }
        }'''
if new_frame not in text:
    if old_frame not in text:
        raise RuntimeError("Reference bubble key frame target not found")
    text = text.replace(old_frame, new_frame, 1)

# Key legends are always bright inside the dark reference-style caps. This changes key labels only,
# not AI panels, toolbar text, suggestion text, or the photo/theme background.
text = text.replace(
    '''            setTextColor(keyTextColor)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))''',
    '''            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))''',
    1,
)
text = text.replace(
    '''                setTextColor(Color.argb(210, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor)))
                gravity = Gravity.TOP or Gravity.END''',
    '''                setTextColor(Color.rgb(200, 198, 210))
                gravity = Gravity.TOP or Gravity.END''',
    1,
)

# Add the tiny purple light under the key face, matching the reference while keeping it subtle.
glow_block = '''
        frame.addView(View(this).apply {
            background = roundedBackground(Color.rgb(194, 92, 255), 2f)
            alpha = 0.82f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(4f)
        }, FrameLayout.LayoutParams(dp(13), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(2)
        })

'''
if "Color.rgb(194, 92, 255)" not in text:
    marker = '''        var downX = 0f'''
    if marker not in text:
        raise RuntimeError("Glow insertion target not found")
    text = text.replace(marker, glow_block + marker, 1)

# Pressed state feels physically pushed in instead of switching to the theme's rectangular key.
text = text.replace(
    'view.background = roundedBackground(pressedKeyBg, 7f)',
    '''view.background = referenceBubbleKeyBackground(pressed = true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dpFloat(0.8f)'''
)
text = text.replace(
    'view.background = roundedBackground(normalColor, 7f)',
    '''view.background = referenceBubbleKeyBackground(pressed = false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dpFloat(3.6f)'''
)

# Make the four important keys visibly larger/wider like the supplied reference.
replacements = {
    'KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.25f': 'KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.55f',
    'KeySpec("⌫", weight = 1.25f': 'KeySpec("⌫", weight = 1.55f',
    'KeySpec("?123", weight = 1.18f': 'KeySpec("?123", weight = 1.55f',
    'KeySpec(enterKeyLabel(), weight = 1.25f': 'KeySpec(enterKeyLabel(), weight = 1.55f',
    'KeySpec("⌫", weight = 1.2f': 'KeySpec("⌫", weight = 1.55f',
    'KeySpec("ABC", weight = 1.2f': 'KeySpec("ABC", weight = 1.45f',
    'KeySpec("ABC", weight = 1.15f': 'KeySpec("ABC", weight = 1.45f',
}
for old, new in replacements.items():
    text = text.replace(old, new)

helper = '''
    private fun referenceBubbleKeyBackground(pressed: Boolean): GradientDrawable {
        // Fixed charcoal key-cap colors intentionally do not follow the photo/theme background.
        val colors = if (pressed) {
            intArrayOf(
                Color.rgb(39, 38, 46),
                Color.rgb(27, 26, 33),
                Color.rgb(20, 19, 25)
            )
        } else {
            intArrayOf(
                Color.rgb(57, 55, 66),
                Color.rgb(37, 36, 45),
                Color.rgb(25, 24, 31)
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dpFloat(if (pressed) 18f else 22f)
            setStroke(
                dp(1),
                if (pressed) Color.rgb(57, 55, 67) else Color.rgb(83, 80, 94)
            )
        }
    }

'''
if "private fun referenceBubbleKeyBackground(" not in text:
    marker = "    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {"
    if marker not in text:
        raise RuntimeError("Rounded background helper target not found")
    text = text.replace(marker, helper + marker, 1)

path.write_text(text, encoding="utf-8")
print("Applied dark reference-style pill keys, purple under-glow, and larger Shift/Delete/?123/Enter keys while preserving photo backgrounds.")
