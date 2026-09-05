from pathlib import Path

panel_path = Path("app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt")
service_path = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")

panel = panel_path.read_text()
service = service_path.read_text()

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing patch target: {label}")
    return text.replace(old, new, 1)

panel = replace_once(
    panel,
    "import android.net.Uri\n",
    "import android.net.Uri\nimport android.net.ConnectivityManager\nimport android.net.NetworkCapabilities\n",
    "network imports",
)

panel = replace_once(
    panel,
    "    private val mainColumn = LinearLayout(context)\n"
    "    private val overlayHost = FrameLayout(context)\n"
    "    private val tabs = mutableListOf<BrowserTab>()\n"
    "    private var currentTabIndex = 0\n"
    "    private var trackerCount = 0\n",
    "    private val mainColumn = LinearLayout(context)\n"
    "    private val overlayHost = FrameLayout(context)\n"
    "    private val customViewHost = FrameLayout(context)\n"
    "    private lateinit var topBar: View\n"
    "    private lateinit var bottomBar: View\n"
    "    private lateinit var browserFullscreenExit: TextView\n"
    "    private var customViewCallback: WebChromeClient.CustomViewCallback? = null\n"
    "    private var fullscreenBeforeVideo = false\n"
    "    private var browserFullscreen = false\n"
    "    private val tabs = mutableListOf<BrowserTab>()\n"
    "    private var currentTabIndex = 0\n"
    "    private var trackerCount = 0\n",
    "browser state fields",
)

panel = replace_once(
    panel,
    '        buildBrowser()\n'
    '        tabs += BrowserTab(title = "Brave", url = initialUrl.trim(), privateMode = false)\n'
    '        updateTabCounter()\n'
    '        when {\n'
    '            initialUrl.isNotBlank() -> navigate(initialUrl, addHistory = false)\n'
    '            initialQuery.isNotBlank() -> navigate(initialQuery, addHistory = false)\n'
    '            else -> openHome()\n'
    '        }\n',
    '        buildBrowser()\n'
    '        restoreTabs()\n'
    '        if (tabs.isEmpty()) tabs += BrowserTab(title = "Brave", privateMode = false)\n'
    '        currentTabIndex = currentTabIndex.coerceIn(0, tabs.lastIndex)\n'
    '        updateTabCounter()\n'
    '        when {\n'
    '            initialUrl.isNotBlank() -> navigate(initialUrl, addHistory = false)\n'
    '            initialQuery.isNotBlank() -> navigate(initialQuery, addHistory = false)\n'
    '            currentTab().url.isNotBlank() -> webView.loadUrl(currentTab().url)\n'
    '            else -> openHome()\n'
    '        }\n',
    "restore tabs in init",
)

panel = replace_once(
    panel,
    "    fun release() {\n        onActiveInputChanged(null)\n",
    "    fun release() {\n        persistTabs()\n        hideCustomVideo()\n        onActiveInputChanged(null)\n",
    "persist tabs on release",
)

panel = replace_once(
    panel,
    "        mainColumn.addView(buildTopBar(), LinearLayout.LayoutParams(-1, dp(54)))\n",
    "        topBar = buildTopBar()\n"
    "        mainColumn.addView(topBar, LinearLayout.LayoutParams(-1, dp(54)))\n",
    "top bar reference",
)

panel = replace_once(
    panel,
    '                    if (scheme == "http" || scheme == "https") return false\n'
    '                    return runCatching {\n',
    '                    if (scheme == "http" || scheme == "https") {\n'
    '                        if (openNativeAppIfSupported(uri)) return true\n'
    '                        return false\n'
    '                    }\n'
    '                    return runCatching {\n',
    "native app links",
)

panel = replace_once(
    panel,
    "                        if (!currentTab().privateMode && prefs.getBoolean(KEY_SAVE_HISTORY, true)) {\n"
    "                            saveHistory(view?.title.orEmpty(), clean)\n"
    "                        }\n"
    "                    }\n",
    "                        if (!currentTab().privateMode && prefs.getBoolean(KEY_SAVE_HISTORY, true)) {\n"
    "                            saveHistory(view?.title.orEmpty(), clean)\n"
    "                        }\n"
    "                        persistTabs()\n"
    "                    }\n",
    "persist on page finish",
)

