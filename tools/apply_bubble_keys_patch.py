from pathlib import Path

path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = path.read_text(encoding="utf-8")

# Rebuild each visual key as a layered cap instead of merely rounding the old rectangle.
# Touch cells remain unchanged, so sensitivity stays comfortable. Only the visible key face is
# narrowed/tallened to match the supplied vertical-pill reference.
old_frame = '''        val frame = FrameLayout(this).apply {
            scaleX = keyBoxScale
            scaleY = keyBoxScale
            isClickable = true
            isFocusable = false
            background = roundedBackground(normalColor, 7f)
        }'''
new_frame = '''        val referenceLargeKey = spec.label in listOf(
            "⇧", "⇪", "⌫", "?123", "↵", "✓", "➤", "→", "←", "🔍"
        )
        val referenceWideKey = referenceLargeKey || spec.label == "spasi"
        val frame = FrameLayout(this).apply {
            scaleX = keyBoxScale * if (referenceWideKey) 0.97f else 0.78f
            scaleY = keyBoxScale * if (referenceLargeKey) 0.96f else 0.91f
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Deep lower shadow makes the cap visibly float above the photo/theme.
        frame.addView(View(this).apply {
            background = roundedBackground(Color.argb(190, 4, 4, 7), 22f)
        }, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(2), dp(4), dp(1), 0)
        })

        // Main charcoal key face. This is the surface changed while pressing.
        val keyFace = View(this).apply {
            background = referenceBubbleKeyBackground(pressed = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(2.6f)
        }
        frame.addView(keyFace, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(1), 0, dp(1), dp(4))
        })

        // Inner top rim/highlight: the important detail that creates a molded 3D cap.
        frame.addView(View(this).apply {
            background = referenceTopRimBackground()
            alpha = 0.92f
        }, FrameLayout.LayoutParams(-1, dp(8), Gravity.TOP).apply {
            setMargins(dp(5), dp(3), dp(5), 0)
        })'''
if new_frame not in text:
    if old_frame not in text:
        raise RuntimeError("3D key frame target not found")
    text = text.replace(old_frame, new_frame, 1)

# Main legends stay bright; move them slightly down when a small alternate symbol exists.
old_label = '''        frame.addView(TextView(this).apply {
            text = spec.label
            textSize = when {
                spec.label == "spasi" -> 13f
                spec.label.length > 3 -> 12f
                (spec.label.firstOrNull()?.code ?: 0) > 0x2600 -> keyTextSizeSp + 1f
                else -> keyTextSizeSp
            }
            setTextColor(keyTextColor)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))'''
new_label = '''        frame.addView(TextView(this).apply {
            text = spec.label
            textSize = when {
                spec.label == "spasi" -> 13f
                spec.label.length > 3 -> 12f
                (spec.label.firstOrNull()?.code ?: 0) > 0x2600 -> keyTextSizeSp + 1f
                else -> keyTextSizeSp
            }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (spec.alternate != null) translationY = dpFloat(4f)
            setShadowLayer(dpFloat(1.2f), 0f, dpFloat(1f), Color.BLACK)
        }, FrameLayout.LayoutParams(-1, -1))'''
if new_label not in text:
    if old_label not in text:
        raise RuntimeError("3D key label target not found")
    text = text.replace(old_label, new_label, 1)

# Reference places the small number/symbol legend centered at the top of each cap, not in a corner.
old_alt = '''        spec.alternate?.let { alternate ->
            frame.addView(TextView(this).apply {
                text = alternate
                textSize = 8f
                setTextColor(Color.argb(210, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor)))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(1), dp(4), 0)
            }, FrameLayout.LayoutParams(-1, -1))
        }'''
new_alt = '''        spec.alternate?.let { alternate ->
            frame.addView(TextView(this).apply {
                text = alternate
                textSize = 8f
                setTextColor(Color.rgb(205, 202, 214))
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                setShadowLayer(dpFloat(0.8f), 0f, dpFloat(1f), Color.BLACK)
            }, FrameLayout.LayoutParams(-1, -1))
        }'''
