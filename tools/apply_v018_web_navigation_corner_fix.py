from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')


def replace_block(start_marker, end_marker, replacement, label):
    global s
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: block not found')
    s = s[:a] + replacement.rstrip() + '\n\n' + s[b:]

# Make the purple border itself rounded, and clip only the inner surface. Clipping the outer
# stroked container caused the four corners to look cut/square on some devices.
panel = '''    private fun addSearchSurfacePanel() {
        searchSurfaceContent = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpFloat(21f)
            }
            clipChildren = true
            clipToPadding = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
        }
        searchSurfacePanel = FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.rgb(132, 48, 220))
                cornerRadius = dpFloat(24f)
            }
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
            addView(searchSurfaceContent, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(searchSurfacePanel, LinearLayout.LayoutParams(-1, dp(searchSurfaceHeightDp())).apply {
            setMargins(dp(4), dp(3), dp(4), dp(4))
        })
    }'''
replace_block('    private fun addSearchSurfacePanel() {', '    private fun addUtilityBar() {', panel, 'search surface')

# Keep normal web-page navigation inside the embedded WebView. Only hand off when Android can
# resolve the tapped URL to a non-browser installed app (YouTube, Shopee, etc.).
s = s.replace(
'''                    if (!isUserNavigation || target.isBlank()) return false
                    openExternalLink(target)
                    return true''',
'''                    if (!isUserNavigation || target.isBlank()) return false
                    return openInstalledAppForLink(target)'''
)
s = s.replace(
'''                    if (!userTouchedPage || target.isBlank()) return false
                    userTouchedPage = false
                    openExternalLink(target)
                    return true''',
'''                    if (!userTouchedPage || target.isBlank()) return false
                    userTouchedPage = false
                    return openInstalledAppForLink(target)'''
)

helper = '''    private fun openInstalledAppForLink(rawUrl: String): Boolean {
        val url = unwrapSearchRedirect(rawUrl)
        if (url.isBlank()) return false
        return runCatching {
            if (url.startsWith("intent://", ignoreCase = true)) {
                val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val resolved = parsed.resolveActivity(packageManager)
                if (resolved != null && resolved.packageName != packageName) {
                    startActivity(parsed)
                    return@runCatching true
                }
                val fallback = parsed.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    searchWebView?.loadUrl(fallback)
                    return@runCatching true
                }
                return@runCatching false
            }

            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme !in setOf("http", "https")) {
                val direct = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val resolved = direct.resolveActivity(packageManager)
                if (resolved != null && resolved.packageName != packageName) {
                    startActivity(direct)
                    return@runCatching true
                }
                return@runCatching false
            }

            val targetIntent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val browserProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val browserPackages = packageManager.queryIntentActivities(browserProbe, 0)
                .map { it.activityInfo.packageName }
                .toSet()

            val appPackage = packageManager.queryIntentActivities(targetIntent, 0)
                .asSequence()
                .map { it.activityInfo.packageName }
                .firstOrNull { it != packageName && it !in browserPackages }
                ?: return@runCatching false

            targetIntent.setPackage(appPackage)
            startActivity(targetIntent)
            true
        }.getOrDefault(false)
    }'''
if '    private fun openInstalledAppForLink(rawUrl: String): Boolean {' not in s:
    marker = '    private fun openExternalLink(rawUrl: String) {'
    if marker not in s:
        raise RuntimeError('native app helper target not found')
    s = s.replace(marker, helper + '\n\n' + marker, 1)

# Explicit rounded clipping for the two inner panel modes too, so a WebView/camera frame can never
# paint square pixels into the rounded corners.
s = s.replace(
'''        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(18, 18, 23), 20f)
        }''',
'''        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(18, 18, 23), 20f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
        }''',
1
)
s = s.replace(
'''        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 20f)
        }''',
'''        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 20f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
        }''',
1
)

p.write_text(s, encoding='utf-8')
print('Applied v0.18 embedded navigation and rounded-corner fix.')
