package com.riyan.aikeyboard

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Brave-like browser shell for the keyboard search surface.
 *
 * Pages are rendered by Android System WebView. The chrome/menu/settings follow the
 * current Brave Android 1.94.x layout language while Brave Search is the default
 * provider. We keep the real WebView user-agent instead of pretending the renderer
 * itself is Brave/Chromium.
 */
class BraveBrowserPanel(
    context: Context,
    private val prefs: SharedPreferences,
    initialQuery: String,
    initialUrl: String,
    private val onClose: () -> Unit,
    private val onOpenScanner: () -> Unit,
    private val onOpenKeyboardAi: () -> Unit,
    private val onActiveInputChanged: (EditText?) -> Unit
) : FrameLayout(context) {

    data class BrowserTab(
        val id: String = UUID.randomUUID().toString(),
        var title: String = "Tab baru",
        var url: String = "",
        val privateMode: Boolean = false
    )

    lateinit var addressField: EditText
        private set
    lateinit var webView: WebView
        private set

    private val mainColumn = LinearLayout(context)
    private val overlayHost = FrameLayout(context)
    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private var trackerCount = 0

    private val darkBg = Color.rgb(31, 31, 33)
    private val darkBar = Color.rgb(46, 46, 48)
    private val darkCard = Color.rgb(55, 55, 58)
    private val darkField = Color.rgb(63, 63, 65)
    private val lightText = Color.rgb(235, 235, 240)
    private val mutedText = Color.rgb(184, 184, 191)

    private val trackerMarkers = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "adservice.google.",
        "facebook.net/tr",
        "connect.facebook.net",
        "app-measurement.com",
        "scorecardresearch.com",
        "taboola.com",
        "outbrain.com"
    )

    init {
        setBackgroundColor(darkBg)
        clipChildren = false
        clipToPadding = false
        buildBrowser()
        tabs += BrowserTab(title = "Brave", url = initialUrl.trim(), privateMode = false)
        updateTabCounter()
        when {
            initialUrl.isNotBlank() -> navigate(initialUrl, addHistory = false)
            initialQuery.isNotBlank() -> navigate(initialQuery, addHistory = false)
            else -> openHome()
        }
    }

    fun release() {
        onActiveInputChanged(null)
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
        overlayHost.removeAllViews()
    }

    private fun buildBrowser() {
        removeAllViews()
        mainColumn.orientation = LinearLayout.VERTICAL
        mainColumn.setBackgroundColor(darkBg)
        addView(mainColumn, LayoutParams(-1, -1))

        mainColumn.addView(buildTopBar(), LinearLayout.LayoutParams(-1, dp(54)))

        webView = WebView(context).apply {
            setBackgroundColor(Color.rgb(248, 248, 248))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    if (scheme == "http" || scheme == "https") return false
                    return runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(true)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    if (shouldBlock(url)) {
                        post { trackerCount += 1 }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val clean = url.orEmpty()
                    if (clean.isNotBlank()) {
                        addressField.setText(clean)
                        addressField.setSelection(addressField.text?.length ?: 0)
                        currentTab().url = clean
                        if (!currentTab().privateMode && prefs.getBoolean(KEY_SAVE_HISTORY, true)) {
                            saveHistory(view?.title.orEmpty(), clean)
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val value = title?.trim().orEmpty()
                    if (value.isNotBlank()) currentTab().title = value.take(80)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request ?: return
                    post {
                        val cameraRequested = request.resources?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true
                        val cameraAllowed = prefs.getBoolean(KEY_CAMERA_ALLOWED, true)
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (cameraRequested && (!cameraAllowed || !granted)) {
                            request.deny()
                            if (!granted) {
                                Toast.makeText(context, "Aktifkan izin kamera AI Ads Keyboard agar kamera situs dapat dipakai.", Toast.LENGTH_SHORT).show()
                            }
                            return@post
                        }
                        request.grant(request.resources)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback ?: return false
                    return WebImagePickerActivity.launch(
                        context = context,
                        callback = filePathCallback,
                        acceptTypes = fileChooserParams?.acceptTypes
                    )
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
        configureWebSettings()
        mainColumn.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        mainColumn.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, dp(46)))

        overlayHost.visibility = View.GONE
        overlayHost.setBackgroundColor(Color.argb(88, 0, 0, 0))
        addView(overlayHost, LayoutParams(-1, -1))
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setBackgroundColor(darkBar)
        }
        row.addView(TextView(context).apply {
            text = "🦁"
            textSize = 19f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(38), -1))

        addressField = EditText(context).apply {
            setSingleLine(true)
            textSize = 14.5f
            hint = "Telusuri Brave atau ketik URL"
            setHintTextColor(mutedText)
            setTextColor(lightText)
            setPadding(dp(12), 0, dp(10), 0)
            background = rounded(darkField, 12f)
            selectAllOnFocus = false
            showSoftInputOnFocus = false
            setOnFocusChangeListener { view, focused ->
                onActiveInputChanged(if (focused) view as EditText else null)
            }
            setOnEditorActionListener { _, _, _ -> submitAddress(); true }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_ENTER) {
                    submitAddress(); true
                } else false
            }
        }
        row.addView(addressField, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(5)
        })
        row.addView(iconButton("🛡") {
            val enabled = !prefs.getBoolean(KEY_SHIELDS_ENABLED, true)
            prefs.edit().putBoolean(KEY_SHIELDS_ENABLED, enabled).apply()
            applyPrivacySettings()
            Toast.makeText(context, if (enabled) "Perisai Brave aktif" else "Perisai Brave nonaktif", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(42), -1))
        row.addView(iconButton("⋮") { showMainMenu() }, LinearLayout.LayoutParams(dp(42), -1))
        return row
    }

    private fun buildBottomBar(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(3), dp(5), dp(3))
            setBackgroundColor(darkBar)
        }
        row.addView(bottomButton("⌂") { openHome() })
        row.addView(bottomButton("‹") { if (webView.canGoBack()) webView.goBack() })
        row.addView(bottomButton("›") { if (webView.canGoForward()) webView.goForward() })
        row.addView(bottomButton("☆") { toggleBookmark() })
        row.addView(bottomButton("▣") { showTabs() }.also { it.tag = TAB_COUNTER_TAG })
        row.addView(bottomButton("↻") { webView.reload() })
        return row
    }

    private fun bottomButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
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

    private fun submitAddress() {
        val text = addressField.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        onActiveInputChanged(null)
        addressField.clearFocus()
        navigate(text)
    }

    fun navigate(raw: String, addHistory: Boolean = true) {
        hideOverlay()
        val url = toNavigableUrl(raw)
        currentTab().url = url
        if (addHistory && !currentTab().privateMode && prefs.getBoolean(KEY_SAVE_HISTORY, true)) saveHistory(raw.take(80), url)
        webView.loadUrl(url)
    }

    private fun openHome() {
        val home = prefs.getString(KEY_HOMEPAGE, DEFAULT_HOME).orEmpty().ifBlank { DEFAULT_HOME }
        navigate(home, addHistory = false)
    }

    private fun toNavigableUrl(raw: String): String {
        val clean = raw.trim()
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        if (looksLikeDomain(clean)) return "https://$clean"
        val encoded = Uri.encode(clean)
        return when (prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded"
            ENGINE_DDG -> "https://duckduckgo.com/?q=$encoded"
            else -> "https://search.brave.com/search?q=$encoded&source=web"
        }
    }

    private fun looksLikeDomain(value: String): Boolean {
        if (value.contains(" ")) return false
        val host = value.substringBefore('/').substringBefore(':')
        return host.contains('.') && host.length >= 4
    }

    private fun currentTab(): BrowserTab = tabs[currentTabIndex.coerceIn(0, tabs.lastIndex)]

    private fun newTab(privateMode: Boolean) {
        tabs += BrowserTab(title = if (privateMode) "Tab Privat" else "Tab baru", privateMode = privateMode)
        currentTabIndex = tabs.lastIndex
        trackerCount = 0
        updateTabCounter()
        openHome()
        hideOverlay()
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index
        trackerCount = 0
        updateTabCounter()
        val url = currentTab().url
        if (url.isBlank()) openHome() else webView.loadUrl(url)
        hideOverlay()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs += BrowserTab()
        currentTabIndex = currentTabIndex.coerceAtMost(tabs.lastIndex)
        updateTabCounter()
        val url = currentTab().url
        if (url.isBlank()) openHome() else webView.loadUrl(url)
        showTabs()
    }

    private fun updateTabCounter() {
        findViewWithTag<TextView>(TAB_COUNTER_TAG)?.text = "▣${tabs.size}"
    }

    private fun showMainMenu() {
        val scroll = ScrollView(context)
        val card = menuCard()
        scroll.addView(card, ViewGroup.LayoutParams(-1, -2))

        val privacy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(67, 67, 70), 12f)
        }
        privacy.addView(TextView(context).apply {
            text = "🛡  Status Privasi"
            textSize = 14.5f
            setTextColor(lightText)
        })
        privacy.addView(TextView(context).apply {
            text = "$trackerCount pelacak & iklan diblokir"
            textSize = 12.5f
            setTextColor(Color.rgb(255, 129, 91))
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(privacy, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })

        card.addView(menuItem("📷", "Kamera Penelusuran") { hideOverlay(); onOpenScanner() })
        card.addView(menuItem("⊞", "Tab baru") { newTab(false) })
        card.addView(menuItem("🕶", "Tab Privat baru") { newTab(true) })
        card.addView(menuItem("▦", "Tambahkan tab ke grup baru") {
            Toast.makeText(context, "Grup tab baru dibuat.", Toast.LENGTH_SHORT).show(); hideOverlay()
        })
        card.addView(separator())
        card.addView(menuItem("◴", "Histori") { showHistory() })
        card.addView(menuItem("⇩", "Download") { openDownloads() })
        card.addView(menuItem("▣", "Dompet") { navigate("https://brave.com/wallet/") })
        card.addView(menuItem("🔖", "Bookmark") { showBookmarks() })
        card.addView(menuItem("✧", "AI Leo") { hideOverlay(); onOpenKeyboardAi() })
        card.addView(menuItem("▣", "Tab terbaru") { showTabs() })
        card.addView(separator())
        card.addView(menuItem("⚙", "Setelan") { showSettings() })
        card.addView(menuItem("★", "Tetapkan Brave sebagai Peramban") { openDefaultAppsSettings() })
        card.addView(menuItem("△", "Brave Rewards") { navigate("https://brave.com/rewards/") })
        card.addView(menuItem("▤", "Brave News") { navigate("https://search.brave.com/news?q=berita") })
        card.addView(menuItem("◉", "VPN Brave") { navigate("https://brave.com/firewall-vpn/") })
        card.addView(menuItem("🛠", "Sesuaikan menu") { showSettings(scrollToAppearance = true) })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(12))
        }
        bottom.addView(circleAction("→") { if (webView.canGoForward()) webView.goForward() })
        bottom.addView(circleAction("↗") { shareCurrentPage() })
        bottom.addView(circleAction("⇩") { openDownloads() })
        bottom.addView(circleAction("↻") { webView.reload(); hideOverlay() })
        card.addView(bottom, LinearLayout.LayoutParams(-1, dp(62)))
        showOverlay(scroll, widthRatio = 0.66f, gravity = Gravity.END)
    }

    private fun showSettings(scrollToAppearance: Boolean = false) {
        val shell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(41, 41, 43)) }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(5)) }
        header.addView(iconButton("‹") { showMainMenu() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        header.addView(TextView(context).apply { text = "Setelan"; textSize = 20f; setTextColor(lightText); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(iconButton("×") { hideOverlay() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        header.addView(iconButton("⋮") { showMainMenu() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        shell.addView(header, LinearLayout.LayoutParams(-1, dp(54)))

        val search = EditText(context).apply {
            hint = "Telusuri setelan"; setHintTextColor(mutedText); setTextColor(lightText); setSingleLine(true); textSize = 14f
            setPadding(dp(14), 0, dp(14), 0); background = rounded(darkField, 18f); showSoftInputOnFocus = false
            setOnFocusChangeListener { view, focused -> onActiveInputChanged(if (focused) view as EditText else null) }
        }
        shell.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(14), dp(4), dp(14), dp(8)) })

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(20)) }

        body.addView(sectionTitle("Fitur"))
        body.addView(settingsGroup(
            settingRow("🛡", "Perisai & privasi Brave", boolState(KEY_SHIELDS_ENABLED, true)) { togglePref(KEY_SHIELDS_ENABLED, true); applyPrivacySettings(); showSettings() },
            settingRow("▤", "Brave News", "Buka") { navigate("https://search.brave.com/news?q=berita") },
            settingRow("▣", "Dompet Brave", "Buka") { navigate("https://brave.com/wallet/") },
            settingRow("◉", "Brave Firewall + VPN", "Info") { navigate("https://brave.com/firewall-vpn/") },
            settingRow("✧", "AI Leo", "AI keyboard") { hideOverlay(); onOpenKeyboardAi() }
        ))

        body.addView(sectionTitle("Umum"))
        body.addView(settingsGroup(
            settingRow("⌕", "Mesin pencari", searchEngineLabel()) { showSearchEngineChoices() },
            settingRow("⌂", "Beranda Aktif", prefs.getString(KEY_HOMEPAGE, DEFAULT_HOME).orEmpty()) {
                showTextSetting("Beranda Aktif", prefs.getString(KEY_HOMEPAGE, DEFAULT_HOME).orEmpty(), "https://search.brave.com/") { value ->
                    prefs.edit().putString(KEY_HOMEPAGE, value.trim().ifBlank { DEFAULT_HOME }).apply(); showSettings()
                }
            },
            settingRow("▦", "Tab dan grup tab", "${tabs.size} tab") { showTabs() },
            settingRow("◉", "Sync", "Lokal") { Toast.makeText(context, "Sync akun Brave asli tidak tersedia di WebView tertanam.", Toast.LENGTH_LONG).show() },
            settingRow("▥", "Pelaporan Privasi", "Nonaktif") { Toast.makeText(context, "Pelaporan privasi tetap lokal.", Toast.LENGTH_SHORT).show() },
            settingRow("🔔", "Notifikasi", boolState(KEY_NOTIFICATIONS, false)) { togglePref(KEY_NOTIFICATIONS, false); showSettings() },
            settingRow("☷", "Setelan situs", "Kamera, JS, cookie") { showSiteSettings() },
            settingRow("⇩", "Download", "Folder aplikasi") { openDownloads() },
            settingRow("▣", "Menutup semua tab akan menutup browser", boolState(KEY_CLOSE_TABS_ON_CLOSE, false)) { togglePref(KEY_CLOSE_TABS_ON_CLOSE, false); showSettings() }
        ))

        body.addView(sectionTitle("Tampilan"))
        body.addView(settingsGroup(
            settingRow("◫", "Mode tampilan", prefs.getString(KEY_THEME, "Gelap") ?: "Gelap") { showThemeChoices() },
            settingRow("Aa", "Ukuran teks halaman", "${prefs.getInt(KEY_TEXT_ZOOM, 100)}%") { showTextZoomSetting() },
            settingRow("▱", "Situs desktop", boolState(KEY_DESKTOP_MODE, false)) { togglePref(KEY_DESKTOP_MODE, false); configureWebSettings(); webView.reload(); showSettings() }
        ))

        body.addView(sectionTitle("Privasi"))
        body.addView(settingsGroup(
            settingRow("🛡", "Blokir pelacak & iklan", boolState(KEY_SHIELDS_ENABLED, true)) { togglePref(KEY_SHIELDS_ENABLED, true); applyPrivacySettings(); showSettings() },
            settingRow("🍪", "Blokir cookie pihak ketiga", boolState(KEY_BLOCK_THIRD_PARTY_COOKIES, true)) { togglePref(KEY_BLOCK_THIRD_PARTY_COOKIES, true); applyPrivacySettings(); showSettings() },
            settingRow("◴", "Simpan histori", boolState(KEY_SAVE_HISTORY, true)) { togglePref(KEY_SAVE_HISTORY, true); showSettings() },
            settingRow("⌫", "Hapus data penjelajahan", "Cache, cookie, histori") { clearBrowsingData(); showSettings() }
        ))

        body.addView(TextView(context).apply {
            val webViewVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "tidak diketahui"
            text = "Tampilan mengikuti Brave Android 1.94.x • renderer aktual Android System WebView $webViewVersion"
            textSize = 10.5f; setTextColor(Color.rgb(145, 145, 151)); setPadding(dp(8), dp(16), dp(8), dp(10))
        })

        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(shell, widthRatio = 1f, gravity = Gravity.CENTER)
        if (scrollToAppearance) scroll.post { scroll.smoothScrollTo(0, body.height / 2) }
    }

    private fun showSiteSettings() {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(18)); setBackgroundColor(Color.rgb(41, 41, 43)) }
        body.addView(overlayHeader("Setelan situs") { showSettings() })
        body.addView(settingsGroup(
            settingRow("📷", "Kamera", boolState(KEY_CAMERA_ALLOWED, true)) { togglePref(KEY_CAMERA_ALLOWED, true); showSiteSettings() },
            settingRow("JS", "JavaScript", boolState(KEY_JAVASCRIPT, true)) { togglePref(KEY_JAVASCRIPT, true); configureWebSettings(); showSiteSettings() },
            settingRow("🍪", "Cookie", boolState(KEY_COOKIES, true)) { togglePref(KEY_COOKIES, true); applyPrivacySettings(); showSiteSettings() },
            settingRow("□", "Izinkan pop-up", boolState(KEY_POPUPS, false)) { togglePref(KEY_POPUPS, false); configureWebSettings(); showSiteSettings() }
        ))
        showOverlay(body, widthRatio = 1f, gravity = Gravity.CENTER)
    }

    private fun showSearchEngineChoices() {
        showChoice("Mesin pencari", listOf(ENGINE_BRAVE to "Brave Search", ENGINE_GOOGLE to "Google", ENGINE_BING to "Microsoft Bing", ENGINE_DDG to "DuckDuckGo"), prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE) { value ->
            prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply(); showSettings()
        }
    }

    private fun showThemeChoices() {
        showChoice("Mode tampilan", listOf("Gelap" to "Gelap", "Terang" to "Terang"), prefs.getString(KEY_THEME, "Gelap") ?: "Gelap") { value ->
            prefs.edit().putString(KEY_THEME, value).apply()
            Toast.makeText(context, "Tampilan diterapkan saat panel browser dibuka ulang.", Toast.LENGTH_SHORT).show()
            showSettings()
        }
    }

    private fun showTextZoomSetting() {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); background = rounded(darkCard, 16f) }
        wrap.addView(TextView(context).apply { text = "Ukuran teks halaman"; textSize = 17f; setTextColor(lightText) })
        val value = TextView(context).apply { text = "${prefs.getInt(KEY_TEXT_ZOOM, 100)}%"; textSize = 14f; setTextColor(mutedText); setPadding(0, dp(8), 0, dp(8)) }
        val seek = SeekBar(context).apply {
            max = 100; progress = (prefs.getInt(KEY_TEXT_ZOOM, 100) - 50).coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val zoom = progress + 50; value.text = "$zoom%"
                    if (fromUser) { prefs.edit().putInt(KEY_TEXT_ZOOM, zoom).apply(); webView.settings.textZoom = zoom }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        wrap.addView(value); wrap.addView(seek)
        wrap.addView(actionButton("Selesai") { showSettings() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        showOverlay(wrap, widthRatio = 0.82f, gravity = Gravity.CENTER)
    }

    private fun showTextSetting(title: String, current: String, hint: String, onSave: (String) -> Unit) {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = rounded(darkCard, 16f) }
        wrap.addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(lightText) })
        val input = EditText(context).apply {
            setSingleLine(true); setText(current); this.hint = hint; setTextColor(lightText); setHintTextColor(mutedText); textSize = 13f
            setPadding(dp(10), 0, dp(10), 0); background = rounded(darkField, 10f); showSoftInputOnFocus = false
            setOnFocusChangeListener { view, focused -> onActiveInputChanged(if (focused) view as EditText else null) }
        }
        wrap.addView(input, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(actionButton("Batal") { onActiveInputChanged(null); showSettings() }, LinearLayout.LayoutParams(dp(78), dp(42)))
        actions.addView(actionButton("Simpan") { onActiveInputChanged(null); onSave(input.text?.toString().orEmpty()) }, LinearLayout.LayoutParams(dp(86), dp(42)).apply { leftMargin = dp(8) })
        wrap.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        showOverlay(wrap, widthRatio = 0.88f, gravity = Gravity.CENTER)
        input.requestFocus(); onActiveInputChanged(input)
    }

    private fun showChoice(title: String, options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14)); background = rounded(darkCard, 16f) }
        wrap.addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(lightText); setPadding(dp(8), dp(4), dp(8), dp(8)) })
        options.forEach { (value, label) ->
            wrap.addView(TextView(context).apply {
                text = if (value == selected) "✓  $label" else "     $label"; textSize = 14.5f; setTextColor(lightText); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0); setOnClickListener { onSelected(value) }
            }, LinearLayout.LayoutParams(-1, dp(46)))
        }
        showOverlay(wrap, widthRatio = 0.82f, gravity = Gravity.CENTER)
    }

    private fun showTabs() {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(10)); background = rounded(darkCard, 15f) }
        wrap.addView(overlayHeader("Tab (${tabs.size})") { hideOverlay() })
        tabs.forEachIndexed { index, tab ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(6), dp(4))
                background = rounded(if (index == currentTabIndex) Color.rgb(68, 61, 83) else darkField, 10f); setOnClickListener { switchTab(index) }
            }
            row.addView(TextView(context).apply { text = (if (tab.privateMode) "🕶  " else "▣  ") + tab.title; textSize = 13f; setTextColor(lightText); maxLines = 1 }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(iconButton("×") { closeTab(index) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            wrap.addView(row, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(5) })
        }
        wrap.addView(actionButton("+ Tab baru") { newTab(false) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        showOverlay(wrap, widthRatio = 0.92f, gravity = Gravity.CENTER)
    }

    private fun showHistory() = showEntryList("Histori", loadEntries(KEY_HISTORY), "Belum ada histori")
    private fun showBookmarks() = showEntryList("Bookmark", loadEntries(KEY_BOOKMARKS), "Belum ada bookmark")

    private fun showEntryList(title: String, items: List<Pair<String, String>>, emptyText: String) {
        val scroll = ScrollView(context)
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(12)); background = darkBg }
        wrap.addView(overlayHeader(title) { showMainMenu() })
        if (items.isEmpty()) {
            wrap.addView(TextView(context).apply { text = emptyText; textSize = 14f; gravity = Gravity.CENTER; setTextColor(mutedText) }, LinearLayout.LayoutParams(-1, dp(120)))
        } else items.forEach { (label, url) -> wrap.addView(menuItem("•", label.ifBlank { url }, url) { navigate(url) }) }
        scroll.addView(wrap, ViewGroup.LayoutParams(-1, -2)); showOverlay(scroll, widthRatio = 1f, gravity = Gravity.CENTER)
    }

    private fun toggleBookmark() {
        val url = webView.url.orEmpty(); if (url.isBlank()) return
        val title = webView.title.orEmpty().ifBlank { url }
        val current = loadEntries(KEY_BOOKMARKS).toMutableList(); val exists = current.any { it.second == url }
        val updated = if (exists) current.filterNot { it.second == url } else listOf(title to url) + current
        saveEntries(KEY_BOOKMARKS, updated.take(80))
        Toast.makeText(context, if (exists) "Bookmark dihapus" else "Bookmark ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun saveHistory(title: String, url: String) {
        if (url.startsWith("about:")) return
        val current = loadEntries(KEY_HISTORY).filterNot { it.second == url }
        saveEntries(KEY_HISTORY, (listOf(title.ifBlank { url } to url) + current).take(120))
    }

    private fun loadEntries(key: String): List<Pair<String, String>> {
        val raw = prefs.getString(key, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url"); if (url.isBlank()) continue
                    add(obj.optString("title") to url)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(key: String, entries: List<Pair<String, String>>) {
        val array = JSONArray(); entries.forEach { (title, url) -> array.put(JSONObject().put("title", title).put("url", url)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun shareCurrentPage() {
        val url = webView.url.orEmpty(); if (url.isBlank()) return
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }, "Bagikan tautan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hideOverlay()
    }

    private fun openDownloads() {
        runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Daftar download Android tidak tersedia.", Toast.LENGTH_SHORT).show() }
        hideOverlay()
    }

    private fun enqueueDownload(url: String?, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val safeUrl = url.orEmpty(); if (!safeUrl.startsWith("http")) return
        runCatching {
            val fileName = URLUtil.guessFileName(safeUrl, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(safeUrl)).setTitle(fileName).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(context, "Download dimulai.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun openDefaultAppsSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        hideOverlay()
    }

    private fun clearBrowsingData() {
        runCatching { webView.clearCache(true); webView.clearHistory(); CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
        prefs.edit().remove(KEY_HISTORY).apply(); trackerCount = 0
        Toast.makeText(context, "Data penjelajahan dibersihkan.", Toast.LENGTH_SHORT).show()
    }

    private fun configureWebSettings() {
        if (!::webView.isInitialized) return
        webView.settings.apply {
            javaScriptEnabled = prefs.getBoolean(KEY_JAVASCRIPT, true)
            domStorageEnabled = true; databaseEnabled = true; allowFileAccess = false; allowContentAccess = true
            builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true); mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = prefs.getInt(KEY_TEXT_ZOOM, 100).coerceIn(50, 150)
            javaScriptCanOpenWindowsAutomatically = prefs.getBoolean(KEY_POPUPS, false)
            setSupportMultipleWindows(prefs.getBoolean(KEY_POPUPS, false))
            userAgentString = if (prefs.getBoolean(KEY_DESKTOP_MODE, false)) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
            } else WebSettings.getDefaultUserAgent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        applyPrivacySettings()
    }

    private fun applyPrivacySettings() {
        if (!::webView.isInitialized) return
        val cookies = prefs.getBoolean(KEY_COOKIES, true)
        CookieManager.getInstance().apply {
            setAcceptCookie(cookies)
            setAcceptThirdPartyCookies(webView, cookies && !prefs.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, true))
        }
    }

    private fun shouldBlock(url: String): Boolean {
        if (!prefs.getBoolean(KEY_SHIELDS_ENABLED, true)) return false
        val lower = url.lowercase(); return trackerMarkers.any(lower::contains)
    }

    private fun boolState(key: String, default: Boolean): String = if (prefs.getBoolean(key, default)) "Aktif" else "Nonaktif"
    private fun togglePref(key: String, default: Boolean) { prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply() }
    private fun searchEngineLabel(): String = when (prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE)) {
        ENGINE_GOOGLE -> "Google"; ENGINE_BING -> "Microsoft Bing"; ENGINE_DDG -> "DuckDuckGo"; else -> "Brave Search"
    }

    private fun sectionTitle(text: String): TextView = TextView(context).apply { this.text = text; textSize = 12f; setTextColor(Color.rgb(201, 196, 211)); setPadding(dp(12), dp(16), dp(8), dp(8)) }

    private fun settingsGroup(vararg rows: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(darkCard, 14f)
        rows.forEachIndexed { index, view -> addView(view, LinearLayout.LayoutParams(-1, dp(58))); if (index != rows.lastIndex) addView(separator(), LinearLayout.LayoutParams(-1, 1)) }
    }

    private fun settingRow(icon: String, title: String, value: String, action: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(10), 0); setOnClickListener { action() }
        addView(TextView(context).apply { text = icon; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.rgb(210, 210, 218)) }, LinearLayout.LayoutParams(dp(38), -1))
        addView(TextView(context).apply { text = title; textSize = 14f; setTextColor(lightText); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(TextView(context).apply { text = value; textSize = 11f; setTextColor(mutedText); gravity = Gravity.CENTER_VERTICAL or Gravity.END; maxLines = 2 }, LinearLayout.LayoutParams(dp(130), -1))
    }

    private fun menuCard(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(8)); background = rounded(Color.rgb(58, 58, 61), 18f) }

    private fun menuItem(icon: String, title: String, subtitle: String = "", action: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(8), 0); setOnClickListener { action() }
        addView(TextView(context).apply { text = icon; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.rgb(219, 218, 224)) }, LinearLayout.LayoutParams(dp(42), dp(50)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(TextView(context).apply { text = title; textSize = 14.5f; setTextColor(lightText); maxLines = 1 })
        if (subtitle.isNotBlank()) texts.addView(TextView(context).apply { text = subtitle; textSize = 10f; setTextColor(mutedText); maxLines = 1 })
        addView(texts, LinearLayout.LayoutParams(0, dp(50), 1f))
    }

    private fun overlayHeader(title: String, onBack: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(iconButton("‹") { onBack() }, LinearLayout.LayoutParams(dp(42), dp(46)))
        addView(TextView(context).apply { text = title; textSize = 18f; setTextColor(lightText); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(iconButton("×") { hideOverlay() }, LinearLayout.LayoutParams(dp(42), dp(46)))
    }

    private fun separator(): View = View(context).apply { setBackgroundColor(Color.rgb(76, 76, 80)) }
    private fun circleAction(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(lightText); background = rounded(Color.rgb(74, 74, 78), 22f); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    }
    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply { text = label; textSize = 12f; setTextColor(lightText); background = rounded(Color.rgb(76, 70, 92), 10f); setOnClickListener { action() }; isAllCaps = false }

    private fun showOverlay(content: View, widthRatio: Float, gravity: Int) {
        overlayHost.removeAllViews(); overlayHost.visibility = View.VISIBLE; overlayHost.setOnClickListener { hideOverlay() }
        val params = if (widthRatio >= 0.99f) LayoutParams(-1, -1, Gravity.CENTER) else LayoutParams(0, -1, gravity).apply {
            val baseWidth = this@BraveBrowserPanel.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            width = (baseWidth * widthRatio).toInt(); topMargin = dp(2); bottomMargin = dp(2)
        }
        content.setOnClickListener { }
        overlayHost.addView(content, params); overlayHost.bringToFront()
    }

    private fun hideOverlay() { onActiveInputChanged(null); overlayHost.removeAllViews(); overlayHost.visibility = View.GONE }
    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp.toInt()).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_COUNTER_TAG = "brave_tab_counter"
        private const val KEY_SEARCH_ENGINE = "browser_search_engine"
        private const val KEY_HOMEPAGE = "browser_homepage"
        private const val KEY_SHIELDS_ENABLED = "browser_shields_enabled"
        private const val KEY_BLOCK_THIRD_PARTY_COOKIES = "browser_block_3p_cookies"
        private const val KEY_COOKIES = "browser_cookies"
        private const val KEY_JAVASCRIPT = "browser_javascript"
        private const val KEY_CAMERA_ALLOWED = "browser_camera_allowed"
        private const val KEY_POPUPS = "browser_popups"
        private const val KEY_DESKTOP_MODE = "browser_desktop_mode"
        private const val KEY_SAVE_HISTORY = "browser_save_history"
        private const val KEY_CLOSE_TABS_ON_CLOSE = "browser_close_tabs_on_close"
        private const val KEY_NOTIFICATIONS = "browser_notifications"
        private const val KEY_TEXT_ZOOM = "browser_text_zoom"
        private const val KEY_THEME = "browser_theme"
        private const val KEY_HISTORY = "browser_history_json"
        private const val KEY_BOOKMARKS = "browser_bookmarks_json"
        private const val ENGINE_BRAVE = "brave"
        private const val ENGINE_GOOGLE = "google"
        private const val ENGINE_BING = "bing"
        private const val ENGINE_DDG = "ddg"
        private const val DEFAULT_HOME = "https://search.brave.com/"
    }
}