if new_alt not in text:
    if old_alt not in text:
        raise RuntimeError("Centered alternate symbol target not found")
    text = text.replace(old_alt, new_alt, 1)

# Small purple light under every cap, as in the reference.
glow_block = '''
        frame.addView(View(this).apply {
            background = roundedBackground(Color.rgb(202, 105, 255), 2f)
            alpha = 0.96f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(5f)
        }, FrameLayout.LayoutParams(dp(if (referenceLargeKey) 18 else 12), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(1)
        })

'''
if "Color.rgb(202, 105, 255)" not in text:
    marker = '''        var downX = 0f'''
    if marker not in text:
        raise RuntimeError("3D key glow insertion target not found")
    text = text.replace(marker, glow_block + marker, 1)

# Pressing physically lowers the whole cap and darkens only its face.
text = text.replace(
    'view.background = roundedBackground(pressedKeyBg, 7f)',
    '''keyFace.background = referenceBubbleKeyBackground(pressed = true)
                    view.translationY = dpFloat(1.7f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(0.6f)'''
)
text = text.replace(
    'view.background = roundedBackground(normalColor, 7f)',
    '''keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    view.translationY = 0f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(2.6f)'''
)

# The four requested control keys use clearly larger layout cells, not just larger corner radii.
replacements = {
    'KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.25f': 'KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.72f',
    'KeySpec("⌫", weight = 1.25f': 'KeySpec("⌫", weight = 1.72f',
    'KeySpec("?123", weight = 1.18f': 'KeySpec("?123", weight = 1.62f',
    'KeySpec(enterKeyLabel(), weight = 1.25f': 'KeySpec(enterKeyLabel(), weight = 1.62f',
    'KeySpec("⌫", weight = 1.2f': 'KeySpec("⌫", weight = 1.72f',
    'KeySpec("ABC", weight = 1.2f': 'KeySpec("ABC", weight = 1.58f',
    'KeySpec("ABC", weight = 1.15f': 'KeySpec("ABC", weight = 1.58f',
}
for old, new in replacements.items():
    text = text.replace(old, new)

# Give rows more breathing room like the reference. Because the visible letter caps are narrower,
# this produces obvious gaps without shrinking the touch targets.
text = text.replace(
    '''                    setMargins(dp(2), if (isFirstKeyboardRow) 0 else dp(2), dp(2), dp(2))''',
    '''                    setMargins(dp(2), if (isFirstKeyboardRow) dp(1) else dp(3), dp(2), dp(3))''',
    1,
)

helper = '''
    private fun referenceBubbleKeyBackground(pressed: Boolean): GradientDrawable {
        val colors = if (pressed) {
            intArrayOf(
                Color.rgb(45, 44, 52),
                Color.rgb(25, 24, 31),
                Color.rgb(14, 14, 19)
            )
        } else {
            intArrayOf(
                Color.rgb(69, 67, 78),
                Color.rgb(42, 41, 50),
                Color.rgb(24, 23, 30),
                Color.rgb(15, 15, 20)
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dpFloat(23f)
            setStroke(
                dp(1),
                if (pressed) Color.rgb(67, 65, 77) else Color.rgb(97, 94, 108)
            )
        }
    }

    private fun referenceTopRimBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(125, 255, 255, 255),
                Color.argb(35, 255, 255, 255),
                Color.TRANSPARENT
            )
        ).apply {
            cornerRadius = dpFloat(20f)
        }

'''
if "private fun referenceBubbleKeyBackground(" not in text:
    marker = "    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {"
    if marker not in text:
        raise RuntimeError("3D key helper insertion target not found")
    text = text.replace(marker, helper + marker, 1)

path.write_text(text, encoding="utf-8")
print("Applied layered molded 3D pill caps, centered alternates, larger separated control keys, and preserved photo backgrounds.")