panel = replace_once(
    panel,
    "                override fun onReceivedTitle(view: WebView?, title: String?) {\n"
    "                    val value = title?.trim().orEmpty()\n"
    "                    if (value.isNotBlank()) currentTab().title = value.take(80)\n"
    "                }\n\n"
    "                override fun onPermissionRequest(request: PermissionRequest?) {\n",
    "                override fun onReceivedTitle(view: WebView?, title: String?) {\n"
    "                    val value = title?.trim().orEmpty()\n"
    "                    if (value.isNotBlank()) {\n"
    "                        currentTab().title = value.take(80)\n"
    "                        persistTabs()\n"
    "                    }\n"
    "                }\n\n"
    "                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {\n"
    "                    if (view == null || callback == null) return\n"
    "                    showCustomVideo(view, callback)\n"
    "                }\n\n"
    "                override fun onHideCustomView() {\n"
    "                    hideCustomVideo()\n"
    "                }\n\n"
    "                override fun onPermissionRequest(request: PermissionRequest?) {\n",
    "youtube custom fullscreen hooks",
)

panel = replace_once(
    panel,
    "        mainColumn.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))\n"
    "        mainColumn.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, dp(46)))\n\n"
    "        overlayHost.visibility = View.GONE\n",
    "        mainColumn.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))\n"
    "        bottomBar = buildBottomBar()\n"
    "        mainColumn.addView(bottomBar, LinearLayout.LayoutParams(-1, dp(46)))\n\n"
    "        overlayHost.visibility = View.GONE\n",
    "bottom bar reference",
)

panel = replace_once(
    panel,
    "        overlayHost.setBackgroundColor(Color.argb(88, 0, 0, 0))\n"
    "        addView(overlayHost, LayoutParams(-1, -1))\n"
    "    }\n\n"
    "    private fun buildTopBar(): View {\n",
    "        overlayHost.setBackgroundColor(Color.argb(88, 0, 0, 0))\n"
    "        addView(overlayHost, LayoutParams(-1, -1))\n\n"
    "        customViewHost.visibility = View.GONE\n"
    "        customViewHost.setBackgroundColor(Color.BLACK)\n"
    "        addView(customViewHost, LayoutParams(-1, -1))\n\n"
    "        browserFullscreenExit = TextView(context).apply {\n"
    "            text = \"↙\"\n"
    "            textSize = 18f\n"
    "            gravity = Gravity.CENTER\n"
    "            setTextColor(Color.WHITE)\n"
    "            background = rounded(Color.argb(205, 35, 35, 38), 16f)\n"
    "            visibility = View.GONE\n"
    "            contentDescription = \"Keluar dari layar penuh\"\n"
    "            setOnClickListener {\n"
    "                if (customViewHost.visibility == View.VISIBLE) hideCustomVideo() else setBrowserFullscreen(false)\n"
    "            }\n"
    "        }\n"
    "        addView(browserFullscreenExit, LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply {\n"
    "            topMargin = dp(7)\n"
    "            rightMargin = dp(7)\n"
    "        })\n"
    "    }\n\n"
    "    private fun buildTopBar(): View {\n",
    "fullscreen hosts",
)

old_shield = '''        row.addView(iconButton("🛡") {
            val enabled = !prefs.getBoolean(KEY_SHIELDS_ENABLED, true)
            prefs.edit().putBoolean(KEY_SHIELDS_ENABLED, enabled).apply()
            applyPrivacySettings()
            Toast.makeText(context, if (enabled) "Perisai Brave aktif" else "Perisai Brave nonaktif", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(42), -1))
'''
panel = replace_once(
    panel,
    old_shield,
    '        row.addView(iconButton("🛡") { showQuickSettings() }, LinearLayout.LayoutParams(dp(42), -1))\n',
    "shield opens functional quick settings",
)

old_engine = '''        return when (prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded"
            ENGINE_DDG -> "https://duckduckgo.com/?q=$encoded"
            else -> "https://search.brave.com/search?q=$encoded&source=web"
        }
'''
panel = replace_once(
    panel,
    old_engine,
    '''        val safe = prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE) ?: SAFE_MODERATE
        return when (prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded&safe=${if (safe == SAFE_OFF) "off" else "active"}"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded&adlt=${when (safe) { SAFE_STRICT -> "strict"; SAFE_OFF -> "off"; else -> "moderate" }}"
            ENGINE_DDG -> "https://duckduckgo.com/?q=$encoded&kp=${if (safe == SAFE_OFF) "-2" else "1"}"
            else -> "https://search.brave.com/search?q=$encoded&source=web&safesearch=$safe"
        }
''',
    "safe search URLs",
)

