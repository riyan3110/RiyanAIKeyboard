from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
PANEL = Path("app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt")


def replace_function(source: str, signature: str, replacement: str) -> str:
    start = source.find(signature)
    if start < 0:
        raise SystemExit(f"Function not found: {signature}")
    brace = source.find("{", start)
    if brace < 0:
        raise SystemExit(f"Opening brace not found: {signature}")
    depth = 0
    i = brace
    in_string = False
    in_char = False
    escape = False
    line_comment = False
    block_comment = False
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if line_comment:
            if ch == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if ch == "*" and nxt == "/":
                block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "'":
                in_char = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            block_comment = True
            i += 2
            continue
        if ch == '"':
            in_string = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                return source[:start] + replacement.rstrip() + source[end:]
        i += 1
    raise SystemExit(f"Closing brace not found: {signature}")


text = SERVICE.read_text()

field_anchor = "    private var searchWebView: WebView? = null\n"
field = "    private var braveBrowserPanel: BraveBrowserPanel? = null\n"
if field not in text:
    if field_anchor not in text:
        raise SystemExit("searchWebView field anchor missing")
    text = text.replace(field_anchor, field_anchor + field, 1)

new_show = r'''    private fun showSearchWebPanel() {
        internalGalleryPanel?.release()
        internalGalleryPanel = null
        stopEmbeddedScanner()
        braveBrowserPanel?.release()
        braveBrowserPanel = null
        searchWebView = null
        searchWebComposeActive = false
        scannerPreviewView = null
        searchSurfaceContent.removeAllViews()

        val browser = BraveBrowserPanel(
            context = this,
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE),
            initialQuery = searchQuery,
            initialUrl = searchUrl,
            onClose = { closeSearchSurface() },
            onOpenScanner = {
                braveBrowserPanel?.release()
                braveBrowserPanel = null
                searchWebView = null
                showEmbeddedCameraPanel(resetCandidate = true)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    scannerPreviewView?.post { startEmbeddedScanner() }
                }
            },
            onOpenKeyboardAi = {
                closeSearchSurface()
                toggleAiPanel(true)
            },
            onActiveInputChanged = { field ->
                searchInput = field
                val active = field != null
                searchComposeActive = active
                searchWebComposeActive = active
                aiComposeActive = false
            }
        )
        braveBrowserPanel = browser
        searchWebView = browser.webView
        searchInput = browser.addressField
        searchSurfaceContent.addView(browser, FrameLayout.LayoutParams(-1, -1))
        showSearchSurface()
    }'''
text = replace_function(text, "    private fun showSearchWebPanel()", new_show)

close_sig = "    private fun closeSearchSurface()"
close_start = text.find(close_sig)
if close_start < 0:
    raise SystemExit("closeSearchSurface missing")
close_brace = text.find("{", close_start)
close_insert = "\n        braveBrowserPanel?.release()\n        braveBrowserPanel = null\n        searchWebView = null\n"
if "braveBrowserPanel?.release()" not in text[close_brace: close_brace + 320]:
    text = text[:close_brace + 1] + close_insert + text[close_brace + 1:]

on_destroy_sig = "    override fun onDestroy()"
destroy_start = text.find(on_destroy_sig)
if destroy_start < 0:
    raise SystemExit("onDestroy missing")
destroy_brace = text.find("{", destroy_start)
destroy_window = text[destroy_brace: destroy_brace + 900]
if "braveBrowserPanel?.release()" not in destroy_window:
    anchor = "        stopEmbeddedScanner()\n"
    pos = text.find(anchor, destroy_brace, destroy_brace + 900)
    if pos < 0:
        raise SystemExit("onDestroy scanner anchor missing")
    pos += len(anchor)
    insert = "        braveBrowserPanel?.release()\n        braveBrowserPanel = null\n        searchWebView = null\n"
    text = text[:pos] + insert + text[pos:]

SERVICE.write_text(text)

panel = PANEL.read_text()
old_version = '            val webViewVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "tidak diketahui"\n'
new_version = '''            val webViewVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n                WebView.getCurrentWebViewPackage()?.versionName ?: "tidak diketahui"\n            } else {\n                "renderer sistem"\n            }\n'''
if old_version in panel:
    panel = panel.replace(old_version, new_version, 1)
PANEL.write_text(panel)

print("Brave full browser integration applied")
