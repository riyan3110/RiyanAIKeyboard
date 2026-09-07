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
        // The two small floating controls can restore the bars or hide them again.
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
            description = "Tampilkan kembali bar browser"
        ) { setBrowserFullscreen(false) }
        addView(browserBarsRestore, LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            rightMargin = dp(45)
        })

        browserFullscreenEnter = browserChromeButton(
            label = "⛶",
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
    """        row.addView(bottomButton("⌂") { openHome() })
        row.addView(bottomButton("‹") { if (webView.canGoBack()) webView.goBack() })
        row.addView(bottomButton("›") { if (webView.canGoForward()) webView.goForward() })
        row.addView(bottomButton("☆") { toggleBookmark() })
        row.addView(bottomButton("▣") { showTabs() }.also { it.tag = TAB_COUNTER_TAG })
        row.addView(bottomButton("↻") { webView.reload() })
""",
    """        row.addView(bottomButton("⌂") { openHome() })
        row.addView(bottomButton("←") { if (webView.canGoBack()) webView.goBack() })
        row.addView(bottomButton("→") { if (webView.canGoForward()) webView.goForward() })
        row.addView(bottomButton("✧") { toggleBookmark() })
        row.addView(bottomButton("▦") { showTabs() }.also { it.tag = TAB_COUNTER_TAG })
        row.addView(bottomButton("⟳") { webView.reload() })
""",
    "modern bottom browser icons",
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
""",
    """    private fun browserChromeButton(label: String, description: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(Color.argb(224, 37, 37, 42), 11f)
        contentDescription = description
        elevation = dp(5).toFloat()
        setOnClickListener { action() }
    }

    private fun bottomButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 19.5f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
    }

    private fun iconButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
""",
    "modern chrome button helper",
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
        if (::browserBarsRestore.isInitialized) {
            browserBarsRestore.visibility = View.VISIBLE
            browserBarsRestore.alpha = if (enabled) 1f else 0.72f
            browserBarsRestore.bringToFront()
        }
        if (::browserFullscreenEnter.isInitialized) {
            browserFullscreenEnter.visibility = View.VISIBLE
            browserFullscreenEnter.alpha = if (enabled) 0.72f else 1f
            browserFullscreenEnter.bringToFront()
        }
        mainColumn.requestLayout()
""",
    "fullscreen chrome visibility",
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
        if (::browserBarsRestore.isInitialized) browserBarsRestore.visibility = View.VISIBLE
        if (::browserFullscreenEnter.isInitialized) browserFullscreenEnter.visibility = View.VISIBLE
""",
    "restore browser chrome buttons after custom video",
)

BROWSER.write_text(source)
print("Applied browser auto-fullscreen + modern chrome controls patch")
