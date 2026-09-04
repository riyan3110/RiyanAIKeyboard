from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt"
SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
THEME = ROOT / "app/src/main/java/com/riyan/aikeyboard/KeyboardTheme.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"marker not found: {label}")
    return text.replace(old, new, 1)


def replace_block(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f"start marker not found: {label}")
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError(f"end marker not found: {label}")
    return text[:start] + replacement + text[end:]


def patch_overlay() -> None:
    text = OVERLAY.read_text()
    text = replace_once(
        text,
        "import android.content.Context\nimport android.content.SharedPreferences\nimport android.graphics.Color\nimport android.graphics.Typeface\nimport android.graphics.drawable.GradientDrawable\nimport android.text.Editable\n",
        "import android.content.Context\nimport android.content.Intent\nimport android.content.SharedPreferences\nimport android.graphics.Color\nimport android.graphics.Typeface\nimport android.graphics.drawable.GradientDrawable\nimport android.text.Editable\nimport android.text.SpannableString\nimport android.text.Spanned\nimport android.text.style.ForegroundColorSpan\n",
        "overlay imports",
    )
    text = replace_once(
        text,
        "    private val prefs: SharedPreferences,\n    private val onApply: () -> Unit,\n    private val onClose: () -> Unit\n) : FrameLayout(context) {",
        "    private val prefs: SharedPreferences,\n    private val onApply: () -> Unit,\n    private val onClose: () -> Unit,\n    private val onInputFocusChanged: (Boolean) -> Unit = {}\n) : FrameLayout(context) {",
        "overlay constructor",
    )
    text = replace_once(
        text,
        "    fun hidePanel() {\n        activeInput?.clearFocus()\n        activeInput = null\n        visibility = View.GONE\n        onClose()\n    }\n",
        "    fun hidePanel() {\n        activeInput?.clearFocus()\n        activeInput = null\n        onInputFocusChanged(false)\n        visibility = View.GONE\n        onClose()\n    }\n\n    fun refreshExternalChanges() {\n        val currentMode = prefs.getString(\"keyboard_theme_mode\", draft.themeMode).orEmpty()\n        if (currentMode.isNotBlank()) draft.themeMode = currentMode\n        if (currentTab == Tab.THEME && visibility == View.VISIBLE) renderBody()\n    }\n",
        "external theme refresh",
    )
    text = text.replace('text = "⚙  Pengaturan AI Ads Keyboard"\n            textSize = 18f', 'text = "⚙  Pengaturan AI Ads Keyboard"\n            textSize = 16.5f')
    text = text.replace('textSize = 11.5f\n                gravity = Gravity.CENTER\n                setTextColor(if (selected)', 'textSize = 10.2f\n                gravity = Gravity.CENTER\n                maxLines = 1\n                ellipsize = android.text.TextUtils.TruncateAt.END\n                setTextColor(if (selected)')

    theme_block = r'''    private fun renderThemeTab() {
        body.addView(section("Tema Keyboard"))
        val customPreview = runCatching { Color.parseColor(KeyboardTheme.normalizeColor(draft.themeColor)) }.getOrDefault(accent)
        val themeOptions = listOf(
            Triple(KeyboardTheme.MODE_DARK, "Gelap Modern (Default)", Color.rgb(138, 112, 255)),
            Triple(KeyboardTheme.MODE_BLUE, "Deep Navy", Color.rgb(52, 166, 255)),
            Triple(KeyboardTheme.MODE_PURPLE, "Cyber Purple", Color.rgb(197, 73, 255)),
            Triple(KeyboardTheme.MODE_ROSE, "Ruby Crimson", Color.rgb(255, 73, 98)),
            Triple(KeyboardTheme.MODE_GREEN, "Emerald Forest", Color.rgb(48, 215, 132)),
            Triple(KeyboardTheme.MODE_AMOLED, "Amoled Pitch Black", Color.rgb(151, 116, 255)),
            Triple(KeyboardTheme.MODE_CUSTOM, "Kustomisasi Warna", customPreview),
            Triple(KeyboardTheme.MODE_PHOTO, "Foto dari Perangkat", Color.rgb(255, 183, 77))
        )
        themeOptions.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (mode, label, previewColor) ->
                val selected = draft.themeMode == mode
                row.addView(TextView(context).apply {
                    text = themePreviewText(label, previewColor)
                    gravity = Gravity.CENTER
                    textSize = 11.5f
                    setTextColor(Color.WHITE)
                    background = rounded(if (selected) Color.rgb(31, 28, 55) else card, 12f, if (selected) accent else border, if (selected) 1 else 1)
                    setOnClickListener {
                        draft.themeMode = mode
                        renderBody()
                    }
                }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            body.addView(row)
        }

        if (draft.themeMode == KeyboardTheme.MODE_CUSTOM) {
            body.addView(textInput("Warna, contoh #5D4AC4", draft.themeColor) { draft.themeColor = it }, LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(8)
            })
        }

        if (draft.themeMode == KeyboardTheme.MODE_PHOTO) {
            val photoCard = cardContainer()
            photoCard.addView(section("Foto Latar dari Perangkat", compact = true))
            val hasPhoto = !prefs.getString("keyboard_theme_image_uri", "").isNullOrBlank()
            photoCard.addView(description(if (hasPhoto) "✓ Foto perangkat sudah dipilih. Tekan tombol di bawah untuk mengganti." else "Pilih foto langsung dari galeri HP untuk dijadikan latar keyboard."))
            photoCard.addView(actionButton(if (hasPhoto) "Ganti Foto dari HP" else "Pilih Foto dari HP") {
                draft.themeMode = KeyboardTheme.MODE_PHOTO
                context.startActivity(
                    Intent(context, ThemePhotoPickerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
            if (hasPhoto) {
                photoCard.addView(actionButton("Hapus Foto", danger = true) {
                    prefs.edit().remove("keyboard_theme_image_uri").apply()
                    draft.themeMode = KeyboardTheme.MODE_DARK
                    renderBody()
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
            }
            body.addView(photoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        val sizeCard = cardContainer()
        sizeCard.addView(sliderRow("Tinggi Keyboard (Portrait)", 170, 330, draft.portraitHeight, " dp") { draft.portraitHeight = it })
        sizeCard.addView(sliderRow("Tinggi Keyboard (Landscape)", 90, 190, draft.landscapeHeight, " dp") { draft.landscapeHeight = it })
        body.addView(sizeCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val keyCard = cardContainer()
        keyCard.addView(sliderRow("Ukuran Teks Tombol", 16, 28, draft.keyTextSize, " sp") { draft.keyTextSize = it })
        keyCard.addView(sliderRow("Skala Kotak Tombol", 65, 110, draft.keyBoxScale, "%") { draft.keyBoxScale = it })
        keyCard.addView(sliderRow("Durasi Tekan Lama", 200, 900, draft.longPressMs, " ms") { draft.longPressMs = it })
        body.addView(keyCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        body.addView(toggleCard("Tampilkan Baris Angka (1–0)", "Menyematkan baris angka di atas QWERTY.", draft.numberRow) { draft.numberRow = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

'''
    text = replace_block(text, "    private fun renderThemeTab() {", "    private fun renderTypingTab() {", theme_block, "theme tab")

    text = replace_once(
        text,
        "        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) activeInput = this }\n",
        "        setOnFocusChangeListener { _, hasFocus ->\n            if (hasFocus) {\n                activeInput = this\n                onInputFocusChanged(true)\n            } else if (activeInput === this) {\n                activeInput = null\n                onInputFocusChanged(false)\n            }\n        }\n",
        "settings input focus",
    )

    helper_marker = "    private fun actionButton(label: String, danger: Boolean = false, action: () -> Unit) = Button(context).apply {\n"
    helper = r'''    private fun themePreviewText(label: String, previewColor: Int): SpannableString {
        val value = SpannableString("Aa 123\n$label")
        value.setSpan(ForegroundColorSpan(previewColor), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        value.setSpan(ForegroundColorSpan(Color.WHITE), 7, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return value
    }

'''
    if "private fun themePreviewText(" not in text:
        text = text.replace(helper_marker, helper + helper_marker, 1)
    OVERLAY.write_text(text)