panel = replace_once(
    panel,
    '        tabs += BrowserTab(title = if (privateMode) "Tab Privat" else "Tab baru", privateMode = privateMode)\n'
    '        currentTabIndex = tabs.lastIndex\n',
    '        tabs += BrowserTab(title = if (privateMode) "Tab Privat" else "Tab baru", privateMode = privateMode)\n'
    '        currentTabIndex = tabs.lastIndex\n'
    '        persistTabs()\n',
    "persist new tab",
)

panel = replace_once(
    panel,
    "        currentTabIndex = index\n"
    "        trackerCount = 0\n",
    "        currentTabIndex = index\n"
    "        persistTabs()\n"
    "        trackerCount = 0\n",
    "persist tab switch",
)

panel = replace_once(
    panel,
    "        currentTabIndex = currentTabIndex.coerceAtMost(tabs.lastIndex)\n"
    "        updateTabCounter()\n",
    "        currentTabIndex = currentTabIndex.coerceAtMost(tabs.lastIndex)\n"
    "        persistTabs()\n"
    "        updateTabCounter()\n",
    "persist close tab",
)

panel = replace_once(
    panel,
    '        card.addView(menuItem("▣", "Tab terbaru") { showTabs() })\n'
    '        card.addView(separator())\n',
    '        card.addView(menuItem("▣", "Tab terbaru") { showTabs() })\n'
    '        card.addView(menuItem("⛶", "Mode layar penuh") { hideOverlay(); setBrowserFullscreen(true) })\n'
    '        card.addView(separator())\n',
    "fullscreen menu",
)

panel = replace_once(
    panel,
    '        card.addView(menuItem("◉", "VPN Brave") { navigate("https://brave.com/firewall-vpn/") })\n',
    '        card.addView(menuItem("◉", if (isSystemVpnActive()) "VPN · Aktif" else "VPN · Nonaktif") { openVpnControls() })\n',
    "vpn menu real control",
)

panel = replace_once(
    panel,
    '            settingRow("◉", "Brave Firewall + VPN", "Info") { navigate("https://brave.com/firewall-vpn/") },\n',
    '            settingRow("◉", "VPN", if (isSystemVpnActive()) "Aktif" else "Nonaktif") { openVpnControls() },\n'
    '            settingRow("🌐", "Server VPN negara", "Otomatis oleh provider") { openVpnControls() },\n',
    "vpn settings real status",
)

panel = replace_once(
    panel,
    '            settingRow("▱", "Situs desktop", boolState(KEY_DESKTOP_MODE, false)) { togglePref(KEY_DESKTOP_MODE, false); configureWebSettings(); webView.reload(); showSettings() }\n',
    '            settingRow("▱", "Situs desktop", boolState(KEY_DESKTOP_MODE, false)) { togglePref(KEY_DESKTOP_MODE, false); configureWebSettings(); webView.reload(); showSettings() },\n'
    '            settingRow("⛶", "Mode layar penuh", if (browserFullscreen) "Aktif" else "Nonaktif") { hideOverlay(); setBrowserFullscreen(!browserFullscreen) }\n',
    "fullscreen setting",
)

marker = "    private fun showSettings(scrollToAppearance: Boolean = false) {\n"
if marker not in panel:
    raise SystemExit("missing settings marker")

