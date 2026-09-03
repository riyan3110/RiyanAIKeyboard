package com.riyan.aikeyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

class RiyanKeyboardService : InputMethodService() {
    private enum class KeyboardMode { LETTERS, SYMBOLS, CURSOR, EMOJI, CLIPBOARD }

    private class CameraSessionLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private data class KeySpec(
        val label: String,
        val alternate: String? = null,
        val weight: Float = 1f,
        val action: () -> Unit,
        val longAction: (() -> Unit)? = null
    )

    private data class ClipEntry(val text: String, val pinned: Boolean)

    private lateinit var root: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var utilityBar: LinearLayout
    private lateinit var resizePanel: LinearLayout
    private lateinit var suggestionBar: LinearLayout
    private lateinit var bottomBrandBar: LinearLayout
    private lateinit var aiPanel: LinearLayout
    private lateinit var aiStatus: TextView
    private lateinit var aiAnswer: TextView
    private lateinit var aiAnswerScroll: ScrollView
    private lateinit var aiInput: EditText
    private lateinit var aiFullscreenButton: Button
    private lateinit var heightLabel: TextView
    private lateinit var searchSurfacePanel: FrameLayout
    private lateinit var searchSurfaceContent: FrameLayout
    private var searchWebView: WebView? = null
    private var searchInput: EditText? = null
    private var scannerPreviewView: PreviewView? = null
    private var scannerStatusText: TextView? = null
    private var scannerResultText: TextView? = null
    private var scannerSearchButton: Button? = null
    private var scannerCamera: Camera? = null
    private var scannerLifecycleOwner: CameraSessionLifecycleOwner? = null
    private var searchSurfaceVisible = false
    private var searchComposeActive = false
    private var scannerActive = false
    private var scannerTorchEnabled = false
    private var scannerBestScore = 0
    private var scannerSelectedQuery = ""
    private var scannerSelectedUrl = ""
    private var scannerLastFrameAt = 0L
    private var scannerLastCandidateAt = 0L
    private val scannerProcessingFrame = AtomicBoolean(false)
    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val scannerTextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val scannerImageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.50f)
                .build()
        )
    }

    private var mode = KeyboardMode.LETTERS
    private var shift = false
    private var capsLock = false
    private var lastShiftTapAt = 0L
    private var pendingText: String? = null
    private var emojiPage = 0
    private var searchQuery = ""
    private var searchUrl = ""
    private var lastConsumedScanNonce = 0L
    private var baseKeyboardHeightDp = DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP
    private var minKeyboardHeightDp = MIN_KEYBOARD_HEIGHT_PORTRAIT_DP
    private var maxKeyboardHeightDp = MAX_KEYBOARD_HEIGHT_PORTRAIT_DP
    private var heightPreferenceKey = HEIGHT_PORTRAIT_KEY
    private var keyTextSizeSp = 21f
    private var keyBoxScale = 1f
    private var touchTolerancePx = 0f
    private var instantKeyResponse = false
    private var longPressDurationMs = 450L
    private var activeKeyPreview: PopupWindow? = null
    private var activeKeyPreviewLabel: TextView? = null
    private var keyPreviewDismissRunnable: Runnable? = null
    private var aiPanelVisible = false
    private var aiFullscreen = false
    private var resizePanelVisible = false
    private var aiComposeActive = false
    private var numberRowEnabled = true
    private var longPressSymbolsEnabled = true
    private var soundEnabled = false
    private var vibrationEnabled = true
    private var vibrationDurationMs = 28L
    private var doubleSpacePeriodEnabled = false
    private var automaticCapitalizationEnabled = true
    private var punctuationSpaceEnabled = false
    private var clipboardHistoryEnabled = true
    private var suggestionsEnabled = true
    private var personalizedLearningEnabled = true
    private var styleMemoryEnabled = true
    private var enterActionEnabled = false
    private var lastSpaceAt = 0L
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    private val handler = Handler(Looper.getMainLooper())
    private val suggestionRefreshRunnable = Runnable { refreshSuggestions() }
    private val suggestionAutoHideRunnable = Runnable {
        if (::suggestionBar.isInitialized) {
            suggestionBar.removeAllViews()
            suggestionBar.visibility = View.VISIBLE
        }
    }
    private var bg = Color.rgb(18, 18, 23)
    private var keyBg = Color.rgb(50, 50, 60)
    private var specialKeyBg = Color.rgb(43, 68, 80)
    private var pressedKeyBg = Color.rgb(93, 74, 196)
    private var purple = Color.rgb(89, 68, 196)
    private var keyTextColor = Color.WHITE
    private var themeUsesPhoto = false

    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (clipboardHistoryEnabled) addCurrentClipboardToHistory()
        if (::keyboardPanel.isInitialized && mode == KeyboardMode.CLIPBOARD) renderKeyboard()
    }

    private val emojiPages = listOf(
        listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "🤩"),
        listOf("😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫡"),
        listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "👏", "🙌", "🫶", "🤝", "🙏", "✍️", "💪", "🦾", "❤️", "🧡", "💛", "💚", "💙"),
        listOf("🎉", "🎊", "🔥", "✨", "⭐", "🌟", "💯", "✅", "❌", "⚠️", "💡", "📌", "🎁", "🎂", "☕", "🍜", "🍕", "🍔", "🚗", "🏍️", "✈️", "🏠", "📱", "💻", "🎮", "⚽", "🎵", "📷", "🌞", "🌙", "🌈")
    )

    override fun onCreate() {
        super.onCreate()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        dismissKeyPreview()
        handler.removeCallbacksAndMessages(null)
        stopEmbeddedScanner()
        destroySearchWebView()
        barcodeScanner.close()
        scannerTextRecognizer.close()
        scannerImageLabeler.close()
        scannerExecutor.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadPreferences()
        if (::root.isInitialized) {
            applyRootHeight()
            renderKeyboard()
            refreshSuggestionsSoon()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshSuggestionsSoon()
    }

    override fun onCreateInputView(): View {
        loadPreferences()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            updateRootPadding(this)
        }

        addAiConversationPanel()
        addSearchSurfacePanel()
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
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadPreferences()
        if (automaticCapitalizationEnabled) updateAutomaticShift()
        if (clipboardHistoryEnabled) addCurrentClipboardToHistory()
        if (::root.isInitialized) {
            applyRootHeight()
            renderKeyboard()
            refreshSuggestionsSoon()
        }
        consumePendingScanResult()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        consumePendingScanResult()
        if (searchSurfaceVisible && scannerActive &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            scannerPreviewView?.post { startEmbeddedScanner() }
        }
    }

    override fun onWindowHidden() {
        stopEmbeddedScanner(keepRequested = true)
        super.onWindowHidden()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        heightPreferenceKey = if (landscape) HEIGHT_LANDSCAPE_KEY else HEIGHT_PORTRAIT_KEY
        minKeyboardHeightDp = if (landscape) MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP else MIN_KEYBOARD_HEIGHT_PORTRAIT_DP
        maxKeyboardHeightDp = if (landscape) MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP else MAX_KEYBOARD_HEIGHT_PORTRAIT_DP
        val defaultHeight = if (landscape) DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE_DP else DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP
        val layoutVersion = prefs.getInt("keyboard_layout_version", 0)
        if (layoutVersion < 5) {
            val legacy = prefs.getInt("keyboard_height_dp", OLD_DEFAULT_KEYBOARD_HEIGHT_DP)
            val portrait = if (legacy == OLD_DEFAULT_KEYBOARD_HEIGHT_DP) {
                DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP
            } else {
                legacy.coerceIn(MIN_KEYBOARD_HEIGHT_PORTRAIT_DP, MAX_KEYBOARD_HEIGHT_PORTRAIT_DP)
            }
            prefs.edit()
                .putInt(HEIGHT_PORTRAIT_KEY, portrait)
                .putInt(HEIGHT_LANDSCAPE_KEY, DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE_DP)
                .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                .apply()
        }
        if (layoutVersion < 6) {
            val legacyLandscape = prefs.getInt(HEIGHT_LANDSCAPE_KEY, 205)
            val migratedLandscape = when {
                legacyLandscape >= 175 -> legacyLandscape - 30
                else -> legacyLandscape
            }.coerceIn(MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP, MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP)
            prefs.edit()
                .putInt(HEIGHT_LANDSCAPE_KEY, migratedLandscape)
                .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                .apply()
        }
        if (layoutVersion < KEYBOARD_LAYOUT_VERSION) {
            val oldPortrait = prefs.getInt(HEIGHT_PORTRAIT_KEY, 250)
            val oldLandscape = prefs.getInt(HEIGHT_LANDSCAPE_KEY, 155)
            prefs.edit()
                .putInt(
                    HEIGHT_PORTRAIT_KEY,
                    if (oldPortrait <= 210) 185 else oldPortrait.coerceIn(MIN_KEYBOARD_HEIGHT_PORTRAIT_DP, MAX_KEYBOARD_HEIGHT_PORTRAIT_DP)
                )
                .putInt(
                    HEIGHT_LANDSCAPE_KEY,
                    if (oldLandscape <= 135) 105 else oldLandscape.coerceIn(MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP, MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP)
                )
                .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
                .apply()
        }
        baseKeyboardHeightDp = prefs.getInt(heightPreferenceKey, defaultHeight)
            .coerceIn(minKeyboardHeightDp, maxKeyboardHeightDp)
        keyTextSizeSp = prefs.getInt("key_text_size_sp", 21).coerceIn(16, 28).toFloat()
        keyBoxScale = prefs.getInt("key_box_scale_percent", 100).coerceIn(65, 110) / 100f
        val sensitivity = prefs.getInt("touch_sensitivity", 100).coerceIn(20, 400)
        touchTolerancePx = dpFloat(12f + sensitivity * 0.18f)
        instantKeyResponse = sensitivity >= INSTANT_RESPONSE_THRESHOLD
        longPressDurationMs = prefs.getInt("long_press_ms", 450).coerceIn(200, 900).toLong()
        numberRowEnabled = prefs.getBoolean("number_row_enabled", true)
        longPressSymbolsEnabled = prefs.getBoolean("long_press_symbols_enabled", true)
        soundEnabled = prefs.getBoolean("sound_enabled", false)
        vibrationEnabled = prefs.getBoolean("vibration_enabled", true)
        vibrationDurationMs = prefs.getInt("vibration_duration_ms", 28).coerceIn(5, 80).toLong()
        doubleSpacePeriodEnabled = prefs.getBoolean("double_space_period_enabled", false)
        automaticCapitalizationEnabled = prefs.getBoolean("automatic_capitalization_enabled", true)
        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)
        clipboardHistoryEnabled = prefs.getBoolean("clipboard_history_enabled", true)
        suggestionsEnabled = prefs.getBoolean("suggestions_enabled", true)
        personalizedLearningEnabled = prefs.getBoolean("personalized_learning_enabled", true)
        styleMemoryEnabled = prefs.getBoolean("style_memory_enabled", true)
        enterActionEnabled = prefs.getBoolean("enter_action_enabled", false)
        val palette = KeyboardTheme.palette(prefs)
        bg = palette.background
        keyBg = palette.key
        specialKeyBg = palette.specialKey
        pressedKeyBg = palette.pressedKey
        purple = palette.accent
        keyTextColor = palette.text
        themeUsesPhoto = palette.usesPhoto
        if (::root.isInitialized) {
            updateRootPadding(root)
            applyTheme()
        }
    }

    private fun applyTheme() {
        if (!::root.isInitialized) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val palette = KeyboardTheme.palette(prefs)
        root.background = KeyboardTheme.background(this, prefs, palette)
        if (::utilityBar.isInitialized) {
            utilityBar.setBackgroundColor(keyBg)
            for (index in 0 until utilityBar.childCount) {
                val child = utilityBar.getChildAt(index)
                if (child !== suggestionBar) child.setBackgroundColor(keyBg)
            }
        }
        if (::suggestionBar.isInitialized) {
            suggestionBar.setBackgroundColor(keyBg)
        }
        if (::bottomBrandBar.isInitialized) {
            bottomBrandBar.setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)
        }
    }

    private fun updateRootPadding(target: LinearLayout) {
        target.setPadding(0, 0, 0, 0)
    }

    private fun addBottomBrandBar() {
        bottomBrandBar = LinearLayout(this).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            setBackgroundColor(if (themeUsesPhoto) Color.TRANSPARENT else bg)
            addView(TextView(this@RiyanKeyboardService).apply {
                text = "AI Ads Keyboard · v0.18"
                textSize = 9f
                setTextColor(Color.rgb(145, 137, 190))
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-2, dp(17)))
        }
        root.addView(bottomBrandBar, LinearLayout.LayoutParams(-1, dp(brandBarHeightDp())))
    }

    private fun addAiConversationPanel() {
        aiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(3), dp(1), dp(2))
            background = roundedBackground(Color.rgb(31, 31, 36), 9f)
            visibility = View.GONE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "✦ Obrolan AI"
            textSize = 14f
            setTextColor(keyTextColor)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(aiHeaderHeightDp()), 1f))
        header.addView(compactButton("Hapus") {
            conversationHistory.clear()
            pendingText = null
            aiAnswer.text = "Jawaban AI akan muncul di sini."
            aiStatus.text = activeProviderLabel()
        }, LinearLayout.LayoutParams(dp(58), dp(aiHeaderHeightDp())))
        aiFullscreenButton = compactButton("⛶") { toggleAiFullscreen() }
        aiFullscreenButton.contentDescription = "Buka obrolan AI layar penuh"
        header.addView(aiFullscreenButton, LinearLayout.LayoutParams(dp(38), dp(aiHeaderHeightDp())).apply {
            leftMargin = dp(2)
        })
        header.addView(compactButton("✕") { toggleAiPanel(false) }, LinearLayout.LayoutParams(dp(38), dp(aiHeaderHeightDp())))
        aiPanel.addView(header)

        aiAnswer = TextView(this).apply {
            text = "Jawaban AI akan muncul di sini."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setOnClickListener { insertPendingResult() }
        }
        aiAnswerScroll = ScrollView(this).apply {
            background = roundedBackground(Color.rgb(38, 38, 43), 10f)
            addView(aiAnswer, ViewGroup.LayoutParams(-1, -2))
        }
        aiPanel.addView(aiAnswerScroll, LinearLayout.LayoutParams(-1, dp(aiAnswerHeightDp())).apply { topMargin = dp(1) })

        val composeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(1), dp(3), dp(1))
            background = roundedBackground(Color.rgb(45, 45, 51), 15f)
        }
        aiInput = EditText(this).apply {
            hint = "Ketik pesan untuk AI…"
            textSize = 13f
            maxLines = 3
            minLines = 1
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            showSoftInputOnFocus = false
            setPadding(dp(8), 0, dp(8), 0)
            background = null
            setOnClickListener { aiComposeActive = true }
            setOnFocusChangeListener { _, hasFocus -> aiComposeActive = hasFocus }
        }
        composeCard.addView(aiInput, LinearLayout.LayoutParams(-1, dp(aiInputHeightDp())))

        val composeFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composeFooter.addView(compactButton("＋") { pasteClipboardIntoAiInput() }, LinearLayout.LayoutParams(dp(34), dp(29)))
        aiStatus = TextView(this).apply {
            text = activeProviderLabel()
            textSize = 10f
            maxLines = 1
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(3), 0)
        }
        composeFooter.addView(aiStatus, LinearLayout.LayoutParams(0, dp(29), 1f))
        composeFooter.addView(compactButton("Pakai") { insertPendingResult() }, LinearLayout.LayoutParams(dp(55), dp(29)))
        composeFooter.addView(compactButton("↑") { runAiConversation() }, LinearLayout.LayoutParams(dp(38), dp(29)).apply { leftMargin = dp(4) })
        composeCard.addView(composeFooter)
        aiPanel.addView(composeCard, LinearLayout.LayoutParams(-1, dp(aiComposeHeightDp())).apply { topMargin = dp(2) })

        val quickActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Perbaiki", "Balas", "Terjemah", "Ringkas", "Santai", "Sopan").forEach { action ->
            quickActions.addView(compactButton(action) { runAi(action) }, LinearLayout.LayoutParams(0, dp(aiQuickActionHeightDp()), 1f).apply {
                setMargins(dp(1), dp(2), dp(1), 0)
            })
        }
        aiPanel.addView(quickActions)
        root.addView(aiPanel, LinearLayout.LayoutParams(-1, dp(currentAiPanelHeightDp())))
    }

    /**
     * The camera and search page live above the utility bar so the actual keyboard never gets
     * replaced. This also avoids opening a full-screen scanner activity after camera permission
     * has been granted.
     */
    private fun addSearchSurfacePanel() {
        searchSurfaceContent = FrameLayout(this)
        searchSurfacePanel = FrameLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpFloat(24f)
                setStroke(dp(1), Color.rgb(224, 224, 228))
            }
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
            addView(searchSurfaceContent, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(searchSurfacePanel, LinearLayout.LayoutParams(-1, dp(searchSurfaceHeightDp())).apply {
            setMargins(dp(4), dp(3), dp(4), dp(4))
        })
    }

    private fun addUtilityBar() {
        utilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(keyBg)
        }
        utilityBar.addView(toolbarButton("✦ AI", dp(57)) { toggleAiPanel() })
        utilityBar.addView(toolbarButton("⌨", dp(43)) {
            aiComposeActive = false
            mode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("😊", dp(43)) {
            aiComposeActive = false
            emojiPage = 0
            mode = KeyboardMode.EMOJI
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("📋", dp(43)) {
            aiComposeActive = false
            addCurrentClipboardToHistory()
            mode = KeyboardMode.CLIPBOARD
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("↕", dp(43)) { toggleResizePanel() })
        utilityBar.addView(toolbarButton("📷", dp(43)) { launchScanner() })
        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            setBackgroundColor(keyBg)
            visibility = View.VISIBLE
        }
        utilityBar.addView(suggestionBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        utilityBar.addView(toolbarButton("⚙", dp(43)) { openSettings() })
        root.addView(utilityBar, LinearLayout.LayoutParams(-1, dp(utilityHeightDp())).apply {
            bottomMargin = dp(toolbarKeyboardGapDp())
        })
    }

    private fun addSuggestionBar() {
        refreshSuggestions()
    }

    private fun addResizePanel() {
        resizePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            background = roundedBackground(Color.rgb(25, 58, 82), 9f)
            visibility = View.GONE
        }
        resizePanel.addView(compactButton("−") { changeKeyboardHeight(-10) }, LinearLayout.LayoutParams(dp(48), dp(34)))
        heightLabel = TextView(this).apply {
            text = keyboardHeightLabel()
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(keyTextColor)
            setTypeface(typeface, Typeface.BOLD)
            setOnTouchListener(heightDragListener())
        }
        resizePanel.addView(heightLabel, LinearLayout.LayoutParams(0, dp(34), 1f))
        resizePanel.addView(compactButton("+") { changeKeyboardHeight(10) }, LinearLayout.LayoutParams(dp(48), dp(34)))
        resizePanel.addView(compactButton("Selesai") { toggleResizePanel(false) }, LinearLayout.LayoutParams(dp(70), dp(34)))
        root.addView(resizePanel, LinearLayout.LayoutParams(-1, dp(resizePanelHeightDp())))
    }

    private fun heightDragListener(): View.OnTouchListener {
        var startY = 0f
        var startHeight = 0
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = baseKeyboardHeightDp
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaDp = ((startY - event.rawY) / resources.displayMetrics.density).toInt()
                    setKeyboardHeight(startHeight + deltaDp, persist = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveKeyboardHeight()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleAiPanel(force: Boolean? = null) {
        aiPanelVisible = force ?: !aiPanelVisible
        aiPanel.visibility = if (aiPanelVisible) View.VISIBLE else View.GONE
        if (aiPanelVisible) {
            aiStatus.text = activeProviderLabel()
        } else {
            aiFullscreen = false
            aiComposeActive = false
            aiInput.clearFocus()
        }
        applyAiDisplayMode()
        applyRootHeight()
    }

    private fun toggleAiFullscreen() {
        aiPanelVisible = true
        aiPanel.visibility = View.VISIBLE
        aiFullscreen = !aiFullscreen
        aiStatus.text = activeProviderLabel()
        applyAiDisplayMode()
        applyRootHeight()
    }

    private fun applyAiDisplayMode() {
        if (!::aiPanel.isInitialized) return
        if (::aiFullscreenButton.isInitialized) {
            aiFullscreenButton.text = if (aiFullscreen) "↙" else "⛶"
            aiFullscreenButton.contentDescription = if (aiFullscreen) {
                "Kecilkan obrolan AI"
            } else {
                "Buka obrolan AI layar penuh"
            }
        }
        if (::aiAnswerScroll.isInitialized) {
            aiAnswerScroll.layoutParams = (aiAnswerScroll.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(aiAnswerHeightDp()))).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (aiFullscreen) 0 else dp(aiAnswerHeightDp())
                weight = if (aiFullscreen) 1f else 0f
                topMargin = dp(1)
            }
        }
        if (::suggestionBar.isInitialized) {
            if (aiFullscreen || !suggestionsEnabled) suggestionBar.removeAllViews()
            suggestionBar.visibility = View.VISIBLE
        }
        if (::resizePanel.isInitialized) {
            resizePanel.visibility = if (!aiFullscreen && resizePanelVisible) View.VISIBLE else View.GONE
        }
        if (::keyboardPanel.isInitialized) {
            keyboardPanel.layoutParams = if (aiFullscreen) {
                LinearLayout.LayoutParams(-1, dp(fullscreenKeyboardPanelHeightDp()))
            } else {
                LinearLayout.LayoutParams(-1, 0, 1f)
            }
        }
    }

    private fun toggleResizePanel(force: Boolean? = null) {
        resizePanelVisible = force ?: !resizePanelVisible
        resizePanel.visibility = if (resizePanelVisible) View.VISIBLE else View.GONE
        heightLabel.text = keyboardHeightLabel()
        applyRootHeight()
    }

    private fun changeKeyboardHeight(deltaDp: Int) {
        setKeyboardHeight(baseKeyboardHeightDp + deltaDp, persist = true)
    }

    private fun setKeyboardHeight(valueDp: Int, persist: Boolean) {
        baseKeyboardHeightDp = valueDp.coerceIn(minKeyboardHeightDp, maxKeyboardHeightDp)
        if (::heightLabel.isInitialized) heightLabel.text = keyboardHeightLabel()
        if (persist) saveKeyboardHeight()
        applyRootHeight()
    }

    private fun saveKeyboardHeight() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(heightPreferenceKey, baseKeyboardHeightDp)
            .putInt("keyboard_layout_version", KEYBOARD_LAYOUT_VERSION)
            .apply()
    }

    private fun keyboardHeightLabel(): String {
        val modeLabel = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "lanskap" else "potret"
        return "Tinggi $modeLabel $baseKeyboardHeightDp dp · geser"
    }

    private fun applyRootHeight() {
        if (!::root.isInitialized) return
        updateRootPadding(root)
        if (::utilityBar.isInitialized) {
            utilityBar.layoutParams = (utilityBar.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(-1, dp(utilityHeightDp()))).apply {
                height = dp(utilityHeightDp())
                bottomMargin = dp(toolbarKeyboardGapDp())
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
            val screenHeight = resources.displayMetrics.heightPixels
            root.minimumHeight = screenHeight
            root.layoutParams = (root.layoutParams ?: ViewGroup.LayoutParams(-1, screenHeight)).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = screenHeight
            }
            root.requestLayout()
            window?.window?.decorView?.requestLayout()
            return
        }
        applyAiDisplayMode()
        val suggestionReduction = 0
        val extra = brandBarHeightDp() +
            (if (aiPanelVisible) currentAiPanelHeightDp() else 0) +
            (if (resizePanelVisible) resizePanelHeightDp() else 0) +
            (if (searchSurfaceVisible) searchSurfaceHeightDp() + 7 else 0)
        val height = dp((baseKeyboardHeightDp - suggestionReduction).coerceAtLeast(1) + extra)
        root.minimumHeight = height
        root.layoutParams = (root.layoutParams ?: ViewGroup.LayoutParams(-1, height)).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            this.height = height
        }
        root.requestLayout()
        window?.window?.decorView?.requestLayout()
        root.post {
            root.requestLayout()
            root.parent?.let { parent -> if (parent is View) parent.requestLayout() }
            window?.window?.decorView?.requestLayout()
        }
    }

    private fun renderKeyboard() {
        if (!::keyboardPanel.isInitialized) return
        keyboardPanel.removeAllViews()
        when (mode) {
            KeyboardMode.LETTERS -> renderLetters()
            KeyboardMode.SYMBOLS -> renderSymbols()
            KeyboardMode.CURSOR -> renderCursorPad()
            KeyboardMode.EMOJI -> renderEmoji()
            KeyboardMode.CLIPBOARD -> renderClipboard()
        }
        refreshSuggestionsSoon()
    }

    private fun refreshSuggestionsSoon() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionRefreshRunnable)
        handler.postDelayed(suggestionRefreshRunnable, 35L)
    }

    private fun refreshSuggestions() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionAutoHideRunnable)
        suggestionBar.removeAllViews()
        if (!suggestionsEnabled || isSensitiveEditor() || (mode != KeyboardMode.LETTERS && activeInternalInput() == null)) {
            suggestionBar.visibility = View.VISIBLE
            return
        }
        val before = textBeforeTypingCursor()
        val candidates = SuggestionEngine
            .suggest(before, loadLearnedSuggestions(), savedSuggestionEntries(), limit = 2)
            .take(2)
        if (candidates.isEmpty()) {
            suggestionBar.visibility = View.VISIBLE
            return
        }
        suggestionBar.visibility = View.VISIBLE
        candidates.forEach { candidate ->
            suggestionBar.addView(TextView(this).apply {
                text = candidate.text
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dp(3), 0, dp(3), 0)
                setTextColor(Color.WHITE)
                background = null
                isClickable = true
                contentDescription = "Saran: ${candidate.text}"
                setOnClickListener { acceptPrediction(candidate) }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        handler.postDelayed(suggestionAutoHideRunnable, SUGGESTION_AUTO_HIDE_MS)
    }

    private fun textBeforeTypingCursor(): String {
        activeInternalInput()?.let { input ->
            val cursor = input.selectionStart.coerceIn(0, input.text.length)
            return input.text.substring(0, cursor)
        }
        return currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
    }

    private fun acceptPrediction(candidate: KeyboardSuggestion) {
        val insertion = "${candidate.text} "
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceIn(0, editable.length)
            val end = internalInput.selectionEnd.coerceIn(0, editable.length)
            val replaceStart = if (start == end) (start - candidate.replaceLength).coerceAtLeast(0) else minOf(start, end)
            editable.replace(replaceStart, maxOf(start, end), insertion)
            internalInput.setSelection((replaceStart + insertion.length).coerceAtMost(editable.length))
        } else {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            if (!deleteSelectedText(ic) && candidate.replaceLength > 0) {
                ic.deleteSurroundingText(candidate.replaceLength, 0)
            }
            ic.commitText(insertion, 1)
            ic.endBatchEdit()
        }
        learnSuggestion(candidate.text)
        learnCurrentBoundary()
        refreshSuggestionsSoon()
    }

    private fun renderLetters() {
        if (numberRowEnabled) {
            val numbers = "1234567890"
            val numberAlt = "!@#$%^&*()"
            addRow(numbers.mapIndexed { index, c ->
                KeySpec(
                    c.toString(),
                    if (longPressSymbolsEnabled) numberAlt[index].toString() else null,
                    action = { commit(c.toString()) },
                    longAction = if (longPressSymbolsEnabled) ({ commit(numberAlt[index].toString()) }) else null
                )
            })
        }

        addLetterRow("qwertyuiop", listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}"))
        addLetterRow("asdfghjkl", listOf("@", "#", "£", "_", "&", "-", "+", "(", ")"), sidePadding = 0.35f)

        val third = mutableListOf<KeySpec>()
        third += KeySpec(if (capsLock) "⇪" else "⇧", weight = 1.72f, action = { handleShiftTap() })
        val thirdAlternates = listOf("*", "\"", "'", ":", ";", "!", "?")
        "zxcvbnm".forEachIndexed { index, c -> third += letterSpec(c, thirdAlternates[index]) }
        third += KeySpec("⌫", weight = 1.72f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(third)

        addRow(
            listOf(
                KeySpec("?123", weight = 1.62f, action = { mode = KeyboardMode.SYMBOLS; renderKeyboard() }),
                KeySpec(",", action = { commitPunctuation(",") }),
                KeySpec("spasi", weight = 4.45f, action = { commitSpace() }),
                KeySpec(".", action = { commitPunctuation(".") }),
                KeySpec(enterKeyLabel(), weight = 1.62f, action = { pressEnter() })
            )
        )
    }

    private fun addLetterRow(chars: String, alternates: List<String>, sidePadding: Float = 0f) {
        val keys = mutableListOf<KeySpec>()
        if (sidePadding > 0f) keys += KeySpec("", weight = sidePadding, action = {})
        chars.forEachIndexed { index, c -> keys += letterSpec(c, alternates[index]) }
        if (sidePadding > 0f) keys += KeySpec("", weight = sidePadding, action = {})
        addRow(keys)
    }

    private fun letterSpec(char: Char, alternate: String): KeySpec {
        val shown = if (shift) char.uppercaseChar().toString() else char.toString()
        return KeySpec(
            label = shown,
            alternate = if (longPressSymbolsEnabled) alternate else null,
            action = {
                commit(shown)
                if (shift && !capsLock) {
                    shift = false
                    renderKeyboard()
                }
            },
            longAction = if (longPressSymbolsEnabled) ({ commit(alternate) }) else null
        )
    }

    private fun handleShiftTap() {
        val now = SystemClock.elapsedRealtime()
        when {
            capsLock -> {
                capsLock = false
                shift = false
                lastShiftTapAt = 0L
            }
            now - lastShiftTapAt <= DOUBLE_TAP_SHIFT_MS -> {
                capsLock = true
                shift = true
                lastShiftTapAt = 0L
            }
            else -> {
                shift = !shift
                lastShiftTapAt = now
            }
        }
        renderKeyboard()
    }

    private fun renderSymbols() {
        addSimpleSymbolRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addSimpleSymbolRow(listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"))
        addSimpleSymbolRow(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆"))
        val last = listOf("<", ">", "[", "]", "{", "}", "_", "-", "+").map { symbolSpec(it) }.toMutableList()
        last += KeySpec("⌫", weight = 1.72f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(last)
        addRow(
            listOf(
                KeySpec("ABC", weight = 1.58f, action = { mode = KeyboardMode.LETTERS; renderKeyboard() }),
                KeySpec("\\", action = { commit("\\") }),
                KeySpec("/", action = { commit("/") }),
                KeySpec(":", action = { commitPunctuation(":") }),
                KeySpec("spasi", weight = 2.2f, action = { commitSpace() }),
                KeySpec("?", action = { commitPunctuation("?") }),
                KeySpec("2/2", weight = 1.25f, action = { mode = KeyboardMode.CURSOR; renderKeyboard() }),
                KeySpec(enterKeyLabel(), weight = 1.62f, action = { pressEnter() })
            )
        )
    }

    private fun renderCursorPad() {
        val workArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }

        val touchPad = TextView(this).apply {
            text = "Mouse\nGeser untuk memindahkan kursor"
            textSize = if (isLandscape()) 12f else 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = cursorPadBackground(pressed = false)
            contentDescription = "Touchpad kursor teks"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnTouchListener(cursorPadTouchListener())
        }
        workArea.addView(touchPad, LinearLayout.LayoutParams(0, -1, 1.75f).apply {
            rightMargin = dp(5)
        })

        val dPad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cursorPadBackground(pressed = false)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        addCursorDirectionRow(dPad, null, "↑" to KeyEvent.KEYCODE_DPAD_UP, null)
        addCursorDirectionRow(
            dPad,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT,
            null,
            "→" to KeyEvent.KEYCODE_DPAD_RIGHT
        )
        addCursorDirectionRow(dPad, null, "↓" to KeyEvent.KEYCODE_DPAD_DOWN, null)
        workArea.addView(dPad, LinearLayout.LayoutParams(0, -1, 1f))
        keyboardPanel.addView(workArea, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            KeySpec("ABC", weight = 1.58f, action = { mode = KeyboardMode.LETTERS; renderKeyboard() }),
            KeySpec("1/2", weight = 1.15f, action = { mode = KeyboardMode.SYMBOLS; renderKeyboard() }),
            KeySpec("spasi", weight = 3.3f, action = { commitSpace() }),
            KeySpec("⌫", weight = 1.72f, action = { deleteOne() }, longAction = { deleteWord() }),
            KeySpec(enterKeyLabel(), weight = 1.62f, action = { pressEnter() })
        ).forEach { spec ->
            bottom.addView(keyView(spec), LinearLayout.LayoutParams(0, -1, spec.weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        keyboardPanel.addView(bottom, LinearLayout.LayoutParams(-1, dp(cursorBottomRowHeightDp())))
    }

    private fun addCursorDirectionRow(
        parent: LinearLayout,
        left: Pair<String, Int>?,
        center: Pair<String, Int>?,
        right: Pair<String, Int>?
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(left, center, right).forEach { item ->
            val view = item?.let { (label, keyCode) -> cursorDirectionButton(label, keyCode) } ?: View(this)
            row.addView(view, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun cursorDirectionButton(label: String, keyCode: Int): View {
        val frame = FrameLayout(this).apply {
            scaleX = keyBoxScale
            scaleY = keyBoxScale
            isClickable = true
            isFocusable = false
            background = roundedBackground(specialKeyBg, 11f)
            contentDescription = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "Kursor kiri"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "Kursor kanan"
                KeyEvent.KEYCODE_DPAD_UP -> "Kursor naik"
                else -> "Kursor turun"
            }
        }
        frame.addView(TextView(this).apply {
            text = label
            textSize = if (isLandscape()) 20f else 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, FrameLayout.LayoutParams(-1, -1))

        var repeatRunnable: Runnable? = null
        frame.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.background = roundedBackground(pressedKeyBg, 11f)
                    moveCursor(keyCode)
                    keyFeedback(view, longPress = false)
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            moveCursor(keyCode)
                            handler.postDelayed(this, CURSOR_REPEAT_INTERVAL_MS)
                        }
                    }.also { handler.postDelayed(it, CURSOR_REPEAT_DELAY_MS) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatRunnable?.let(handler::removeCallbacks)
                    repeatRunnable = null
                    view.background = roundedBackground(specialKeyBg, 11f)
                    true
                }
                else -> true
            }
        }
        return frame
    }

    private fun cursorPadTouchListener(): View.OnTouchListener {
        var lastX = 0f
        var lastY = 0f
        var accumulatedX = 0f
        var accumulatedY = 0f
        var gaveFeedback = false
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    accumulatedX = 0f
                    accumulatedY = 0f
                    gaveFeedback = false
                    view.background = cursorPadBackground(pressed = true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    accumulatedX += event.x - lastX
                    accumulatedY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    val step = dpFloat(if (isLandscape()) 13f else 17f)
                    var moved = false
                    while (abs(accumulatedX) >= step) {
                        moveCursor(if (accumulatedX > 0f) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                        accumulatedX += if (accumulatedX > 0f) -step else step
                        moved = true
                    }
                    while (abs(accumulatedY) >= step) {
                        moveCursor(if (accumulatedY > 0f) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                        accumulatedY += if (accumulatedY > 0f) -step else step
                        moved = true
                    }
                    if (moved && !gaveFeedback) {
                        keyFeedback(view, longPress = false)
                        gaveFeedback = true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.background = cursorPadBackground(pressed = false)
                    true
                }
                else -> false
            }
        }
    }

    private fun cursorPadBackground(pressed: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (pressed) {
            intArrayOf(pressedKeyBg, Color.rgb(34, 28, 55))
        } else {
            intArrayOf(Color.rgb(65, 62, 76), Color.rgb(27, 26, 34))
        }
    ).apply {
        cornerRadius = dpFloat(17f)
        setStroke(dp(2), if (pressed) Color.rgb(214, 133, 255) else Color.rgb(127, 86, 180))
    }

    private fun moveCursor(keyCode: Int) {
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceIn(0, editable.length)
            val end = internalInput.selectionEnd.coerceIn(0, editable.length)
            cursorTarget(editable.toString(), start, end, keyCode)?.let(internalInput::setSelection)
        } else {
            moveTargetCursor(keyCode)
        }
        refreshSuggestionsSoon()
    }

    private fun moveTargetCursor(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        val text = extracted?.text?.toString()
        if (extracted != null && text != null) {
            val start = extracted.selectionStart.coerceIn(0, text.length)
            val end = extracted.selectionEnd.coerceIn(0, text.length)
            val target = cursorTarget(text, start, end, keyCode)
            if (target != null && ic.setSelection(extracted.startOffset + target, extracted.startOffset + target)) return
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun cursorTarget(text: String, selectionStart: Int, selectionEnd: Int, keyCode: Int): Int? {
        fun lineStartAt(position: Int): Int =
            if (position <= 0) 0 else text.lastIndexOf('\n', position - 1) + 1

        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        if (start != end) {
            return if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) start else end
        }
        val cursor = end
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> (cursor - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> (cursor + 1).coerceAtMost(text.length)
            KeyEvent.KEYCODE_DPAD_UP -> {
                val lineStart = lineStartAt(cursor)
                if (lineStart <= 0) cursor else {
                    val previousEnd = lineStart - 1
                    val previousStart = lineStartAt(previousEnd)
                    (previousStart + (cursor - lineStart)).coerceAtMost(previousEnd)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val lineStart = lineStartAt(cursor)
                val lineEnd = text.indexOf('\n', cursor)
                if (lineEnd < 0) cursor else {
                    val nextStart = lineEnd + 1
                    val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                    (nextStart + (cursor - lineStart)).coerceAtMost(nextEnd)
                }
            }
            else -> null
        }
    }

    private fun addSimpleSymbolRow(symbols: List<String>) = addRow(symbols.map(::symbolSpec))

    private fun symbolSpec(symbol: String) = KeySpec(symbol, action = { commit(symbol) })

    private fun renderEmoji() {
        val emojis = if (emojiPage == 0) {
            (loadRecentEmojis() + emojiPages[0]).distinct().take(31)
        } else {
            emojiPages[emojiPage]
        }
        repeat(3) { rowIndex ->
            addRow(emojis.drop(rowIndex * 8).take(8).map { emoji -> KeySpec(emoji, action = { rememberEmoji(emoji); commit(emoji) }) })
        }
        val fourth = emojis.drop(24).take(7).map { emoji -> KeySpec(emoji, action = { rememberEmoji(emoji); commit(emoji) }) }.toMutableList()
        fourth += KeySpec("⌫", weight = 1.72f, action = { deleteOne() }, longAction = { deleteWord() })
        addRow(fourth)
        addRow(
            listOf(
                KeySpec("ABC", weight = 1.58f, action = { mode = KeyboardMode.LETTERS; renderKeyboard() }),
                KeySpec("◀", action = { emojiPage = (emojiPage - 1 + emojiPages.size) % emojiPages.size; renderKeyboard() }),
                KeySpec("${emojiPage + 1}/${emojiPages.size}", weight = 1.15f, action = {}),
                KeySpec("▶", action = { emojiPage = (emojiPage + 1) % emojiPages.size; renderKeyboard() }),
                KeySpec("spasi", weight = 2.8f, action = { commitSpace() }),
                KeySpec(enterKeyLabel(), weight = 1.62f, action = { pressEnter() })
            )
        )
    }


    private fun loadRecentEmojis(): List<String> = runCatching {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("recent_emojis", "[]").orEmpty()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }.getOrDefault(emptyList())

    private fun rememberEmoji(emoji: String) {
        val recent = loadRecentEmojis().toMutableList()
        recent.removeAll { it == emoji }
        recent.add(0, emoji)
        val array = JSONArray()
        recent.take(16).forEach(array::put)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("recent_emojis", array.toString())
            .apply()
    }

    private fun renderClipboard() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Clipboard"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(compactButton("Tempel terbaru") { pasteClipboard() }, LinearLayout.LayoutParams(dp(105), dp(34)))
        header.addView(compactButton("Bersihkan") {
            saveClips(loadClips().filter { it.pinned })
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(82), dp(34)).apply { leftMargin = dp(4) })
        keyboardPanel.addView(header, LinearLayout.LayoutParams(-1, dp(40)))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(3))
        }
        val clips = loadClips()
        if (clips.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada riwayat. Salin teks, lalu buka Clipboard lagi."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(210, Color.red(keyTextColor), Color.green(keyTextColor), Color.blue(keyTextColor)))
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
        } else {
            clips.forEachIndexed { index, clip -> list.addView(clipboardItem(index, clip)) }
        }
        keyboardPanel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun clipboardItem(index: Int, clip: ClipEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        row.addView(compactButton(if (clip.pinned) "📌" else "○") {
            val clips = loadClips().toMutableList()
            if (index in clips.indices) clips[index] = clips[index].copy(pinned = !clips[index].pinned)
            saveClips(clips)
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(45), dp(44)))
        row.addView(TextView(this).apply {
            text = clip.text.replace('\n', ' ')
            textSize = 13f
            maxLines = 2
            setTextColor(Color.WHITE)
            setPadding(dp(9), 0, dp(9), 0)
            background = roundedBackground(Color.rgb(43, 43, 52), 8f)
            setOnClickListener { commitToTarget(clip.text) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(compactButton("🗑") {
            val clips = loadClips().toMutableList()
            if (index in clips.indices) clips.removeAt(index)
            saveClips(clips)
            renderKeyboard()
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        return row
    }

    private fun addRow(specs: List<KeySpec>) {
        val isFirstKeyboardRow = keyboardPanel.childCount == 0
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        specs.forEach { spec ->
            if (spec.label.isEmpty()) {
                row.addView(View(this), LinearLayout.LayoutParams(0, -1, spec.weight))
            } else {
                row.addView(keyView(spec), LinearLayout.LayoutParams(0, -1, spec.weight).apply {
                    setMargins(dp(2), if (isFirstKeyboardRow) dp(1) else dp(3), dp(2), dp(3))
                })
            }
        }
        keyboardPanel.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun keyView(spec: KeySpec): View {
        val isSpecial = spec.label.length > 2 || spec.label in listOf("⇧", "⇪", "⌫", "↵", "◀", "▶")
        val normalColor = if (isSpecial) specialKeyBg else keyBg
        val referenceLargeKey = spec.label in listOf(
            "⇧", "⇪", "⌫", "?123", "↵", "✓", "➤", "→", "←", "🔍"
        )
        val referenceWideKey = referenceLargeKey || spec.label == "spasi"
        val frame = FrameLayout(this).apply {
            // At the old 100% setting the regular caps now fill 90% of their cells instead of
            // only 78%. The new 110% maximum can reach 99%, while clamping prevents overlap.
            scaleX = (keyBoxScale * if (referenceWideKey) 0.98f else 0.90f).coerceAtMost(1f)
            scaleY = (keyBoxScale * if (referenceLargeKey) 0.98f else 0.94f).coerceAtMost(1f)
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Deep lower shadow makes the cap visibly float above the photo/theme.
        frame.addView(View(this).apply {
            background = roundedBackground(Color.argb(190, 4, 4, 7), 22f)
        }, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(2), dp(4), dp(1), 0)
        })

        // Main charcoal key face. This is the surface changed while pressing.
        val keyFace = View(this).apply {
            background = referenceBubbleKeyBackground(pressed = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(2.6f)
        }
        frame.addView(keyFace, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(1), 0, dp(1), dp(4))
        })

        // Inner top rim/highlight: the important detail that creates a molded 3D cap.
        frame.addView(View(this).apply {
            background = referenceTopRimBackground()
            alpha = 0.92f
        }, FrameLayout.LayoutParams(-1, dp(8), Gravity.TOP).apply {
            setMargins(dp(5), dp(3), dp(5), 0)
        })
        frame.addView(TextView(this).apply {
            text = spec.label
            textSize = when {
                spec.label == "spasi" -> 13f
                spec.label.length > 3 -> 12f
                (spec.label.firstOrNull()?.code ?: 0) > 0x2600 -> keyTextSizeSp + 1f
                else -> keyTextSizeSp
            }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (spec.alternate != null) translationY = dpFloat(4f)
            setShadowLayer(dpFloat(1.2f), 0f, dpFloat(1f), Color.BLACK)
            // The molded key face uses elevation, so its legend must have a higher Z value.
            // Without this, Android composites the face above every letter/emoji and the key
            // appears blank even though the TextView still contains the correct label.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(7f)
        }, FrameLayout.LayoutParams(-1, -1))

        spec.alternate?.let { alternate ->
            frame.addView(TextView(this).apply {
                text = alternate
                textSize = 8f
                setTextColor(Color.rgb(205, 202, 214))
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                setShadowLayer(dpFloat(0.8f), 0f, dpFloat(1f), Color.BLACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(7f)
            }, FrameLayout.LayoutParams(-1, -1))
        }


        frame.addView(View(this).apply {
            background = roundedBackground(Color.rgb(202, 105, 255), 2f)
            alpha = 0.96f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(5f)
        }, FrameLayout.LayoutParams(dp(if (referenceLargeKey) 18 else 12), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(1)
        })

        var downX = 0f
        var downY = 0f
        var longTriggered = false
        var actionTriggered = false
        var longRunnable: Runnable? = null
        frame.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longTriggered = false
                    actionTriggered = false
                    showKeyPreview(view, spec)
                    keyFace.background = referenceBubbleKeyBackground(pressed = true)
                    view.translationY = dpFloat(1.7f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(0.6f)
                    spec.longAction?.let { longAction ->
                        longRunnable = Runnable {
                            longTriggered = true
                            if (actionTriggered && spec.alternate != null) deleteInstantCommittedCharacter()
                            spec.alternate?.let(::updateKeyPreview)
                            longAction()
                            keyFeedback(view, longPress = true)
                        }.also { handler.postDelayed(it, longPressDurationMs) }
                    }
                    if (instantKeyResponse) {
                        spec.action()
                        actionTriggered = true
                        keyFeedback(view, longPress = false)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > touchTolerancePx) {
                        longRunnable?.let(handler::removeCallbacks)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longRunnable?.let(handler::removeCallbacks)
                    dismissKeyPreview()
                    keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    view.translationY = 0f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(2.6f)
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        spec.action()
                        keyFeedback(view, longPress = false)
                        actionTriggered = true
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    dismissKeyPreview()
                    keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    view.translationY = 0f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(2.6f)
                    true
                }
                else -> false
            }
        }
        return frame
    }

    private fun keyFeedback(view: View, longPress: Boolean) {
        if (soundEnabled) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEY_CLICK, 0.35f)
        }
        if (vibrationEnabled) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val duration = if (longPress) (vibrationDurationMs + 12).coerceAtMost(100) else vibrationDurationMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else if (longPress) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }


    private fun shouldShowKeyPreview(spec: KeySpec): Boolean {
        if (spec.label.isBlank() || spec.label.matches(Regex("\\d+/\\d+"))) return false
        return spec.label !in setOf(
            "spasi", "?123", "ABC", "⇧", "⇪", "⌫", "↵", "✓", "➤",
            "→", "←", "🔍", "◀", "▶"
        )
    }

    private fun showKeyPreview(anchor: View, spec: KeySpec) {
        if (!shouldShowKeyPreview(spec) || !anchor.isAttachedToWindow) return
        dismissKeyPreview()

        val previewWidth = dp(if (isLandscape()) 48 else 58)
        val previewHeight = dp(if (isLandscape()) 58 else 72)
        val previewLabel = TextView(this).apply {
            text = spec.label
            textSize = when {
                spec.label.length > 2 -> 25f
                else -> 31f
            }
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(dpFloat(1.5f), 0f, dpFloat(1f), Color.BLACK)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(79, 76, 90),
                    Color.rgb(41, 39, 49),
                    Color.rgb(18, 18, 24)
                )
            ).apply {
                cornerRadius = dpFloat(18f)
                setStroke(dp(2), Color.rgb(202, 105, 255))
            }
        }
        val popup = PopupWindow(previewLabel, previewWidth, previewHeight, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            isClippingEnabled = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(10f)
        }
        activeKeyPreview = popup
        activeKeyPreviewLabel = previewLabel

        val xOffset = (anchor.width - previewWidth) / 2
        val yOffset = -(anchor.height + previewHeight + dp(4))
        runCatching { popup.showAsDropDown(anchor, xOffset, yOffset) }
            .onFailure { dismissKeyPreview() }

        keyPreviewDismissRunnable = Runnable {
            if (activeKeyPreview === popup) dismissKeyPreview()
        }.also { handler.postDelayed(it, longPressDurationMs + 350L) }
    }

    private fun updateKeyPreview(label: String) {
        activeKeyPreviewLabel?.text = label
    }

    private fun dismissKeyPreview() {
        keyPreviewDismissRunnable?.let(handler::removeCallbacks)
        keyPreviewDismissRunnable = null
        activeKeyPreviewLabel = null
        activeKeyPreview?.dismiss()
        activeKeyPreview = null
    }

    private fun referenceBubbleKeyBackground(pressed: Boolean): GradientDrawable {
        val colors = if (pressed) {
            intArrayOf(
                Color.rgb(45, 44, 52),
                Color.rgb(25, 24, 31),
                Color.rgb(14, 14, 19)
            )
        } else {
            intArrayOf(
                Color.rgb(69, 67, 78),
                Color.rgb(42, 41, 50),
                Color.rgb(24, 23, 30),
                Color.rgb(15, 15, 20)
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dpFloat(23f)
            setStroke(
                dp(1),
                if (pressed) Color.rgb(67, 65, 77) else Color.rgb(97, 94, 108)
            )
        }
    }

    private fun referenceTopRimBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(125, 255, 255, 255),
                Color.argb(35, 255, 255, 255),
                Color.TRANSPARENT
            )
        ).apply {
            cornerRadius = dpFloat(20f)
        }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpFloat(radiusDp)
    }

    private fun toolbarButton(label: String, widthPx: Int, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 2) 12f else 18f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(keyBg)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(widthPx, dp(utilityHeightDp()))
    }

    private fun compactButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = if (label.length > 6) 10f else 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(Color.WHITE)
        background = roundedBackground(if (label in listOf("➤", "↑", "Pakai")) purple else Color.rgb(50, 50, 60), 8f)
        setOnClickListener { action() }
    }

    private fun pasteClipboard() {
        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let(::commit)
    }

    private fun pasteClipboardIntoAiInput() {
        val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            aiStatus.text = "Clipboard kosong."
            return
        }
        val editable = aiInput.text
        val start = aiInput.selectionStart.coerceIn(0, editable.length)
        val end = aiInput.selectionEnd.coerceIn(0, editable.length)
        editable.replace(minOf(start, end), maxOf(start, end), text)
        aiInput.requestFocus()
        aiComposeActive = true
        refreshSuggestionsSoon()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun utilityHeightDp(): Int = if (isLandscape()) 30 else 39

    private fun toolbarKeyboardGapDp(): Int = if (isLandscape()) 1 else 2

    private fun suggestionHeightDp(): Int = if (isLandscape()) 25 else 34

    private fun activeSuggestionHeightDp(): Int = if (suggestionsEnabled) suggestionHeightDp() else 0

    private fun resizePanelHeightDp(): Int = if (isLandscape()) 30 else 36

    private fun brandBarHeightDp(): Int = if (isLandscape()) 18 else 24

    private fun cursorBottomRowHeightDp(): Int = if (isLandscape()) 34 else 48

    private fun searchHeaderHeightDp(): Int = if (isLandscape()) 27 else 34

    private fun searchSurfaceHeightDp(): Int {
        val screenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        return if (isLandscape()) {
            (screenDp * 0.42f).toInt().coerceIn(118, 205)
        } else {
            (screenDp * 0.44f).toInt().coerceIn(245, 390)
        }
    }

    private fun aiHeaderHeightDp(): Int = if (isLandscape()) 23 else 27

    private fun aiAnswerHeightDp(): Int = if (isLandscape()) 43 else 70

    private fun aiInputHeightDp(): Int = if (isLandscape()) 27 else 34

    private fun aiComposeHeightDp(): Int = if (isLandscape()) 60 else 65

    private fun aiQuickActionHeightDp(): Int = if (isLandscape()) 25 else 30

    private fun currentAiPanelHeightDp(): Int = if (isLandscape()) 162 else 204

    private fun fullscreenKeyboardPanelHeightDp(): Int =
        (baseKeyboardHeightDp - utilityHeightDp() - toolbarKeyboardGapDp() - brandBarHeightDp())
            .coerceAtLeast(if (isLandscape()) 72 else 105)

    private fun addCurrentClipboardToHistory() {
        if (!clipboardHistoryEnabled || !clipboardManager.hasPrimaryClip() || isSensitiveEditor() || clipboardMarkedSensitive()) return
        val text = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val clips = loadClips().toMutableList()
        val existing = clips.firstOrNull { it.text == text }
        clips.removeAll { it.text == text }
        clips.add(0, ClipEntry(text.take(MAX_CLIP_LENGTH), existing?.pinned == true))
        val kept = clips.filter { it.pinned } + clips.filterNot { it.pinned }.take(MAX_CLIPS)
        saveClips(kept.distinctBy { it.text }.take(MAX_CLIPS + 5))
    }

    private fun loadClips(): List<ClipEntry> = runCatching {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("clipboard_items", "[]").orEmpty()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text")
                if (text.isNotBlank()) add(ClipEntry(text, item.optBoolean("pinned", false)))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveClips(clips: List<ClipEntry>) {
        val array = JSONArray()
        clips.forEach { clip -> array.put(JSONObject().put("text", clip.text).put("pinned", clip.pinned)) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("clipboard_items", array.toString()).apply()
    }

    private fun clipboardMarkedSensitive(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clipboardManager.primaryClipDescription?.extras
                ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true

    private fun savedSuggestionEntries(): List<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("personal_phrases", "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.length in 2..140 }
            .distinctBy { it.lowercase() }
            .take(80)
            .toList()

    private fun loadLearnedSuggestions(): List<LearnedSuggestion> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val fromJson = runCatching {
            val array = JSONArray(prefs.getString("learned_suggestions", "[]").orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.length in 2..140) {
                        add(
                            LearnedSuggestion(
                                text = text,
                                uses = item.optInt("uses", 1).coerceAtLeast(1),
                                lastUsedAt = item.optLong("lastUsedAt", 0L)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (fromJson.isNotEmpty()) return fromJson

        val legacy = prefs.getStringSet("learned_words", emptySet()).orEmpty()
            .map { LearnedSuggestion(it, 1, 0L) }
        if (legacy.isNotEmpty()) saveLearnedSuggestions(legacy)
        return legacy
    }

    private fun learnSuggestion(value: String) {
        if (!personalizedLearningEnabled || isSensitiveEditor()) return
        val cleaned = value.trim().replace(Regex("\\s+"), " ").take(140)
        if (cleaned.length < 2) return
        val entries = loadLearnedSuggestions().toMutableList()
        val existingIndex = entries.indexOfFirst { it.text.equals(cleaned, ignoreCase = true) }
        val updated = if (existingIndex >= 0) {
            val existing = entries.removeAt(existingIndex)
            existing.copy(text = cleaned, uses = (existing.uses + 1).coerceAtMost(100_000), lastUsedAt = System.currentTimeMillis())
        } else {
            LearnedSuggestion(cleaned, 1, System.currentTimeMillis())
        }
        entries.add(updated)
        saveLearnedSuggestions(
            entries.sortedWith(
                compareByDescending<LearnedSuggestion> { it.uses }.thenByDescending { it.lastUsedAt }
            ).take(MAX_LEARNED_SUGGESTIONS)
        )
    }

    private fun saveLearnedSuggestions(entries: List<LearnedSuggestion>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("text", entry.text)
                    .put("uses", entry.uses)
                    .put("lastUsedAt", entry.lastUsedAt)
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("learned_suggestions", array.toString())
            .apply()
    }

    private fun learnCurrentBoundary(completed: Boolean = false, terminalMark: String = "") {
        if (isSensitiveEditor()) return
        val before = textBeforeTypingCursor()
        if (personalizedLearningEnabled) {
            SuggestionEngine.learnableEntries(before).forEach(::learnSuggestion)
        }
        if (styleMemoryEnabled) {
            TypingStyleMemory.observeBoundary(
                getSharedPreferences(PREFS, MODE_PRIVATE),
                before,
                completed,
                terminalMark
            )
        }
    }

    private fun isSensitiveEditor(): Boolean {
        if (activeInternalInput() != null) return false
        val info = currentInputEditorInfo ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            info.imeOptions.and(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        ) return true

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun launchScanner() {
        if (!::searchSurfacePanel.isInitialized) return
        if (aiPanelVisible) toggleAiPanel(false)
        if (resizePanelVisible) toggleResizePanel(false)
        searchComposeActive = false
        aiComposeActive = false
        if (::aiInput.isInitialized) aiInput.clearFocus()
        showEmbeddedCameraPanel(resetCandidate = true)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scannerPreviewView?.post { startEmbeddedScanner() }
            return
        }

        scannerStatusText?.text = "Izinkan kamera sekali; setelah itu pemindai tetap di kotak ini."
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CAMERA_PERMISSION_PENDING_KEY, true).apply()
        runCatching {
            startActivity(
                Intent(this, ScannerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onFailure {
            Toast.makeText(this, "Izin kamera tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Accepts old v0.17 scan results and the one-time permission hand-off. */
    private fun consumePendingScanResult() {
        if (!::keyboardPanel.isInitialized) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cameraPending = prefs.getBoolean(CAMERA_PERMISSION_PENDING_KEY, false)
        if (cameraPending &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean(CAMERA_PERMISSION_PENDING_KEY, false).apply()
            showEmbeddedCameraPanel(resetCandidate = false)
            scannerPreviewView?.post { startEmbeddedScanner() }
        }

        if (!prefs.getBoolean(SCAN_READY_KEY, false)) return
        val nonce = prefs.getLong(SCAN_NONCE_KEY, 0L)
        val query = prefs.getString(SCAN_QUERY_KEY, "").orEmpty().trim()
        val directUrl = prefs.getString(SCAN_URL_KEY, "").orEmpty().trim()
        prefs.edit().putBoolean(SCAN_READY_KEY, false).apply()
        if (nonce == lastConsumedScanNonce || (query.isBlank() && directUrl.isBlank())) return
        lastConsumedScanNonce = nonce
        openSearchResults(query.ifBlank { directUrl }, directUrl)
    }

    private fun showSearchSurface() {
        searchSurfaceVisible = true
        searchSurfacePanel.visibility = View.VISIBLE
        applyRootHeight()
    }

    private fun closeSearchSurface() {
        searchComposeActive = false
        searchInput?.clearFocus()
        searchInput = null
        stopEmbeddedScanner()
        destroySearchWebView()
        if (::searchSurfaceContent.isInitialized) searchSurfaceContent.removeAllViews()
        searchSurfaceVisible = false
        if (::searchSurfacePanel.isInitialized) searchSurfacePanel.visibility = View.GONE
        applyRootHeight()
    }

    private fun showEmbeddedCameraPanel(resetCandidate: Boolean) {
        stopEmbeddedScanner()
        destroySearchWebView()
        searchComposeActive = false
        searchInput = null
        if (resetCandidate) {
            scannerBestScore = 0
            scannerSelectedQuery = ""
            scannerSelectedUrl = ""
            scannerLastCandidateAt = 0L
        }
        scannerActive = true
        searchSurfaceContent.removeAllViews()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(18, 18, 23), 20f)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(2), dp(4), dp(2))
            setBackgroundColor(Color.rgb(34, 33, 41))
        }
        val title = TextView(this).apply {
            text = "Kamera penelusuran"
            textSize = if (isLandscape()) 11f else 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(-2, dp(searchHeaderHeightDp())))
        scannerStatusText = TextView(this).apply {
            text = "Pindai teks, kode, label, dokumen, dan objek…"
            textSize = if (isLandscape()) 8f else 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.LTGRAY)
            setPadding(dp(7), 0, dp(3), 0)
        }
        header.addView(scannerStatusText, LinearLayout.LayoutParams(0, dp(searchHeaderHeightDp()), 1f))
        header.addView(compactButton("⚡") { toggleScannerTorch() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())))
        header.addView(compactButton("✕") { closeSearchSurface() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply {
            leftMargin = dp(2)
        })
        content.addView(header, LinearLayout.LayoutParams(-1, dp(searchHeaderHeightDp() + 4)))

        val previewFrame = FrameLayout(this)
        scannerPreviewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    focusScannerAt(event.x, event.y)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                true
            }
        }
        previewFrame.addView(scannerPreviewView, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpFloat(18f)
                setStroke(dp(2), Color.WHITE)
            }
        }, FrameLayout.LayoutParams(-1, if (isLandscape()) dp(72) else dp(140), Gravity.CENTER).apply {
            setMargins(dp(28), 0, dp(28), 0)
        })
        content.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(4), dp(3))
            setBackgroundColor(Color.rgb(31, 30, 38))
        }
        scannerResultText = TextView(this).apply {
            text = scannerSelectedQuery.ifBlank { "Arahkan objek ke kotak, lalu ketuk layar untuk fokus." }
            textSize = if (isLandscape()) 9f else 11f
            maxLines = if (isLandscape()) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(5), 0)
        }
        resultCard.addView(scannerResultText, LinearLayout.LayoutParams(0, if (isLandscape()) dp(36) else dp(49), 1f))
        resultCard.addView(compactButton("Ulang") { showEmbeddedCameraPanel(resetCandidate = true); startEmbeddedScanner() }, LinearLayout.LayoutParams(dp(52), if (isLandscape()) dp(30) else dp(38)))
        scannerSearchButton = compactButton("Cari") { openSearchResults(scannerSelectedQuery, scannerSelectedUrl) }.apply {
            isEnabled = scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank()
        }
        resultCard.addView(scannerSearchButton, LinearLayout.LayoutParams(dp(55), if (isLandscape()) dp(30) else dp(38)).apply {
            leftMargin = dp(3)
        })
        content.addView(resultCard, LinearLayout.LayoutParams(-1, if (isLandscape()) dp(40) else dp(55)))
        searchSurfaceContent.addView(content, FrameLayout.LayoutParams(-1, -1))
        showSearchSurface()
    }

    private fun startEmbeddedScanner() {
        if (!scannerActive || !searchSurfaceVisible) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val previewView = scannerPreviewView ?: return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!scannerActive || scannerPreviewView !== previewView) return@addListener
            runCatching {
                val provider = providerFuture.get()
                val owner = CameraSessionLifecycleOwner().also { it.resume() }
                scannerLifecycleOwner?.destroy()
                scannerLifecycleOwner = owner
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(scannerExecutor, ::analyzeScannerFrame) }
                provider.unbindAll()
                scannerCamera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                scannerStatusText?.text = "Aktif · ketuk objek untuk fokus"
                previewView.postDelayed({ focusScannerAt(previewView.width / 2f, previewView.height / 2f) }, 450L)
            }.onFailure {
                scannerStatusText?.text = "Kamera gagal dimulai. Tutup lalu coba lagi."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopEmbeddedScanner(keepRequested: Boolean = false) {
        scannerLifecycleOwner?.destroy()
        scannerLifecycleOwner = null
        scannerCamera = null
        scannerTorchEnabled = false
        scannerProcessingFrame.set(false)
        if (!keepRequested) scannerActive = false
    }

    private fun focusScannerAt(x: Float, y: Float) {
        val preview = scannerPreviewView ?: return
        val camera = scannerCamera ?: return
        if (preview.width <= 0 || preview.height <= 0) return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun toggleScannerTorch() {
        val camera = scannerCamera
        if (camera == null || !camera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Lampu kamera tidak tersedia.", Toast.LENGTH_SHORT).show()
            return
        }
        scannerTorchEnabled = !scannerTorchEnabled
        camera.cameraControl.enableTorch(scannerTorchEnabled)
        scannerStatusText?.text = if (scannerTorchEnabled) "Lampu aktif · arahkan objek ke kotak" else "Aktif · ketuk objek untuk fokus"
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeScannerFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - scannerLastFrameAt < SCANNER_FRAME_INTERVAL_MS || !scannerProcessingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        scannerLastFrameAt = now
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            finishScannerFrame(imageProxy)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (barcode != null) {
                    publishScannerBarcode(barcode)
                    finishScannerFrame(imageProxy)
                } else {
                    analyzeScannerTextAndObjects(input, imageProxy)
                }
            }
            .addOnFailureListener { analyzeScannerTextAndObjects(input, imageProxy) }
    }

    private fun analyzeScannerTextAndObjects(input: InputImage, imageProxy: ImageProxy) {
        var remaining = 2
        var recognizedText = ""
        var labels = emptyList<Pair<String, Float>>()
        fun completeOne() {
            remaining -= 1
            if (remaining != 0) return
            publishScannerVisualResult(recognizedText, labels)
            finishScannerFrame(imageProxy)
        }
        scannerTextRecognizer.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) recognizedText = task.result?.text.orEmpty()
            completeOne()
        }
        scannerImageLabeler.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                labels = task.result.orEmpty()
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim() to it.confidence }
                    .filter { it.first.isNotBlank() }
                    .distinctBy { it.first.lowercase() }
                    .take(6)
            }
            completeOne()
        }
    }

    private fun publishScannerBarcode(barcode: Barcode) {
        val raw = barcode.rawValue.orEmpty().trim()
        if (raw.isBlank()) return
        val directUrl = when {
            barcode.valueType == Barcode.TYPE_URL -> barcode.url?.url.orEmpty()
            isWebUrl(raw) -> raw
            else -> ""
        }
        val query = when (barcode.valueType) {
            Barcode.TYPE_WIFI -> "WiFi ${barcode.wifi?.ssid.orEmpty()} $raw"
            Barcode.TYPE_EMAIL -> barcode.email?.address.orEmpty().ifBlank { raw }
            Barcode.TYPE_PHONE -> barcode.phone?.number.orEmpty().ifBlank { raw }
            Barcode.TYPE_SMS -> listOf(barcode.sms?.phoneNumber, barcode.sms?.message).filterNotNull().joinToString(" ").ifBlank { raw }
            Barcode.TYPE_GEO -> barcode.geoPoint?.let { "lokasi ${it.lat}, ${it.lng}" } ?: raw
            Barcode.TYPE_CONTACT_INFO -> listOf(
                barcode.contactInfo?.name?.formattedName,
                barcode.contactInfo?.organization,
                barcode.contactInfo?.phones?.firstOrNull()?.number
            ).filterNotNull().joinToString(" ").ifBlank { raw }
            Barcode.TYPE_DRIVER_LICENSE -> barcode.driverLicense?.let {
                "${it.firstName.orEmpty()} ${it.lastName.orEmpty()} ${it.documentType.orEmpty()} ${it.licenseNumber.orEmpty()}".trim()
            } ?: raw
            Barcode.TYPE_ISBN -> "ISBN $raw"
            else -> when (barcode.format) {
                Barcode.FORMAT_EAN_8, Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODE_128 -> "produk kode $raw"
                else -> raw
            }
        }.replace(Regex("\\s+"), " ").trim()
        val label = when {
            directUrl.isNotBlank() -> "Tautan terdeteksi"
            barcode.valueType == Barcode.TYPE_WIFI -> "WiFi terdeteksi"
            barcode.valueType == Barcode.TYPE_GEO -> "Lokasi terdeteksi"
            barcode.valueType == Barcode.TYPE_CONTACT_INFO -> "Kontak terdeteksi"
            else -> "Kode terdeteksi"
        }
        setScannerCandidate(query, directUrl, 1_000, label)
    }

    private fun publishScannerVisualResult(rawText: String, labels: List<Pair<String, Float>>) {
        val lines = rawText.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..100 && it.any(Char::isLetterOrDigit) }
            .distinctBy { it.lowercase() }
            .toList()
        val directUrl = lines.asSequence()
            .mapNotNull { OCR_URL_REGEX.find(it)?.value }
            .map { if (it.startsWith("www.", true)) "https://$it" else it }
            .firstOrNull().orEmpty()
        val rankedLines = lines
            .map { it to scannerTextLineScore(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }
        val usefulLabels = labels
            .filter { it.second >= 0.50f }
            .map { scannerLabelForSearch(it.first) }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(3)
        val queryParts = (rankedLines.take(3) + usefulLabels.take(2))
            .distinctBy { it.lowercase() }
        if (queryParts.isEmpty() && directUrl.isBlank()) return
        val query = directUrl.ifBlank { queryParts.joinToString(" ").take(240) }
        val hasStructuredText = lines.any {
            OCR_EMAIL_REGEX.containsMatchIn(it) || OCR_PHONE_REGEX.containsMatchIn(it) ||
                OCR_PRICE_REGEX.containsMatchIn(it) || OCR_DATE_REGEX.containsMatchIn(it)
        }
        val score = rankedLines.sumOf(::scannerTextLineScore) +
            (labels.firstOrNull()?.second?.times(30)?.toInt() ?: 0) +
            (if (hasStructuredText) 18 else 0) + (if (directUrl.isNotBlank()) 400 else 0)
        val source = when {
            directUrl.isNotBlank() -> "Tautan pada tulisan terdeteksi"
            rankedLines.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks, label, dan objek terdeteksi"
            rankedLines.isNotEmpty() -> if (hasStructuredText) "Data dokumen terdeteksi" else "Tulisan terdeteksi"
            else -> "Objek terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)
    }

    private fun scannerTextLineScore(line: String): Int {
        var score = 0
        if (line.any(Char::isLetter)) score += 12
        if (line.any(Char::isDigit)) score += 5
        if (line.length in 3..42) score += 12 else if (line.length > 75) score -= 8
        if (line.count(Char::isUpperCase) >= 2) score += 8
        if (line.any(Char::isLetter) && line.any(Char::isDigit)) score += 6
        if (OCR_PRICE_REGEX.containsMatchIn(line) || OCR_DATE_REGEX.containsMatchIn(line)) score += 7
        if (line.count { it == ',' || it == ';' } >= 4) score -= 10
        return score
    }

    private fun scannerLabelForSearch(raw: String): String {
        val normalized = raw.trim().lowercase()
        return SCANNER_LABEL_TRANSLATIONS[normalized] ?: raw.trim()
    }

    private fun setScannerCandidate(query: String, url: String, score: Int, source: String) {
        val cleanQuery = query.replace(Regex("\\s+"), " ").trim()
        if (cleanQuery.isBlank() && url.isBlank()) return
        val now = System.currentTimeMillis()
        if (score < scannerBestScore && now - scannerLastCandidateAt < SCANNER_CANDIDATE_HOLD_MS) return
        scannerBestScore = score
        scannerLastCandidateAt = now
        scannerSelectedQuery = cleanQuery.ifBlank { url }
        scannerSelectedUrl = url
        handler.post {
            scannerStatusText?.text = "$source · tekan Cari"
            scannerResultText?.text = scannerSelectedQuery
            scannerSearchButton?.isEnabled = true
        }
    }

    private fun finishScannerFrame(imageProxy: ImageProxy) {
        imageProxy.close()
        scannerProcessingFrame.set(false)
    }

    private fun openSearchResults(query: String, directUrl: String = "") {
        val cleanQuery = query.trim().ifBlank { directUrl.trim() }
        if (cleanQuery.isBlank()) return
        searchQuery = cleanQuery
        searchUrl = directUrl.takeIf(::isWebUrl) ?: if (isWebUrl(cleanQuery)) cleanQuery else googleSearchUrl(cleanQuery)
        stopEmbeddedScanner()
        showSearchWebPanel()
    }

    private fun showSearchWebPanel() {
        destroySearchWebView()
        scannerPreviewView = null
        searchSurfaceContent.removeAllViews()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 20f)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(3), dp(2))
            setBackgroundColor(Color.rgb(239, 239, 243))
        }
        val queryInput = EditText(this).apply {
            setText(searchQuery)
            textSize = if (isLandscape()) 10f else 12f
            setTextColor(Color.rgb(28, 28, 33))
            setHintTextColor(Color.GRAY)
            hint = "Ketik pencarian…"
            maxLines = 1
            isSingleLine = true
            showSoftInputOnFocus = false
            setPadding(dp(9), 0, dp(7), 0)
            background = roundedBackground(Color.WHITE, 12f)
            setSelection(text.length)
            setOnClickListener {
                searchComposeActive = true
                aiComposeActive = false
            }
            setOnFocusChangeListener { _, focused -> searchComposeActive = focused }
        }
        searchInput = queryInput
        header.addView(queryInput, LinearLayout.LayoutParams(0, dp(searchHeaderHeightDp()), 1f))
        header.addView(compactButton("Cari") { performSearchFromInput() }, LinearLayout.LayoutParams(dp(46), dp(searchHeaderHeightDp())).apply { leftMargin = dp(3) })
        header.addView(compactButton("📷") { launchScanner() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })
        header.addView(compactButton("↗") { openExternalLink(searchUrl) }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })
        header.addView(compactButton("✕") { closeSearchSurface() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })
        content.addView(header, LinearLayout.LayoutParams(-1, dp(searchHeaderHeightDp() + 5)))

        var userTouchedPage = false
        val webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString + " AIAdsKeyboard/0.18"
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) userTouchedPage = true
                false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    val isUserNavigation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        request?.hasGesture() == true || userTouchedPage
                    } else userTouchedPage
                    userTouchedPage = false
                    if (!isUserNavigation || target.isBlank()) return false
                    openExternalLink(target)
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val target = url.orEmpty()
                    if (!userTouchedPage || target.isBlank()) return false
                    userTouchedPage = false
                    openExternalLink(target)
                    return true
                }
            }
            loadUrl(searchUrl)
        }
        searchWebView = webView
        content.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        searchSurfaceContent.addView(content, FrameLayout.LayoutParams(-1, -1))
        showSearchSurface()
        queryInput.requestFocus()
        searchComposeActive = true
    }

    private fun performSearchFromInput() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return
        searchQuery = query
        searchUrl = if (isWebUrl(query)) query else googleSearchUrl(query)
        searchWebView?.loadUrl(searchUrl)
        searchInput?.setSelection(searchInput?.text?.length ?: 0)
    }

    private fun openExternalLink(rawUrl: String) {
        val url = unwrapSearchRedirect(rawUrl)
        if (url.isBlank()) return
        runCatching {
            if (url.startsWith("intent://", ignoreCase = true)) {
                val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (parsedIntent.resolveActivity(packageManager) != null) {
                    startActivity(parsedIntent)
                    return
                }
                val fallback = parsedIntent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) openExternalLink(fallback)
                return
            }

            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                val genericHandlers = packageManager
                    .queryIntentActivities(genericIntent, 0)
                    .map { it.activityInfo.packageName }
                    .toSet()
                packageManager.queryIntentActivities(intent, 0)
                    .firstOrNull { it.activityInfo.packageName !in genericHandlers && it.activityInfo.packageName != packageName }
                    ?.activityInfo
                    ?.packageName
                    ?.let(intent::setPackage)
            }
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "Tautan tidak didukung oleh aplikasi di HP ini.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unwrapSearchRedirect(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        val host = uri.host.orEmpty()
        if (host.endsWith("google.com") && uri.path == "/url") {
            return uri.getQueryParameter("url")
                ?: uri.getQueryParameter("q")
                ?: rawUrl
        }
        return rawUrl
    }

    private fun googleSearchUrl(query: String): String = Uri.Builder()
        .scheme("https")
        .authority("www.google.com")
        .path("search")
        .appendQueryParameter("q", query)
        .build()
        .toString()

    private fun isWebUrl(value: String): Boolean = runCatching {
        Uri.parse(value).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun destroySearchWebView() {
        searchWebView?.let { webView ->
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        searchWebView = null
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            if (::aiStatus.isInitialized) aiStatus.text = "Buka aplikasi AI Ads Keyboard untuk pengaturan"
        }
    }

    private fun activeInternalInput(): EditText? = when {
        searchComposeActive -> searchInput
        aiComposeActive && ::aiInput.isInitialized -> aiInput
        else -> null
    }

    private fun deleteOne() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            when {
                start != end -> editable.delete(minOf(start, end), maxOf(start, end))
                start > 0 -> editable.delete(start - 1, start)
            }
        } else {
            val ic = currentInputConnection ?: return
            if (!deleteSelectedText(ic)) deletePreviousCharacterCompat(ic)
        }
        refreshSuggestionsSoon()
    }

    private fun deleteWord() {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            if (start != end) {
                editable.delete(minOf(start, end), maxOf(start, end))
            } else if (start > 0) {
                val before = editable.substring(0, start)
                val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
                editable.delete((start - count).coerceAtLeast(0), start)
            }
            refreshSuggestionsSoon()
            return
        }

        val ic = currentInputConnection ?: return
        if (deleteSelectedText(ic)) {
            refreshSuggestionsSoon()
            return
        }
        val before = runCatching { ic.getTextBeforeCursor(100, 0)?.toString().orEmpty() }.getOrDefault("")
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }
        refreshSuggestionsSoon()
    }

    /**
     * Some browser/WebView/OTP fields need a real DEL event. Trust the event result and never
     * immediately follow it with deleteSurroundingText: many editors apply the key event
     * asynchronously, and doing both can delete two characters for a single tap.
     */
    private fun deletePreviousCharacterCompat(ic: InputConnection): Boolean {
        if (sendDeleteKeyEvent(ic)) return true
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }.getOrDefault(false)
    }

    private fun sendDeleteKeyEvent(ic: InputConnection): Boolean = runCatching {
        val now = SystemClock.uptimeMillis()
        val down = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
        val up = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        down || up
    }.getOrDefault(false)

    /**
     * When instant response is enabled, the normal letter is committed on ACTION_DOWN before a
     * long-press can fire. Replacing it with the alternate symbol must delete exactly that one
     * freshly committed character. Do not use the general backspace compatibility path here,
     * because a hardware DEL event can race with commitText and remove the previous character too.
     */
    private fun deleteInstantCommittedCharacter(): Boolean {
        if (aiComposeActive && ::aiInput.isInitialized) {
            val editable = aiInput.text
            val start = aiInput.selectionStart.coerceAtLeast(0)
            val end = aiInput.selectionEnd.coerceAtLeast(0)
            return when {
                start != end -> {
                    editable.delete(minOf(start, end), maxOf(start, end))
                    true
                }
                start > 0 -> {
                    editable.delete(start - 1, start)
                    true
                }
                else -> false
            }
        }
        val ic = currentInputConnection ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }.getOrDefault(false)
    }

    private fun deleteSelectedText(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isEmpty()) return false
        ic.commitText("", 1)
        return true
    }


    private fun enterKeyLabel(): String {
        if (searchComposeActive) return "🔍"
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_GO -> "→"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_PREVIOUS -> "←"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "↵"
        }
    }

    private fun pressEnter() {
        if (searchComposeActive) {
            performSearchFromInput()
            return
        }
        if (aiComposeActive) {
            runAiConversation()
            return
        }
        learnCurrentBoundary(completed = true)
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
        if (automaticCapitalizationEnabled) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
        refreshSuggestionsSoon()
    }

    private fun commit(text: String) {
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceIn(0, editable.length)
            val end = internalInput.selectionEnd.coerceIn(0, editable.length)
            editable.replace(minOf(start, end), maxOf(start, end), text)
            internalInput.setSelection((minOf(start, end) + text.length).coerceAtMost(editable.length))
            refreshSuggestionsSoon()
        } else {
            commitToTarget(text)
        }
    }

    private fun commitToTarget(text: String) {
        currentInputConnection?.commitText(text, 1)
        refreshSuggestionsSoon()
    }

    private fun commitSpace() {
        if (activeInternalInput() != null) {
            learnCurrentBoundary()
            commit(" ")
            return
        }
        learnCurrentBoundary()
        val now = System.currentTimeMillis()
        if (doubleSpacePeriodEnabled && now - lastSpaceAt < 700) {
            val before = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.endsWith(" ") && before.dropLast(1).lastOrNull()?.isLetterOrDigit() == true) {
                currentInputConnection?.deleteSurroundingText(1, 0)
                currentInputConnection?.commitText(". ", 1)
                lastSpaceAt = 0L
                if (automaticCapitalizationEnabled) shift = true
                if (mode == KeyboardMode.LETTERS) renderKeyboard()
                return
            }
        }
        commitToTarget(" ")
        lastSpaceAt = now
    }

    private fun commitPunctuation(mark: String) {
        learnCurrentBoundary(completed = mark in listOf(".", "?", "!"), terminalMark = mark)
        commit(if (activeInternalInput() == null && punctuationSpaceEnabled) "$mark " else mark)
        if (automaticCapitalizationEnabled && mark in listOf(".", "?", "!")) {
            shift = true
            if (mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun updateAutomaticShift() {
        if (capsLock) return
        val before = currentInputConnection?.getTextBeforeCursor(120, 0)?.toString().orEmpty()
        shift = before.isBlank() || before.trimEnd().lastOrNull() in listOf('.', '?', '!') || before.endsWith("\n")
    }

    private fun editorContext(ic: InputConnection): String {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) return selected
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty()
        }.getOrDefault("")
        if (extracted.isNotBlank()) return extracted.takeLast(MAX_AI_CONTEXT_CHARS)
        val before = ic.getTextBeforeCursor(MAX_AI_CONTEXT_CHARS / 2, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_AI_CONTEXT_CHARS / 2, 0)?.toString().orEmpty()
        return (before + after).takeLast(MAX_AI_CONTEXT_CHARS)
    }

    private fun context(ic: InputConnection): String {
        val editor = editorContext(ic)
        if (editor.isNotBlank()) return editor
        val screen = screenContextNow()
        if (screen != null) return screen.primaryText.ifBlank { screen.text }
        return recentSharedContext()
    }

    private fun automaticReplyContext(ic: InputConnection): String {
        val screen = screenContextNow()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val editor = editorContext(ic)
        val copied = if (clipboardMarkedSensitive()) "" else clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        val saved = recentSharedContext()
        return listOf(
            "Konteks percakapan/postingan yang terlihat" to screen?.primaryText.orEmpty(),
            "Teks yang sengaja dipilih atau dibagikan" to saved,
            "Teks yang dipilih di kolom aktif" to selected,
            "Clipboard — gunakan hanya jika cocok dengan percakapan" to copied,
            "Draf pengguna di kolom balasan — jangan dianggap pesan lawan bicara" to editor,
            "TARGET WAJIB DIBALAS — pesan masuk terakhir yang belum dibalas" to
                screen?.latestIncomingText.orEmpty().ifBlank { screen?.primaryText.orEmpty() }
        )
            .map { (label, value) -> label to value.trim() }
            .filter { (_, value) -> value.isNotBlank() }
            .joinToString("\n\n") { (label, value) -> "[$label]\n$value" }
            .takeLast(MAX_REPLY_CONTEXT_CHARS)
    }

    /**
     * Chooses a translation source without requiring text to be pasted into the AI composer.
     * An explicit selection wins; otherwise the currently visible application is read on demand.
     * Shared text, the active editor, and the clipboard are safe fallbacks when Accessibility
     * cannot expose the application's view hierarchy.
     */
    private fun automaticTranslationContext(ic: InputConnection): String {
        val selected = ic.getSelectedText(0)?.toString().orEmpty().trim()
        if (selected.isNotBlank()) return selected.takeLast(MAX_AI_CONTEXT_CHARS)

        val snapshot = screenContextNow()
        val screen = snapshot?.primaryText.orEmpty().ifBlank { snapshot?.text.orEmpty() }.trim()
        if (screen.isNotBlank()) return screen.takeLast(MAX_AI_CONTEXT_CHARS)

        val shared = recentSharedContext().trim()
        if (shared.isNotBlank()) return shared.takeLast(MAX_AI_CONTEXT_CHARS)

        val editor = editorContext(ic).trim()
        if (editor.isNotBlank()) return editor.takeLast(MAX_AI_CONTEXT_CHARS)

        if (!clipboardMarkedSensitive()) {
            val copied = clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
                .trim()
            if (copied.isNotBlank()) return copied.takeLast(MAX_AI_CONTEXT_CHARS)
        }
        return ""
    }

    private fun conversationContext(ic: InputConnection?): String {
        val screen = screenContextNow()
        val editor = ic?.let(::editorContext).orEmpty()
        val shared = recentSharedContext()
        return listOf(
            "Teks layar aplikasi saat ini" to screen?.text.orEmpty(),
            "Isi kolom tulisan saat ini" to editor,
            "Teks yang dipilih, dibagikan, atau dibaca dari gambar" to shared
        )
            .filter { (_, value) -> value.isNotBlank() }
            .distinctBy { (_, value) -> value }
            .joinToString("\n\n") { (label, value) -> "[$label]\n$value" }
            .takeLast(MAX_AI_CONTEXT_CHARS)
    }

    private fun screenContextNow(): ScreenTextSnapshot? {
        if (isSensitiveEditor()) return null
        return ScreenTextAccessibilityService.captureNow(currentInputEditorInfo?.packageName)
    }

    private fun recentSharedContext(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedAt = prefs.getLong("shared_context_updated_at", 0L)
        return prefs.getString("shared_context", "").orEmpty()
            .takeIf { System.currentTimeMillis() - savedAt <= SHARED_CONTEXT_MAX_AGE_MS }
            .orEmpty()
    }

    private fun aiSettings() = getSharedPreferences(PREFS, MODE_PRIVATE).let { prefs ->
        AiSettings(
            primaryProvider = AiProvider.fromId(prefs.getString("provider", null)),
            openRouterApiKey = prefs.getString("openrouter_api_key", prefs.getString("api_key", "")).orEmpty(),
            openRouterModel = prefs.getString("openrouter_model", "openrouter/free").orEmpty(),
            tabiApiKey = prefs.getString("tabi_api_key", "").orEmpty(),
            tabiBaseUrl = prefs.getString("tabi_base_url", "https://tabitoken.com").orEmpty(),
            tabiModel = prefs.getString("tabi_model", "claude-opus-5").orEmpty(),
            fallbackEnabled = prefs.getBoolean("fallback_enabled", false),
            referenceUrls = prefs.getString("reference_urls", "").orEmpty()
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_REFERENCE_URLS)
                .toList(),
            writingStyleProfile = if (prefs.getBoolean("style_memory_enabled", true)) {
                TypingStyleMemory.prompt(prefs)
            } else {
                ""
            }
        )
    }

    private fun activeProviderLabel(): String {
        val provider = AiProvider.fromId(getSharedPreferences(PREFS, MODE_PRIVATE).getString("provider", null))
        return "Provider: ${provider.label}"
    }

    private fun runAi(action: String) {
        val ic = currentInputConnection ?: return
        if (!aiPanelVisible) toggleAiPanel(true)
        aiComposeActive = false
        aiInput.clearFocus()
        val input = when (action) {
            "Balas" -> automaticReplyContext(ic)
            "Terjemah" -> automaticTranslationContext(ic)
            else -> context(ic)
        }
        if (action in listOf("Balas", "Terjemah") && input.isBlank()) {
            aiStatus.text = if (action == "Balas") {
                "Belum ada pesan untuk dibalas."
            } else {
                "Belum ada teks yang dapat diterjemahkan."
            }
            aiAnswer.text = "Aktifkan Akses Teks Layar, buka kembali aplikasi yang berisi teks, lalu tekan $action lagi. Teks pilihan, Bagikan, atau clipboard tetap dapat dipakai sebagai cadangan."
            return
        }
        aiStatus.text = when (action) {
            "Balas" -> "Membaca percakapan layar dan menyiapkan balasan…"
            "Terjemah" -> "Membaca teks layar dan menerjemahkan otomatis…"
            else -> "$action sedang diproses…"
        }
        thread {
            val result = AiClient.transform(aiSettings(), action, input)
            aiStatus.post {
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiStatus.text = error.message ?: "Terjadi kesalahan"
                    aiAnswer.text = error.message ?: "AI gagal merespons."
                }
            }
        }
    }

    private fun runAiConversation() {
        val prompt = aiInput.text.toString().trim()
        if (prompt.isBlank()) {
            aiStatus.text = "Tulis pesan untuk AI terlebih dahulu."
            aiInput.requestFocus()
            aiComposeActive = true
            return
        }
        val ic = currentInputConnection
        if (styleMemoryEnabled && prompt.isNotBlank()) {
            TypingStyleMemory.observeCompletedText(getSharedPreferences(PREFS, MODE_PRIVATE), prompt)
        }
        val appContext = conversationContext(ic)
        val history = conversationHistory.takeLast(4).joinToString("\n") { (role, text) -> "$role: $text" }
        aiStatus.text = "AI sedang menjawab…"
        aiAnswer.text = "Menunggu jawaban…"
        aiInput.setText("")
        aiComposeActive = false
        aiInput.clearFocus()
        thread {
            val result = AiClient.chat(aiSettings(), prompt, appContext, history)
            aiStatus.post {
                result.onSuccess { response ->
                    conversationHistory += "Pengguna" to prompt
                    conversationHistory += "AI" to response.text
                    while (conversationHistory.size > 8) conversationHistory.removeAt(0)
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Jawaban via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiAnswer.text = "Jawaban AI akan muncul di sini."
                    aiStatus.text = error.message ?: "Terjadi kesalahan"
                }
            }
        }
    }

    private fun insertPendingResult() {
        pendingText?.let { result ->
            commitToTarget(result)
            pendingText = null
            aiStatus.text = "Jawaban dimasukkan ke kolom teks."
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpFloat(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val PREFS = "riyan_ai"
        private const val HEIGHT_PORTRAIT_KEY = "keyboard_height_portrait_dp"
        private const val HEIGHT_LANDSCAPE_KEY = "keyboard_height_landscape_dp"
        private const val DEFAULT_KEYBOARD_HEIGHT_PORTRAIT_DP = 220
        private const val DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE_DP = 120
        private const val OLD_DEFAULT_KEYBOARD_HEIGHT_DP = 350
        private const val MIN_KEYBOARD_HEIGHT_PORTRAIT_DP = 170
        private const val MAX_KEYBOARD_HEIGHT_PORTRAIT_DP = 330
        private const val MIN_KEYBOARD_HEIGHT_LANDSCAPE_DP = 90
        private const val MAX_KEYBOARD_HEIGHT_LANDSCAPE_DP = 190
        private const val KEYBOARD_LAYOUT_VERSION = 9
        private const val INSTANT_RESPONSE_THRESHOLD = 150
        private const val DOUBLE_TAP_SHIFT_MS = 420L
        private const val SUGGESTION_AUTO_HIDE_MS = 2_600L
        private const val CURSOR_REPEAT_DELAY_MS = 330L
        private const val CURSOR_REPEAT_INTERVAL_MS = 65L
        private const val SHARED_CONTEXT_MAX_AGE_MS = 30L * 60L * 1000L
        private const val MAX_AI_CONTEXT_CHARS = 24_000
        private const val MAX_REPLY_CONTEXT_CHARS = 28_000
        private const val MAX_REFERENCE_URLS = 6
        private const val MAX_CLIPS = 12
        private const val MAX_CLIP_LENGTH = 1200
        private const val MAX_LEARNED_SUGGESTIONS = 180
        private const val SCAN_READY_KEY = "camera_search_ready"
        private const val SCAN_NONCE_KEY = "camera_search_nonce"
        private const val SCAN_QUERY_KEY = "camera_search_query"
        private const val SCAN_URL_KEY = "camera_search_url"
        private const val CAMERA_PERMISSION_PENDING_KEY = "camera_permission_pending"
        private const val SCANNER_FRAME_INTERVAL_MS = 260L
        private const val SCANNER_CANDIDATE_HOLD_MS = 1_250L

        private val OCR_URL_REGEX = Regex("""(?i)\b(?:https?://|www\.)[^\s<>\"']+""")
        private val OCR_EMAIL_REGEX = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
        private val OCR_PHONE_REGEX = Regex("""(?<!\d)(?:\+?62|0)[\d\s-]{8,15}(?!\d)""")
        private val OCR_PRICE_REGEX = Regex("""(?i)(?:Rp\.?|IDR|USD|\$)\s?\d[\d.,]*""")
        private val OCR_DATE_REGEX = Regex("""(?i)\b(?:\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2}\s+(?:jan|feb|mar|apr|mei|jun|jul|agu|sep|okt|nov|des)[a-z]*\s+\d{2,4})\b""")

        private val SCANNER_LABEL_TRANSLATIONS = mapOf(
            "food" to "makanan",
            "drink" to "minuman",
            "bottle" to "botol",
            "packaged goods" to "produk kemasan",
            "personal care" to "produk perawatan diri",
            "cosmetics" to "kosmetik",
            "medicine" to "obat",
            "electronic device" to "perangkat elektronik",
            "mobile phone" to "ponsel",
            "computer" to "komputer",
            "vehicle" to "kendaraan",
            "plant" to "tanaman",
            "animal" to "hewan",
            "document" to "dokumen",
            "receipt" to "struk belanja",
            "book" to "buku",
            "clothing" to "pakaian",
            "furniture" to "furnitur",
            "tool" to "alat",
            "toy" to "mainan",
            "fruit" to "buah",
            "vegetable" to "sayuran"
        )
    }
}