def patch_service() -> None:
    text = SERVICE.read_text()
    text = replace_once(
        text,
        "    private lateinit var root: LinearLayout\n",
        "    private lateinit var inputHost: FrameLayout\n    private lateinit var root: LinearLayout\n",
        "input host field",
    )
    text = replace_once(
        text,
        "    private lateinit var settingsPanel: KeyboardSettingsOverlay\n    private var settingsPanelVisible = false\n",
        "    private lateinit var settingsPanel: KeyboardSettingsOverlay\n    private lateinit var settingsScrim: View\n    private var settingsPanelVisible = false\n    private var settingsEditingMode = false\n",
        "settings fields",
    )

    create_input = r'''    override fun onCreateInputView(): View {
        loadPreferences()

        inputHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            updateRootPadding(this)
        }
        inputHost.addView(root, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        addAiConversationPanel()
        addSearchSurfacePanel()
        addSettingsPanel()
        addUtilityBar()
        addSuggestionBar()
        addResizePanel()

        keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        root.addView(keyboardPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        addBottomBrandBar()
        applyTheme()
        applyRootHeight()
        renderKeyboard()
        refreshSuggestionsSoon()
        return inputHost
    }

'''
    text = replace_block(text, "    override fun onCreateInputView(): View {", "    override fun onStartInputView(", create_input, "create input view")

    settings_block = r'''    private fun addSettingsPanel() {
        settingsScrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { toggleSettingsPanel(false) }
        }
        inputHost.addView(settingsScrim, FrameLayout.LayoutParams(-1, -1))

        settingsPanel = KeyboardSettingsOverlay(
            context = this,
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE),
            onApply = {
                loadPreferences()
                if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
                renderKeyboard()
                refreshSuggestionsSoon()
            },
            onClose = {
                settingsPanelVisible = false
                settingsEditingMode = false
                if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
                if (::settingsScrim.isInitialized) settingsScrim.visibility = View.GONE
                applyRootHeight()
                renderKeyboard()
            },
            onInputFocusChanged = { focused ->
                settingsEditingMode = focused
                applyRootHeight()
            }
        ).apply {
            visibility = View.GONE
            elevation = dpFloat(18f)
        }
        inputHost.addView(settingsPanel, FrameLayout.LayoutParams(-1, dp(560), Gravity.CENTER).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        })
    }

    private fun toggleSettingsPanel(force: Boolean? = null) {
        val show = force ?: !settingsPanelVisible
        if (show == settingsPanelVisible) return
        if (show) {
            if (searchSurfaceVisible) closeSearchSurface()
            aiPanelVisible = false
            aiFullscreen = false
            aiComposeActive = false
            aiPanel.visibility = View.GONE
            resizePanelVisible = false
            resizePanel.visibility = View.GONE
            settingsEditingMode = false
            settingsPanelVisible = true
            settingsScrim.visibility = View.VISIBLE
            settingsPanel.show()
        } else {
            settingsPanel.hidePanel()
            return
        }
        applyRootHeight()
    }

'''
    text = replace_block(text, "    private fun addSettingsPanel() {", "    private fun addUtilityBar() {", settings_block, "settings panel block")

    text = replace_once(
        text,
        "        consumePendingScanResult()\n        if (searchSurfaceVisible && scannerActive && scannerGalleryUri == null &&\n",
        "        consumePendingScanResult()\n        if (settingsPanelVisible && ::settingsPanel.isInitialized) settingsPanel.refreshExternalChanges()\n        if (searchSurfaceVisible && scannerActive && scannerGalleryUri == null &&\n",
        "window shown settings refresh",
    )

    root_height = r'''    private fun applyRootHeight() {
        if (!::root.isInitialized) return
        updateRootPadding(root)
        if (::utilityBarFrame.isInitialized) {
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
        }
        if (::suggestionBar.isInitialized) suggestionBar.layoutParams = suggestionBar.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (::resizePanel.isInitialized) resizePanel.layoutParams = resizePanel.layoutParams.apply {
            height = dp(resizePanelHeightDp())
        }
        if (::bottomBrandBar.isInitialized) bottomBrandBar.layoutParams = bottomBrandBar.layoutParams.apply {
            height = dp(brandBarHeightDp())
        }
        if (::searchSurfacePanel.isInitialized) {
            searchSurfacePanel.layoutParams = (searchSurfacePanel.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(searchSurfaceHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = dp(searchSurfaceHeightDp())
                setMargins(dp(4), dp(3), dp(4), dp(4))
            }
        }
        if (::aiPanel.isInitialized) {
            aiPanel.layoutParams = (aiPanel.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(currentAiPanelHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (aiFullscreen) 0 else dp(currentAiPanelHeightDp())
                weight = if (aiFullscreen) 1f else 0f
            }
        }

        if (aiFullscreen) {
            applyAiDisplayMode()
            val screenHeight = fullscreenRootHeightPx()
            root.minimumHeight = screenHeight
            root.layoutParams = (root.layoutParams ?: FrameLayout.LayoutParams(-1, screenHeight, Gravity.BOTTOM)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = screenHeight
            }
            inputHost.minimumHeight = screenHeight
            inputHost.layoutParams = (inputHost.layoutParams ?: ViewGroup.LayoutParams(-1, screenHeight)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = screenHeight
            }
            root.requestLayout()
            inputHost.requestLayout()
            window?.window?.decorView?.requestLayout()
            return
        }

        applyAiDisplayMode()
        val extra = brandBarHeightDp() +
            (if (aiPanelVisible) currentAiPanelHeightDp() else 0) +
            (if (resizePanelVisible) resizePanelHeightDp() else 0) +
            (if (searchSurfaceVisible) searchSurfaceHeightDp() + 7 else 0)
        val rootHeight = dp(baseKeyboardHeightDp.coerceAtLeast(1) + extra)
        root.minimumHeight = rootHeight
        root.layoutParams = (root.layoutParams ?: FrameLayout.LayoutParams(-1, rootHeight, Gravity.BOTTOM)).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = rootHeight
        }

        if (settingsPanelVisible) {
            val screenHeight = fullscreenRootHeightPx()
            inputHost.minimumHeight = screenHeight
            inputHost.layoutParams = (inputHost.layoutParams ?: ViewGroup.LayoutParams(-1, screenHeight)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = screenHeight
            }
            settingsScrim.visibility = View.VISIBLE
            settingsPanel.visibility = View.VISIBLE
            val compactHeight = (screenHeight - rootHeight - dp(18)).coerceAtLeast(dp(300))
            val floatingHeight = (screenHeight * 0.78f).toInt().coerceIn(dp(360), screenHeight - dp(36))
            settingsPanel.layoutParams = FrameLayout.LayoutParams(
                -1,
                if (settingsEditingMode) compactHeight else floatingHeight,
                if (settingsEditingMode) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                topMargin = if (settingsEditingMode) dp(10) else 0
                bottomMargin = if (settingsEditingMode) rootHeight + dp(8) else 0
            }
        } else {
            if (::settingsScrim.isInitialized) settingsScrim.visibility = View.GONE
            if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
            inputHost.minimumHeight = rootHeight
            inputHost.layoutParams = (inputHost.layoutParams ?: ViewGroup.LayoutParams(-1, rootHeight)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = rootHeight
            }
        }

        root.requestLayout()
        inputHost.requestLayout()
        window?.window?.decorView?.requestLayout()
        inputHost.post {
            root.requestLayout()
            inputHost.requestLayout()
            window?.window?.decorView?.requestLayout()
        }
    }

'''
    text = replace_block(text, "    private fun applyRootHeight() {", "    private fun renderKeyboard() {", root_height, "root height")
    SERVICE.write_text(text)