insert = r'''    private fun showQuickSettings() {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(14))
            background = rounded(darkCard, 16f)
        }
        val host = runCatching { Uri.parse(webView.url.orEmpty()).host.orEmpty() }.getOrDefault("")
        wrap.addView(TextView(context).apply {
            text = if (host.isBlank()) "Pengaturan cepat" else "Pengaturan cepat · $host"
            textSize = 17f
            setTextColor(lightText)
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        wrap.addView(settingsGroup(
            settingRow("🛡", "Perisai & privasi", boolState(KEY_SHIELDS_ENABLED, true)) {
                togglePref(KEY_SHIELDS_ENABLED, true)
                applyPrivacySettings()
                webView.reload()
                showQuickSettings()
            },
            settingRow("⌕", "Penelusuran yang aman", safeSearchLabel()) { showSafeSearchChoices() },
            settingRow("🍪", "Blokir cookie pihak ketiga", boolState(KEY_BLOCK_THIRD_PARTY_COOKIES, true)) {
                togglePref(KEY_BLOCK_THIRD_PARTY_COOKIES, true)
                applyPrivacySettings()
                webView.reload()
                showQuickSettings()
            },
            settingRow("JS", "JavaScript", boolState(KEY_JAVASCRIPT, true)) {
                togglePref(KEY_JAVASCRIPT, true)
                configureWebSettings()
                webView.reload()
                showQuickSettings()
            },
            settingRow("▱", "Situs desktop", boolState(KEY_DESKTOP_MODE, false)) {
                togglePref(KEY_DESKTOP_MODE, false)
                configureWebSettings()
                webView.reload()
                showQuickSettings()
            },
            settingRow("📷", "Kamera situs", boolState(KEY_CAMERA_ALLOWED, true)) {
                togglePref(KEY_CAMERA_ALLOWED, true)
                showQuickSettings()
            },
            settingRow("Aa", "Ukuran teks", "${prefs.getInt(KEY_TEXT_ZOOM, 100)}%") {
                val current = prefs.getInt(KEY_TEXT_ZOOM, 100)
                val next = when {
                    current < 100 -> 100
                    current < 120 -> 120
                    else -> 90
                }
                prefs.edit().putInt(KEY_TEXT_ZOOM, next).apply()
                webView.settings.textZoom = next
                showQuickSettings()
            }
        ))
        wrap.addView(actionButton("Semua pengaturan  →") { showSettings() }, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(12)
        })
        showOverlay(wrap, widthRatio = 0.92f, gravity = Gravity.CENTER)
    }

    private fun safeSearchLabel(): String = when (prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE)) {
        SAFE_STRICT -> "Ketat"
        SAFE_OFF -> "Nonaktif"
        else -> "Sedang"
    }

    private fun showSafeSearchChoices() {
        showChoice(
            "Penelusuran yang aman",
            listOf(SAFE_OFF to "Nonaktif", SAFE_MODERATE to "Sedang", SAFE_STRICT to "Ketat"),
            prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE) ?: SAFE_MODERATE
        ) { value ->
            prefs.edit().putString(KEY_SAFE_SEARCH, value).apply()
            reloadCurrentSearchWithSafeSearch()
            showQuickSettings()
        }
    }

    private fun reloadCurrentSearchWithSafeSearch() {
        val uri = runCatching { Uri.parse(webView.url.orEmpty()) }.getOrNull()
        val query = uri?.getQueryParameter("q").orEmpty()
        if (query.isNotBlank()) navigate(query, addHistory = false) else webView.reload()
    }

    private fun setBrowserFullscreen(enabled: Boolean) {
        browserFullscreen = enabled
        hideOverlay()
        if (::topBar.isInitialized) topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        if (::bottomBar.isInitialized) bottomBar.visibility = if (enabled) View.GONE else View.VISIBLE
        if (::browserFullscreenExit.isInitialized) {
            browserFullscreenExit.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) browserFullscreenExit.bringToFront()
        }
        mainColumn.requestLayout()
    }

    private fun showCustomVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customViewHost.visibility == View.VISIBLE) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenBeforeVideo = browserFullscreen
        customViewCallback = callback
        hideOverlay()
        customViewHost.removeAllViews()
        customViewHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        customViewHost.visibility = View.VISIBLE
        mainColumn.visibility = View.GONE
        browserFullscreenExit.visibility = View.VISIBLE
        customViewHost.bringToFront()
        browserFullscreenExit.bringToFront()
    }

    private fun hideCustomVideo() {
        if (customViewHost.visibility != View.VISIBLE) return
        customViewHost.removeAllViews()
        customViewHost.visibility = View.GONE
        mainColumn.visibility = View.VISIBLE
        val callback = customViewCallback
        customViewCallback = null
        runCatching { callback?.onCustomViewHidden() }
        setBrowserFullscreen(fullscreenBeforeVideo)
    }

    private fun restoreTabs() {
        tabs.clear()
        val raw = prefs.getString(KEY_TABS, "[]").orEmpty()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                val title = item.optString("title").trim().ifBlank { "Tab" }
                if (url.isNotBlank()) tabs += BrowserTab(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = title,
                    url = url,
                    privateMode = false
                )
            }
        }
        currentTabIndex = prefs.getInt(KEY_CURRENT_TAB, 0).coerceAtLeast(0)
    }

    private fun persistTabs() {
        if (tabs.isEmpty()) return
        val normalTabs = tabs.filterNot { it.privateMode }
        val array = JSONArray()
        normalTabs.take(MAX_PERSISTED_TABS).forEach { tab ->
            array.put(JSONObject().put("id", tab.id).put("title", tab.title).put("url", tab.url))
        }
        val currentId = tabs.getOrNull(currentTabIndex)?.id
        val persistedIndex = normalTabs.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 }
        prefs.edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_CURRENT_TAB, persistedIndex)
            .apply()
    }

    private fun openNativeAppIfSupported(uri: Uri): Boolean {
        val host = uri.host.orEmpty().lowercase()
        val packages = when {
            host == "shopee.co.id" || host.endsWith(".shopee.co.id") || host == "shopee.com" || host.endsWith(".shopee.com") ->
                listOf("com.shopee.id")
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            else -> emptyList()
        }
        for (pkg in packages) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                return runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    private fun isSystemVpnActive(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    private fun openVpnControls() {
        val active = isSystemVpnActive()
        Toast.makeText(
            context,
            if (active) "VPN sistem sedang aktif." else "Pilih provider VPN nyata di pengaturan Android.",
            Toast.LENGTH_SHORT
        ).show()
        runCatching {
            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        hideOverlay()
    }

'''
panel = panel.replace(marker, insert + marker, 1)

