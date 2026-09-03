from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')

start = s.find('    private fun addSearchSurfacePanel() {')
end = s.find('    private fun addUtilityBar() {', start)
if start < 0 or end < 0:
    raise RuntimeError('search surface panel target not found')

new = '''    private fun addSearchSurfacePanel() {
        searchSurfaceContent = FrameLayout(this)
        searchSurfacePanel = FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpFloat(24f)
                setStroke(dp(2), Color.rgb(171, 78, 255))
            }
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
            addView(searchSurfaceContent, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(searchSurfacePanel, LinearLayout.LayoutParams(-1, dp(searchSurfaceHeightDp())).apply {
            setMargins(dp(4), dp(3), dp(4), dp(4))
        })
    }

'''
if 'setStroke(dp(2), Color.rgb(171, 78, 255))' not in s:
    s = s[:start] + new + s[end:]

p.write_text(s, encoding='utf-8')
print('Applied v0.18 purple clipped web/camera frame.')