def patch_theme() -> None:
    text = THEME.read_text()
    text = replace_once(text, '    const val MODE_PHOTO = "photo"\n', '    const val MODE_PHOTO = "photo"\n    const val MODE_AMOLED = "amoled"\n', "amoled constant")
    text = replace_once(
        text,
        '        MODE_CUSTOM to "Warna sendiri",\n        MODE_PHOTO to "Foto dari galeri"\n',
        '        MODE_CUSTOM to "Warna sendiri",\n        MODE_AMOLED to "Amoled Pitch Black",\n        MODE_PHOTO to "Foto dari galeri"\n',
        "amoled mode list",
    )
    marker = '        if (mode == MODE_DARK) {\n'
    amoled = '''        if (mode == MODE_AMOLED) {
            return KeyboardThemePalette(
                background = Color.BLACK,
                key = Color.rgb(17, 17, 20),
                specialKey = Color.rgb(21, 21, 25),
                pressedKey = Color.rgb(96, 67, 210),
                accent = Color.rgb(111, 79, 232),
                text = Color.WHITE,
                usesPhoto = false
            )
        }
'''
    if "if (mode == MODE_AMOLED)" not in text:
        text = text.replace(marker, amoled + marker, 1)
    THEME.write_text(text)


def patch_manifest() -> None:
    text = MANIFEST.read_text()
    if '.ThemePhotoPickerActivity' not in text:
        marker = '        <activity android:name=".MainActivity" android:exported="true">\n'
        entry = '''        <activity
            android:name=".ThemePhotoPickerActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
'''
        if marker not in text:
            raise RuntimeError("manifest activity marker missing")
        text = text.replace(marker, entry + marker, 1)
    MANIFEST.write_text(text)


def main() -> None:
    patch_overlay()
    patch_service()
    patch_theme()
    patch_manifest()
    print("settings overlay v3 polish applied")


if __name__ == "__main__":
    main()
