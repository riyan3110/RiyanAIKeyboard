from pathlib import Path
import re

p = Path("app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt")
text = p.read_text()

if "browser_search_country" in text and "private fun showCountryChoices()" in text:
    print("Functional Brave Search quick settings already applied.")
    raise SystemExit(0)

def require_replace(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"patch target missing: {label}")
    text = text.replace(old, new, 1)

old = '''        val encoded = Uri.encode(clean)
        val safe = prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE) ?: SAFE_MODERATE
        return when (prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded&safe=${if (safe == SAFE_OFF) "off" else "active"}"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded&adlt=${when (safe) { SAFE_STRICT -> "strict"; SAFE_OFF -> "off"; else -> "moderate" }}"
            ENGINE_DDG -> "https://duckduckgo.com/?q=$encoded&kp=${if (safe == SAFE_OFF) "-2" else "1"}"
            else -> "https://search.brave.com/search?q=$encoded&source=web&safesearch=$safe"
        }
'''
new = '''        val encoded = Uri.encode(clean)
        val safe = prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE) ?: SAFE_MODERATE
        val country = prefs.getString(KEY_SEARCH_COUNTRY, COUNTRY_ALL) ?: COUNTRY_ALL
        val language = prefs.getString(KEY_SEARCH_LANGUAGE, LANGUAGE_ID) ?: LANGUAGE_ID
        val aiAnswers = prefs.getBoolean(KEY_AI_ANSWERS, true)
        return when (prefs.getString(KEY_SEARCH_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded&safe=${if (safe == SAFE_OFF) "off" else "active"}"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded&adlt=${when (safe) { SAFE_STRICT -> "strict"; SAFE_OFF -> "off"; else -> "moderate" }}"
            ENGINE_DDG -> "https://duckduckgo.com/?q=$encoded&kp=${if (safe == SAFE_OFF) "-2" else "1"}"
            else -> buildString {
                append("https://search.brave.com/search?q=")
                append(encoded)
                append("&source=web&safesearch=")
                append(Uri.encode(safe))
                append("&country=")
                append(Uri.encode(country))
                append("&lang=")
                append(Uri.encode(language))
                if (aiAnswers) append("&summary=1")
            }
        }
'''
require_replace(old, new, "Brave search URL params")

old = '''        val url = toNavigableUrl(raw)
        currentTab().url = url
        if (addHistory && !currentTab().privateMode && prefs.getBoolean(KEY_SAVE_HISTORY, true)) saveHistory(raw.take(80), url)
        webView.loadUrl(url)
'''
new = '''        val url = toNavigableUrl(raw)
        currentTab().url = url
        if (addHistory && !currentTab().privateMode && prefs.getBoolean(KEY_SAVE_HISTORY, true)) saveHistory(raw.take(80), url)
        applyBraveSearchPreferences()
        webView.loadUrl(url)
'''
require_replace(old, new, "navigate applies preferences")

