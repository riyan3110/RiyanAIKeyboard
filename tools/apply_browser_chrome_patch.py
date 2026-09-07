from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BROWSER = ROOT / "app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt"

source = BROWSER.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global source
    if new in source:
        return
    if old not in source:
        raise RuntimeError(f"Browser chrome patch marker not found: {label}")
    source = source.replace(old, new, 1)


replace_once(
    """    private lateinit var browserFullscreenExit: TextView
""",
    """    private lateinit var browserFullscreenExit: TextView
    private lateinit var browserBarsRestore: TextView
    private lateinit var browserFullscreenEnter: TextView
""",
    "browser chrome control fields",
)

replace_once(
    """        buildBrowser()
        restoreTabs()
""",
    """        buildBrowser()
        // Browser/search starts with only the page visible, filling the purple frame.
        // Floating restore/fullscreen controls are shown only while the bars are hidden.
        post { setBrowserFullscreen(true) }
        restoreTabs()
""",
    "initial browser fullscreen",
)

replace_once(
    """        addView(browserFullscreenExit, LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            rightMargin = dp(7)
        })
    }

    private fun buildTopBar(): View {
""",
    """        addView(browserFullscreenExit, LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            rightMargin = dp(7)
        })

        browserBarsRestore = browserChromeButton(
            label = "▤",
            tint = Color.rgb(77, 208, 225),
            description = "Tampilkan kembali bar browser"
        ) { setBrowserFullscreen(false) }
        addView(browserBarsRestore, LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            rightMargin = dp(45)
        })

        browserFullscreenEnter = browserChromeButton(
            label = "⛶",
            tint = Color.rgb(186, 104, 255),
            description = "Layar penuh sampai bingkai ungu"
        ) { setBrowserFullscreen(true) }
        addView(browserFullscreenEnter, LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            rightMargin = dp(7)
        })
    }

    private fun buildTopBar(): View {
""",
    "floating bar/fullscreen controls",
)

replace_once(
    """        row.addView(TextView(context).apply {
            text = "🦁"
            textSize = 19f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(38), -1))

        addressField = EditText(context).apply {
""",
    """        // No browser mascot here: give the address field maximum room on the left.
        addressField = EditText(context).apply {
""",
    "remove lion and shift address left",
)

replace_once(
    """        row.addView(addressField, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(5)
        })
        row.addView(iconButton("🛡") { showQuickSettings() }, LinearLayout.LayoutParams(dp(42), -1))
        row.addView(iconButton("⋮") { showMainMenu() }, LinearLayout.LayoutParams(dp(42), -1))
        return row
""",
    """        row.addView(addressField, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = 0
            rightMargin = dp(3)
        })
        // Three compact controls now fit cleanly to the right of the URL field.
        row.addView(iconButton("🛡", Color.rgb(74, 186, 255)) { showQuickSettings() }, LinearLayout.LayoutParams(dp(36), -1))
        row.addView(iconButton("▤", Color.rgb(77, 208, 225)) { setBrowserFullscreen(false) }, LinearLayout.LayoutParams(dp(36), -1))
        row.addView(iconButton("⛶", Color.rgb(186, 104, 255)) { setBrowserFullscreen(true) }, LinearLayout.LayoutParams(dp(36), -1))
        return row
""",
    "three compact top controls",
)

replace_once(
    """        row.addView(bottomButton("⌂") { openHome() })
        row.addView(bottomButton("‹") { if (webView.canGoBack()) webView.goBack() })
        row.addView(bottomButton("›") { if (webView.canGoForward()) webView.goForward() })
        row.addView(bottomButton("☆") { toggleBookmark() })
        row.addView(bottomButton("▣") { showTabs() }.also { it.tag = TAB_COUNTER_TAG })
        row.addView(bottomButton("↻") { webView.reload() })
""",
    """        row.addView(bottomButton("⌂", Color.rgb(79, 195, 247)) { openHome() })
        row.addView(bottomButton("❮", Color.rgb(129, 212, 250)) { if (webView.canGoBack()) webView.goBack() })
        row.addView(bottomButton("❯", Color.rgb(105, 240, 174)) { if (webView.canGoForward()) webView.goForward() })
        row.addView(bottomButton("★", Color.rgb(255, 202, 40)) { toggleBookmark() })
        row.addView(bottomButton("▦", Color.rgb(179, 136, 255)) { showTabs() }.also { it.tag = TAB_COUNTER_TAG })
        row.addView(bottomButton("⟳", Color.rgb(38, 198, 218)) { webView.reload() })
        row.addView(bottomButton("⋮", Color.rgb(244, 143, 177)) { showMainMenu() })
""",
    "modern colored bottom browser icons and moved menu",
)

