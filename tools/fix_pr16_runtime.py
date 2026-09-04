from pathlib import Path

path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = path.read_text()

prop_old = "    private lateinit var utilityBar: LinearLayout\n"
prop_new = "    private lateinit var utilityBar: LinearLayout\n    private lateinit var utilityBarFrame: FrameLayout\n"
if prop_new not in text:
    if prop_old not in text:
        raise SystemExit("utilityBar property anchor not found")
    text = text.replace(prop_old, prop_new, 1)

if "        val barFrame = FrameLayout(this).apply {" in text:
    text = text.replace(
        "        val barFrame = FrameLayout(this).apply {",
        "        utilityBarFrame = FrameLayout(this).apply {",
        1,
    )

text = text.replace(
    "        barFrame.addView(utilityBar, FrameLayout.LayoutParams(-1, barHeight))",
    "        utilityBarFrame.addView(utilityBar, FrameLayout.LayoutParams(-1, barHeight))",
    1,
)
text = text.replace(
    "        barFrame.addView(View(this).apply {",
    "        utilityBarFrame.addView(View(this).apply {",
    1,
)
text = text.replace(
    "        barFrame.addView(View(this).apply {",
    "        utilityBarFrame.addView(View(this).apply {",
    1,
)
text = text.replace(
    "        root.addView(barFrame, LinearLayout.LayoutParams(-1, barHeight).apply {",
    "        root.addView(utilityBarFrame, LinearLayout.LayoutParams(-1, barHeight).apply {",
    1,
)

old_height_block = '''        if (::utilityBar.isInitialized) {
            utilityBar.layoutParams = (utilityBar.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(utilityHeightDp()))).apply {
                height = dp(utilityHeightDp())
                bottomMargin = dp(toolbarKeyboardGapDp())
            }
        }'''
new_height_block = '''        if (::utilityBarFrame.isInitialized) {
            utilityBarFrame.layoutParams = (utilityBarFrame.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(utilityHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = dp(utilityHeightDp())
                bottomMargin = dp(toolbarKeyboardGapDp())
            }
        }
        if (::utilityBar.isInitialized) {
            utilityBar.layoutParams = (utilityBar.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(-1, dp(utilityHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = dp(utilityHeightDp())
            }
        }'''
if old_height_block not in text:
    raise SystemExit("applyRootHeight utilityBar block not found")
text = text.replace(old_height_block, new_height_block, 1)

path.write_text(text)