pattern = re.compile(
    r'''    private fun showQuickSettings\(\) \{.*?    private fun reloadCurrentSearchWithSafeSearch\(\) \{.*?\n    \}\n\n''',
    re.S,
)
replacement = r'''    private fun showQuickSettings() {
        onActiveInputChanged(null)

        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = rounded(Color.rgb(247, 247, 248), 18f)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Pengaturan cepat"
            textSize = 18f
            setTextColor(Color.rgb(35, 35, 38))
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 55, 58))
            setOnClickListener { hideOverlay() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        wrap.addView(header)

        wrap.addView(quickSelector(
            "Bahasa tampilan",
            "Bahasa tombol, label, dan antarmuka Brave Search",
            languageLabel()
        ) { showLanguageChoices() })

        wrap.addView(quickSelector(
            "Wilayah hasil",
            "Prioritas negara/wilayah untuk hasil pencarian",
            countryLabel()
        ) { showCountryChoices() })

        wrap.addView(quickSelector(
            "Penelusuran yang aman",
            "Pemfilteran konten eksplisit",
            safeSearchLabel()
        ) { showSafeSearchChoices() })

        wrap.addView(quickToggle(
            "Jawaban dari AI",
            "Minta Brave menampilkan AI Answers otomatis",
            prefs.getBoolean(KEY_AI_ANSWERS, true)
        ) {
            prefs.edit().putBoolean(KEY_AI_ANSWERS, !prefs.getBoolean(KEY_AI_ANSWERS, true)).apply()
            refreshBraveSearchForPreferenceChange()
            showQuickSettings()
        })

        wrap.addView(actionButton("Semua pengaturan  →") { showSettings() }, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(12)
        })
        showOverlay(wrap, widthRatio = 0.96f, gravity = Gravity.CENTER)
    }

    private fun quickSelector(title: String, subtitle: String, value: String, action: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(9))
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 14.5f
            setTextColor(Color.rgb(32, 32, 35))
        })
        row.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.rgb(105, 105, 112))
            setPadding(0, dp(2), 0, dp(6))
        })
        row.addView(TextView(context).apply {
            text = "$value    ⌄"
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(61, 72, 172))
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(Color.rgb(235, 238, 252), 20f)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        return row
    }

    private fun quickToggle(title: String, subtitle: String, enabled: Boolean, action: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { action() }

            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply {
                text = title
                textSize = 14.5f
                setTextColor(Color.rgb(32, 32, 35))
            })
            labels.addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.rgb(105, 105, 112))
            })
            addView(labels, LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(TextView(context).apply {
                text = if (enabled) "●" else "○"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(if (enabled) Color.rgb(61, 72, 172) else Color.rgb(125, 125, 132))
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
    }

    private fun languageChoices(): List<Pair<String, String>> = listOf(
        "id-id" to "Bahasa Indonesia",
        "en-us" to "English (United States)",
        "en-gb" to "English (United Kingdom)",
        "ms-my" to "Bahasa Melayu",
        "es-es" to "Español",
        "fr-fr" to "Français",
        "de-de" to "Deutsch",
        "it-it" to "Italiano",
        "pt-br" to "Português (Brasil)",
        "ja-jp" to "日本語",
        "ko-kr" to "한국어",
        "zh-cn" to "中文（简体）"
    )

    private fun countryChoices(): List<Pair<String, String>> = listOf(
        "all" to "Semua wilayah",
        "us" to "Amerika Serikat",
        "za" to "Afrika Selatan",
        "sa" to "Arab Saudi",
        "ar" to "Argentina",
        "au" to "Australia",
        "at" to "Austria",
        "nl" to "Belanda",
        "be" to "Belgia",
        "br" to "Brasil",
        "cl" to "Chili",
        "dk" to "Denmark",
        "ph" to "Filipina",
        "fi" to "Finlandia",
        "fr" to "Prancis",
        "de" to "Jerman",
        "hk" to "Hong Kong",
        "in" to "India",
        "id" to "Indonesia",
        "ie" to "Irlandia",
        "it" to "Italia",
        "jp" to "Jepang",
        "ca" to "Kanada",
        "kr" to "Korea Selatan",
        "my" to "Malaysia",
        "mx" to "Meksiko",
        "nz" to "Selandia Baru",
        "no" to "Norwegia",
        "pl" to "Polandia",
        "pt" to "Portugal",
        "sg" to "Singapura",
        "es" to "Spanyol",
        "se" to "Swedia",
        "ch" to "Swiss",
        "tw" to "Taiwan",
        "th" to "Thailand",
        "tr" to "Turki",
        "gb" to "Britania Raya",
        "vn" to "Vietnam"
    )

    private fun languageLabel(): String {
        val selected = prefs.getString(KEY_SEARCH_LANGUAGE, LANGUAGE_ID) ?: LANGUAGE_ID
        return languageChoices().firstOrNull { it.first == selected }?.second ?: "Bahasa Indonesia"
    }

    private fun countryLabel(): String {
        val selected = prefs.getString(KEY_SEARCH_COUNTRY, COUNTRY_ALL) ?: COUNTRY_ALL
        return countryChoices().firstOrNull { it.first == selected }?.second ?: "Semua wilayah"
    }

    private fun safeSearchLabel(): String = when (prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE)) {
        SAFE_STRICT -> "Ketat"
        SAFE_OFF -> "Nonaktifkan"
        else -> "Sedang"
    }

    private fun showLanguageChoices() {
        showScrollableChoice(
            "Bahasa tampilan",
            languageChoices(),
            prefs.getString(KEY_SEARCH_LANGUAGE, LANGUAGE_ID) ?: LANGUAGE_ID
        ) { value ->
            prefs.edit().putString(KEY_SEARCH_LANGUAGE, value).apply()
            refreshBraveSearchForPreferenceChange()
            showQuickSettings()
        }
    }

    private fun showCountryChoices() {
        showScrollableChoice(
            "Wilayah hasil",
            countryChoices(),
            prefs.getString(KEY_SEARCH_COUNTRY, COUNTRY_ALL) ?: COUNTRY_ALL
        ) { value ->
            prefs.edit().putString(KEY_SEARCH_COUNTRY, value).apply()
            refreshBraveSearchForPreferenceChange()
            showQuickSettings()
        }
    }

    private fun showSafeSearchChoices() {
        showScrollableChoice(
            "Penelusuran yang aman",
            listOf(
                SAFE_OFF to "Nonaktifkan",
                SAFE_STRICT to "Ketat",
                SAFE_MODERATE to "Sedang"
            ),
            prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE) ?: SAFE_MODERATE
        ) { value ->
            prefs.edit().putString(KEY_SAFE_SEARCH, value).apply()
            refreshBraveSearchForPreferenceChange()
            showQuickSettings()
        }
    }

    private fun showScrollableChoice(
        title: String,
        options: List<Pair<String, String>>,
        selected: String,
        onSelected: (String) -> Unit
    ) {
        onActiveInputChanged(null)
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.rgb(43, 43, 46), 18f)
        }

        shell.addView(overlayHeader(title) { showQuickSettings() })

        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.forEach { (value, label) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                setOnClickListener { onSelected(value) }
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(lightText)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(54), 1f))
            row.addView(TextView(context).apply {
                text = if (value == selected) "◉" else "○"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(if (value == selected) Color.rgb(190, 202, 255) else lightText)
            }, LinearLayout.LayoutParams(dp(48), dp(54)))
            list.addView(row)
            list.addView(separator())
        }
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(shell, widthRatio = 0.94f, gravity = Gravity.CENTER)
    }

    private fun refreshBraveSearchForPreferenceChange() {
        applyBraveSearchPreferences()
        val uri = runCatching { Uri.parse(webView.url.orEmpty()) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val query = uri?.getQueryParameter("q").orEmpty()
        if (host == "search.brave.com" && query.isNotBlank()) {
            navigate(query, addHistory = false)
        } else if (!webView.url.isNullOrBlank()) {
            webView.reload()
        }
    }

'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f"quick settings block replacement count={count}")

needle = '''        body.addView(sectionTitle("Umum"))
'''
insert = '''        body.addView(sectionTitle("Pencarian Brave"))
        body.addView(settingsGroup(
            settingRow("🌐", "Bahasa tampilan", languageLabel()) { showLanguageChoices() },
            settingRow("◎", "Wilayah hasil", countryLabel()) { showCountryChoices() },
            settingRow("⌕", "Penelusuran yang aman", safeSearchLabel()) { showSafeSearchChoices() },
            settingRow("✧", "Jawaban dari AI", boolState(KEY_AI_ANSWERS, true)) {
                prefs.edit().putBoolean(KEY_AI_ANSWERS, !prefs.getBoolean(KEY_AI_ANSWERS, true)).apply()
                refreshBraveSearchForPreferenceChange()
                showSettings()
            },
            settingRow("℃", "Satuan hasil", if ((prefs.getString(KEY_UNITS, UNITS_METRIC) ?: UNITS_METRIC) == UNITS_US) "Imperial" else "Metrik") { showUnitsChoices() },
            settingRow("⌖", "Hasil lokal anonim", boolState(KEY_ANONYMOUS_LOCAL, true)) {
                togglePref(KEY_ANONYMOUS_LOCAL, true)
                applyBraveSearchPreferences()
                refreshBraveSearchForPreferenceChange()
                showSettings()
            },
            settingRow("↗", "Buka link di tab baru", boolState(KEY_OPEN_NEW_TAB, false)) {
                togglePref(KEY_OPEN_NEW_TAB, false)
                applyBraveSearchPreferences()
                showSettings()
            }
        ))

