from pathlib import Path

path = Path("app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt")
text = path.read_text()
text = text.replace("            selectAllOnFocus = false\n", "            setSelectAllOnFocus(false)\n", 1)
text = text.replace(
    "        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(12)); background = darkBg }\n",
    "        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(12)); setBackgroundColor(darkBg) }\n",
    1,
)
path.write_text(text)
print("Brave browser compile fixes applied")