panel = replace_once(
    panel,
    '        private const val KEY_THEME = "browser_theme"\n'
    '        private const val KEY_HISTORY = "browser_history_json"\n',
    '        private const val KEY_THEME = "browser_theme"\n'
    '        private const val KEY_SAFE_SEARCH = "browser_safe_search"\n'
    '        private const val KEY_TABS = "browser_tabs_json"\n'
    '        private const val KEY_CURRENT_TAB = "browser_current_tab"\n'
    '        private const val KEY_HISTORY = "browser_history_json"\n',
    "new browser constants",
)

panel = replace_once(
    panel,
    '        private const val ENGINE_DDG = "ddg"\n'
    '        private const val DEFAULT_HOME = "https://search.brave.com/"\n',
    '        private const val ENGINE_DDG = "ddg"\n'
    '        private const val SAFE_OFF = "off"\n'
    '        private const val SAFE_MODERATE = "moderate"\n'
    '        private const val SAFE_STRICT = "strict"\n'
    '        private const val MAX_PERSISTED_TABS = 24\n'
    '        private const val DEFAULT_HOME = "https://search.brave.com/"\n',
    "safe search constants",
)

service = replace_once(
    service,
    "                searchInput = field\n"
    "                val active = field != null\n"
    "                searchComposeActive = active\n"
    "                searchWebComposeActive = active\n"
    "                aiComposeActive = false\n",
    "                searchInput = field\n"
    "                val active = field != null\n"
    "                searchComposeActive = active\n"
    "                // Native Brave controls are not webpage inputs. Web focus is probed separately.\n"
    "                searchWebComposeActive = false\n"
    "                aiComposeActive = false\n",
    "native vs web input routing",
)

service = replace_once(
    service,
    "        braveBrowserPanel = browser\n"
    "        searchWebView = browser.webView\n"
    "        searchInput = browser.addressField\n"
    "        searchSurfaceContent.addView(browser, FrameLayout.LayoutParams(-1, -1))\n",
    "        braveBrowserPanel = browser\n"
    "        searchWebView = browser.webView\n"
    "        searchInput = browser.addressField\n"
    "        browser.webView.setOnTouchListener { _, event ->\n"
    "            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {\n"
    "                handler.postDelayed({\n"
    "                    if (searchWebView === browser.webView) probeFocusedWebInput()\n"
    "                }, 24L)\n"
    "            }\n"
    "            false\n"
    "        }\n"
    "        browser.webView.setOnFocusChangeListener { _, hasFocus ->\n"
    "            if (hasFocus) {\n"
    "                handler.postDelayed({\n"
    "                    if (searchWebView === browser.webView) probeFocusedWebInput()\n"
    "                }, 24L)\n"
    "            } else {\n"
    "                searchWebComposeActive = false\n"
    "            }\n"
    "        }\n"
    "        searchSurfaceContent.addView(browser, FrameLayout.LayoutParams(-1, -1))\n",
    "probe browser web input focus",
)

panel_path.write_text(panel)
service_path.write_text(service)
print("Applied functional Brave browser fixes.")