replace_once(
    """    private fun bottomButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(lightText)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
    }

    private fun iconButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(lightText)
        setOnClickListener { action() }
    }
""",
    """    private fun browserChromeButton(
        label: String,
        tint: Int,
        description: String,
        action: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(tint)
        background = rounded(Color.argb(232, 39, 39, 44), 11f)
        contentDescription = description
        elevation = dp(5).toFloat()
        setOnClickListener { action() }
    }

    private fun bottomButton(label: String, tint: Int, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 18.5f
        gravity = Gravity.CENTER
        setTextColor(tint)
        background = rounded(Color.argb(72, 255, 255, 255), 11f)
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(dp(2), dp(1), dp(2), dp(1))
        }
    }

    private fun iconButton(label: String, tint: Int = lightText, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 17.5f
        gravity = Gravity.CENTER
        setTextColor(tint)
        background = rounded(Color.argb(54, 255, 255, 255), 10f)
        setOnClickListener { action() }
    }
""",
    "colored modern chrome helpers",
)

replace_once(
    """        findViewWithTag<TextView>(TAB_COUNTER_TAG)?.text = "▣${tabs.size}"
""",
    """        findViewWithTag<TextView>(TAB_COUNTER_TAG)?.text = "▦${tabs.size}"
""",
    "modern tab counter icon",
)

replace_once(
    """        if (::browserFullscreenExit.isInitialized) {
            browserFullscreenExit.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) browserFullscreenExit.bringToFront()
        }
        mainColumn.requestLayout()
""",
    """        // The old exit button is reserved for HTML/video custom fullscreen only.
        if (::browserFullscreenExit.isInitialized && customViewHost.visibility != View.VISIBLE) {
            browserFullscreenExit.visibility = View.GONE
        }
        // Floating controls exist only when the browser bars are hidden, so they can never cover
        // quick-settings/menu controls while the regular chrome is visible.
        if (::browserBarsRestore.isInitialized) {
            browserBarsRestore.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) browserBarsRestore.bringToFront()
        }
        if (::browserFullscreenEnter.isInitialized) {
            browserFullscreenEnter.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) browserFullscreenEnter.bringToFront()
        }
        mainColumn.requestLayout()
""",
    "fullscreen chrome visibility without overlap",
)

replace_once(
    """        browserFullscreenExit.visibility = View.VISIBLE
        customViewHost.bringToFront()
        browserFullscreenExit.bringToFront()
""",
    """        browserFullscreenExit.visibility = View.VISIBLE
        if (::browserBarsRestore.isInitialized) browserBarsRestore.visibility = View.GONE
        if (::browserFullscreenEnter.isInitialized) browserFullscreenEnter.visibility = View.GONE
        customViewHost.bringToFront()
        browserFullscreenExit.bringToFront()
""",
    "hide browser chrome buttons for custom video",
)

replace_once(
    """        runCatching { callback?.onCustomViewHidden() }
        setBrowserFullscreen(fullscreenBeforeVideo)
""",
    """        runCatching { callback?.onCustomViewHidden() }
        setBrowserFullscreen(fullscreenBeforeVideo)
""",
    "restore browser chrome state after custom video",
)

# Modern, colorful menu glyphs. Emoji keep their native color on Android and make the menu
# visually distinct without adding external drawable/font dependencies.
menu_replacements = {
    'menuItem("📷", "Kamera Penelusuran")': 'menuItem("📸", "Kamera Penelusuran")',
    'menuItem("⊞", "Tab baru")': 'menuItem("➕", "Tab baru")',
    'menuItem("🕶", "Tab Privat baru")': 'menuItem("🕶️", "Tab Privat baru")',
    'menuItem("▦", "Tambahkan tab ke grup baru")': 'menuItem("🗂️", "Tambahkan tab ke grup baru")',
    'menuItem("◴", "Histori")': 'menuItem("🕘", "Histori")',
    'menuItem("⇩", "Download")': 'menuItem("⬇️", "Download")',
    'menuItem("▣", "Dompet")': 'menuItem("👛", "Dompet")',
    'menuItem("🔖", "Bookmark")': 'menuItem("🔖", "Bookmark")',
    'menuItem("✧", "AI Leo")': 'menuItem("✨", "AI Leo")',
    'menuItem("▣", "Tab terbaru")': 'menuItem("🗂️", "Tab terbaru")',
    'menuItem("⛶", "Mode layar penuh")': 'menuItem("🖥️", "Mode layar penuh")',
    'menuItem("⚙", "Setelan")': 'menuItem("⚙️", "Setelan")',
    'menuItem("★", "Tetapkan Brave sebagai Peramban")': 'menuItem("🌐", "Tetapkan Brave sebagai Peramban")',
    'menuItem("△", "Brave Rewards")': 'menuItem("🏆", "Brave Rewards")',
    'menuItem("▤", "Brave News")': 'menuItem("📰", "Brave News")',
    'menuItem("◉", if (isSystemVpnActive())': 'menuItem("🔐", if (isSystemVpnActive())',
    'menuItem("🛠", "Sesuaikan menu")': 'menuItem("🎨", "Sesuaikan menu")',
    'circleAction("→")': 'circleAction("➡️")',
    'circleAction("↗")': 'circleAction("📤")',
    'circleAction("⇩")': 'circleAction("⬇️")',
    'circleAction("↻")': 'circleAction("🔄")',
}
for old, new in menu_replacements.items():
    if old in source:
        source = source.replace(old, new)

BROWSER.write_text(source)
print("Applied browser auto-fullscreen + non-overlapping modern colored chrome controls patch")
