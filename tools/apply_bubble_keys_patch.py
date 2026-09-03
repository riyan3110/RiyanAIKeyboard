from pathlib import Path

path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = path.read_text(encoding="utf-8")

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
            background = bubbleKeyBackground(normalColor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpFloat(2.4f)
            }
        }'''
if new_frame not in text:
    if old_frame not in text:
        raise RuntimeError("Bubble key frame target not found")
    text = text.replace(old_frame, new_frame, 1)

text = text.replace(
    'view.background = roundedBackground(pressedKeyBg, 7f)',
    '''view.background = bubbleKeyBackground(pressedKeyBg, pressed = true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dpFloat(0.7f)'''
)
text = text.replace(
    'view.background = roundedBackground(normalColor, 7f)',
    '''view.background = bubbleKeyBackground(normalColor)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) view.elevation = dpFloat(2.4f)'''
)

helper = '''
    private fun bubbleKeyBackground(baseColor: Int, pressed: Boolean = false): GradientDrawable {
        val top = mixColor(baseColor, Color.WHITE, if (pressed) 0.04f else 0.16f)
        val middle = if (pressed) mixColor(baseColor, Color.BLACK, 0.04f) else baseColor
        val bottom = mixColor(baseColor, Color.BLACK, if (pressed) 0.18f else 0.11f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, middle, bottom)
        ).apply {
            cornerRadius = dpFloat(if (pressed) 13f else 17f)
            setStroke(
                dp(1),
                mixColor(baseColor, Color.WHITE, if (pressed) 0.06f else 0.20f)
            )
        }
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            Color.alpha(from),
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to))
        )
    }

'''
if "private fun bubbleKeyBackground(" not in text:
    marker = "    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {"
    if marker not in text:
        raise RuntimeError("Rounded background helper target not found")
    text = text.replace(marker, helper + marker, 1)

path.write_text(text, encoding="utf-8")
print("Applied bubble/3D key backgrounds while preserving existing key labels and behavior.")