''' + needle
require_replace(needle, insert, "settings Brave section")

needle = '''    private fun showThemeChoices() {
'''
insert = '''    private fun showUnitsChoices() {
        showScrollableChoice(
            "Satuan hasil",
            listOf(
                UNITS_METRIC to "Metrik — kg, meter, celsius",
                UNITS_US to "Imperial — lb, kaki, fahrenheit"
            ),
            prefs.getString(KEY_UNITS, UNITS_METRIC) ?: UNITS_METRIC
        ) { value ->
            prefs.edit().putString(KEY_UNITS, value).apply()
            applyBraveSearchPreferences()
            refreshBraveSearchForPreferenceChange()
            showSettings()
        }
    }

''' + needle
require_replace(needle, insert, "units selector")

needle = '''    private fun shouldBlock(url: String): Boolean {
'''
cookie_helpers = '''    private fun applyBraveSearchPreferences() {
        val manager = CookieManager.getInstance()
        val base = "https://search.brave.com"
        val safe = prefs.getString(KEY_SAFE_SEARCH, SAFE_MODERATE) ?: SAFE_MODERATE
        val country = prefs.getString(KEY_SEARCH_COUNTRY, COUNTRY_ALL) ?: COUNTRY_ALL
        val units = prefs.getString(KEY_UNITS, UNITS_METRIC) ?: UNITS_METRIC
        val localResults = prefs.getBoolean(KEY_ANONYMOUS_LOCAL, true)
        val openNewTab = prefs.getBoolean(KEY_OPEN_NEW_TAB, false)

        setBraveCookie(manager, base, "safesearch", safe)
        setBraveCookie(manager, base, "country", country)
        setBraveCookie(manager, base, "units", units)
        if (localResults) removeBraveCookie(manager, base, "useLocation")
        else setBraveCookie(manager, base, "useLocation", "0")

        if (openNewTab) setBraveCookie(manager, base, "olnt", "1")
        else removeBraveCookie(manager, base, "olnt")

        manager.flush()
    }

    private fun setBraveCookie(manager: CookieManager, base: String, key: String, value: String) {
        manager.setCookie(base, "$key=$value; Path=/; Secure; SameSite=Lax")
    }

    private fun removeBraveCookie(manager: CookieManager, base: String, key: String) {
        manager.setCookie(base, "$key=; Max-Age=0; Path=/; Secure; SameSite=Lax")
    }

''' + needle
require_replace(needle, cookie_helpers, "cookie helpers")

old = '''        prefs.edit().remove(KEY_HISTORY).apply(); trackerCount = 0
        Toast.makeText(context, "Data penjelajahan dibersihkan.", Toast.LENGTH_SHORT).show()
'''
new = '''        prefs.edit().remove(KEY_HISTORY).apply(); trackerCount = 0
        applyBraveSearchPreferences()
        Toast.makeText(context, "Data penjelajahan dibersihkan.", Toast.LENGTH_SHORT).show()
'''
require_replace(old, new, "clear browsing reapplies settings")

old = '''        private const val KEY_SAFE_SEARCH = "browser_safe_search"
        private const val KEY_TABS = "browser_tabs_json"
'''
new = '''        private const val KEY_SAFE_SEARCH = "browser_safe_search"
        private const val KEY_SEARCH_LANGUAGE = "browser_search_language"
        private const val KEY_SEARCH_COUNTRY = "browser_search_country"
        private const val KEY_AI_ANSWERS = "browser_ai_answers"
        private const val KEY_UNITS = "browser_units"
        private const val KEY_ANONYMOUS_LOCAL = "browser_anonymous_local"
        private const val KEY_OPEN_NEW_TAB = "browser_open_links_new_tab"
        private const val KEY_TABS = "browser_tabs_json"
'''
require_replace(old, new, "new setting keys")

old = '''        private const val SAFE_STRICT = "strict"
        private const val MAX_PERSISTED_TABS = 24
'''
new = '''        private const val SAFE_STRICT = "strict"
        private const val LANGUAGE_ID = "id-id"
        private const val COUNTRY_ALL = "all"
        private const val UNITS_METRIC = "metric"
        private const val UNITS_US = "us"
        private const val MAX_PERSISTED_TABS = 24
'''
require_replace(old, new, "setting values")

p.write_text(text)
print("Applied functional Brave Search quick settings.")
