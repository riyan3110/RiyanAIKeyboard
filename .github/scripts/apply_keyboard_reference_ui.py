from pathlib import Path
import re

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text()

# Utility bar: keep emoji in the bar, move cursor mode into the bar,
# compact widths so everything fits, and add a purple glow line above it.
utility_pattern = re.compile(
    r'    private fun addUtilityBar\(\) \{.*?\n    \}\n\n    private fun addSuggestionBar\(\)',
    re.S,
)
utility_replacement = '''    private fun addUtilityBar() {
        val barHeight = dp(utilityHeightDp())
        val barFrame = FrameLayout(this).apply {
            setBackgroundColor(keyBg)
            clipChildren = false
            clipToPadding = false
        }

        utilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), 0)
            setBackgroundColor(keyBg)
        }

        utilityBar.addView(toolbarButton("✦ AI", dp(50)) { toggleAiPanel() })
        utilityBar.addView(toolbarButton("⌨", dp(36)) {
            aiComposeActive = false
            mode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("😊", dp(36)) {
            aiComposeActive = false
            emojiPage = 0
            mode = KeyboardMode.EMOJI
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("📋", dp(36)) {
            aiComposeActive = false
            addCurrentClipboardToHistory()
            mode = KeyboardMode.CLIPBOARD
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("Kursor", dp(50)) {
            aiComposeActive = false
            mode = KeyboardMode.CURSOR
            renderKeyboard()
        })
        utilityBar.addView(
            toolbarIconButton(R.drawable.ic_camera_modern, "Kamera penelusuran", dp(36)) { launchScanner() }
        )
        utilityBar.addView(toolbarButton("↕", dp(34)) { toggleResizePanel() })

        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(1), 0, dp(1), 0)
            setBackgroundColor(keyBg)
            visibility = View.VISIBLE
        }
        utilityBar.addView(suggestionBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        utilityBar.addView(
            toolbarIconButton(R.drawable.ic_settings_modern, "Pengaturan", dp(38)) { openSettings() }
        )

        barFrame.addView(utilityBar, FrameLayout.LayoutParams(-1, barHeight))

        // Purple light strip modeled after the reference: soft glow plus a crisp core line.
        barFrame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.TRANSPARENT,
                Color.argb(150, 152, 73, 255),
                Color.argb(230, 193, 92, 255),
                Color.argb(150, 152, 73, 255),
                Color.TRANSPARENT
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(5f)
        }, FrameLayout.LayoutParams(-1, dp(4), Gravity.TOP))
        barFrame.addView(View(this).apply {
            setBackgroundColor(Color.rgb(188, 82, 255))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(6f)
        }, FrameLayout.LayoutParams(-1, dp(1), Gravity.TOP))

        root.addView(barFrame, LinearLayout.LayoutParams(-1, barHeight).apply {
            bottomMargin = dp(toolbarKeyboardGapDp())
        })
    }

    private fun addSuggestionBar()'''
s, n = utility_pattern.subn(utility_replacement, s, count=1)
if n != 1:
    raise SystemExit(f'addUtilityBar replacement failed: {n}')

# Cursor now lives in the toolbar, so remove the old cursor shortcut from symbol page 2.
old_cursor = '                KeySpec("2/2", weight = 1.25f, action = { mode = KeyboardMode.CURSOR; renderKeyboard() }),\n'
if old_cursor in s:
    s = s.replace(old_cursor, '', 1)

# Reference key geometry: rounded rectangle instead of bubble/pill.
if 'cornerRadius = dpFloat(23f)' not in s:
    raise SystemExit('referenceBubbleKeyBackground radius anchor missing')
s = s.replace('cornerRadius = dpFloat(23f)', 'cornerRadius = dpFloat(11f)', 1)

# Flatter premium dark key face.
s = s.replace(
    '''                Color.rgb(42, 41, 50),
                Color.rgb(24, 23, 30),
                Color.rgb(15, 15, 20)''',
    '''                Color.rgb(45, 44, 53),
                Color.rgb(39, 38, 47),
                Color.rgb(35, 34, 43)''',
    1,
)

# Thin top rim instead of thick glossy bubble highlight.
rim_old = '}, FrameLayout.LayoutParams(-1, dp(8), Gravity.TOP).apply {\n            setMargins(dp(5), dp(3), dp(5), 0)\n        })'
rim_new = '}, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP).apply {\n            setMargins(dp(5), dp(2), dp(5), 0)\n        })'
if rim_old not in s:
    raise SystemExit('top rim anchor missing')
s = s.replace(rim_old, rim_new, 1)

# Long-press symbol/number hint in upper-right like reference image 2.
alt_old = '''                textSize = 8f
                setTextColor(Color.rgb(205, 202, 214))
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)'''
alt_new = '''                textSize = 8f
                setTextColor(Color.rgb(176, 174, 184))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(3), dp(6), 0)'''
if alt_old not in s:
    raise SystemExit('alternate legend anchor missing')
s = s.replace(alt_old, alt_new, 1)

# Remove the small purple underline from each key. The purple light belongs above the toolbar only.
underline_pattern = re.compile(
    r'''\n        frame\.addView\(View\(this\)\.apply \{\n            background = roundedBackground\(Color\.rgb\(202, 105, 255\), 2f\)\n            alpha = 0\.96f\n            if \(Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.LOLLIPOP\) elevation = dpFloat\(5f\)\n        \}, FrameLayout\.LayoutParams\(dp\(if \(referenceLargeKey\) 18 else 12\), dp\(2\), Gravity\.BOTTOM or Gravity\.CENTER_HORIZONTAL\)\.apply \{\n            bottomMargin = dp\(1\)\n        \}\)\n'''
)
s, n = underline_pattern.subn('\n', s, count=1)
if n != 1:
    raise SystemExit(f'purple key underline removal failed: {n}')

# Slightly wider regular caps and a squarer vertical proportion, without changing touch cells.
scale_x_old = 'scaleX = (keyBoxScale * if (referenceWideKey) 0.98f else 0.90f).coerceAtMost(1f)'
scale_x_new = 'scaleX = (keyBoxScale * if (referenceWideKey) 0.98f else 0.94f).coerceAtMost(1f)'
scale_y_old = 'scaleY = (keyBoxScale * if (referenceLargeKey) 0.98f else 0.94f).coerceAtMost(1f)'
scale_y_new = 'scaleY = (keyBoxScale * if (referenceLargeKey) 0.96f else 0.90f).coerceAtMost(1f)'
if scale_x_old not in s or scale_y_old not in s:
    raise SystemExit('key scale anchors missing')
s = s.replace(scale_x_old, scale_x_new, 1)
s = s.replace(scale_y_old, scale_y_new, 1)

p.write_text(s)
print('Keyboard reference UI applied successfully')
