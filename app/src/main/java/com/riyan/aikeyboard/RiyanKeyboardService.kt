package com.riyan.aikeyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
import androidx.core.content.res.ResourcesCompat
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
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
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
    private data class ScannerTextLine(val text: String, val bounds: Rect?)
    private data class ScannerObjectSignal(
        val bounds: Rect,
        val labels: List<Pair<String, Float>>
    )

    private lateinit var inputHost: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var utilityBar: LinearLayout
    private lateinit var utilityBarFrame: FrameLayout
    private lateinit var resizePanel: LinearLayout
    private lateinit var suggestionBar: LinearLayout
    private lateinit var bottomBrandBar: LinearLayout
    private lateinit var settingsPanel: KeyboardSettingsOverlay
    private lateinit var settingsScrim: View
    private var settingsPanelVisible = false
    private var settingsEditingMode = false
    private lateinit var aiPanel: LinearLayout
    private lateinit var aiStatus: TextView
    private lateinit var aiAnswer: TextView
    private lateinit var aiAnswerScroll: ScrollView
    private lateinit var aiInput: EditText
    private lateinit var aiFullscreenButton: ImageButton
    private lateinit var heightLabel: TextView
    private lateinit var searchSurfacePanel: FrameLayout
    private lateinit var searchSurfaceContent: FrameLayout
    private var searchWebView: WebView? = null
    private var braveBrowserPanel: BraveBrowserPanel? = null
    private var searchInput: EditText? = null
    private var scannerPreviewView: PreviewView? = null
    private var scannerGalleryImageView: ImageView? = null
    private var scannerStatusText: TextView? = null
    private var scannerResultText: TextView? = null
    private var scannerSearchButton: Button? = null
    private var scannerCamera: Camera? = null
    private var scannerLifecycleOwner: CameraSessionLifecycleOwner? = null
    private var searchSurfaceVisible = false
    private var searchComposeActive = false
    private var searchWebComposeActive = false
    private var searchWebBigMode = false
    private var scannerActive = false
    private var scannerTorchEnabled = false
    private var scannerGalleryUri: Uri? = null
    private var scannerGalleryPreviewBitmap: Bitmap? = null
    private var internalGalleryPanel: InternalGalleryPanel? = null
    private var scannerCameraZoomRatio = 1f
    private var scannerCameraPanX = 0f
    private var scannerCameraPanY = 0f
    private var scannerGalleryZoom = 1f
    private var scannerGalleryPanX = 0f
    private var scannerGalleryPanY = 0f
    private var scannerGalleryFocusX = 0.5f
    private var scannerGalleryFocusY = 0.5f
    private var scannerBestScore = 0
    private var scannerSelectedQuery = ""
    private var scannerSelectedUrl = ""
    private var scannerVisualSearchPreferred = true
    private var scannerMainProductText = ""
    private var scannerBingVisualQuery = ""
    private var scannerLocalShapeHint = ""
    private var scannerLastFrameAt = 0L
    private var scannerLastCandidateAt = 0L
    private var scannerPendingQuery = ""
    private var scannerPendingHits = 0
    private var scannerPendingAt = 0L
    private val scannerProcessingFrame = AtomicBoolean(false)
    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val scannerTextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val scannerObjectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
    }
    private val scannerImageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.40f)
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
    private var fastTypingMode = false
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
    private var lastRenderedEnterLabel = ""
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

    private val aiRegularTypeface by lazy {
        ResourcesCompat.getFont(this, R.font.kalam_regular)
            ?: Typeface.create("cursive", Typeface.NORMAL)
    }
    private val aiBoldTypeface by lazy {
        ResourcesCompat.getFont(this, R.font.kalam_bold)
            ?: Typeface.create("cursive", Typeface.BOLD)
    }

    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private val keyboardAudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val keyboardVibrator by lazy { getSystemService(VIBRATOR_SERVICE) as Vibrator }
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
        dismissKeyPreview(release = true)
        handler.removeCallbacksAndMessages(null)
        internalGalleryPanel?.release()
        internalGalleryPanel = null
        stopEmbeddedScanner()
        braveBrowserPanel?.release()
        braveBrowserPanel = null
        searchWebView = null
        destroySearchWebView()
        barcodeScanner.close()
        scannerTextRecognizer.close()
        scannerImageLabeler.close()
        scannerObjectDetector.close()
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
        if (!fastTypingMode) refreshSuggestionsSoon()
        refreshEnterKeyIfNeeded()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (::keyboardPanel.isInitialized) {
            handler.post { refreshEnterKeyIfNeeded() }
        }
    }

    override fun onCreateInputView(): View {
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
        if (settingsPanelVisible && ::settingsPanel.isInitialized) settingsPanel.refreshExternalChanges()
        if (searchSurfaceVisible && scannerActive && scannerGalleryUri == null &&
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
        instantKeyResponse = true
        fastTypingMode = sensitivity >= 300
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
                text = "AI Ads Keyboard · v0.20"
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
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = roundedStrokedBackground(Color.BLACK, 12f, purple, 2)
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerControlHeight = dp(aiHeaderControlHeightDp())
        header.addView(TextView(this).apply {
            text = "✨ Obrolan AI"
            textSize = if (isLandscape()) 15f else 18f
            setTextColor(Color.rgb(43, 40, 50))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(10), 0)
            typeface = aiBoldTypeface
            background = roundedBackground(purple, 8f)
        }, LinearLayout.LayoutParams(-2, headerControlHeight))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(aiPanelButton("Hapus") {
            conversationHistory.clear()
            pendingText = null
            aiAnswer.text = "Jawaban AI akan muncul di sini."
            aiStatus.text = activeProviderLabel()
        }, LinearLayout.LayoutParams(dp(58), headerControlHeight))
        aiFullscreenButton = premiumIconButton(
            R.drawable.ic_fullscreen_modern,
            "Buka obrolan AI layar penuh"
        ) { toggleAiFullscreen() }
        header.addView(aiFullscreenButton, LinearLayout.LayoutParams(dp(40), headerControlHeight).apply {
            leftMargin = dp(2)
        })
        header.addView(
            premiumIconButton(R.drawable.ic_close_modern, "Tutup obrolan AI") { toggleAiPanel(false) },
            LinearLayout.LayoutParams(dp(40), headerControlHeight).apply { leftMargin = dp(2) }
        )
        aiPanel.addView(header, LinearLayout.LayoutParams(-1, dp(aiHeaderHeightDp())))

        aiAnswer = TextView(this).apply {
            text = "Jawaban AI akan muncul di sini."
            textSize = if (isLandscape()) 12f else 14f
            setTextColor(Color.rgb(224, 222, 231))
            setPadding(dp(7), dp(5), dp(7), dp(4))
            typeface = aiRegularTypeface
            setOnClickListener { insertPendingResult() }
        }
        aiAnswerScroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(aiAnswer, ViewGroup.LayoutParams(-1, -2))
        }
        aiPanel.addView(aiAnswerScroll, LinearLayout.LayoutParams(-1, dp(aiAnswerHeightDp())).apply { topMargin = dp(3) })

        val composeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = roundedStrokedBackground(Color.rgb(14, 14, 16), 15f, purple, 2)
        }
        aiInput = EditText(this).apply {
            hint = "Ketik pesan untuk AI…"
            textSize = if (isLandscape()) 12f else 14f
            maxLines = 3
            minLines = 1
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(137, 134, 146))
            showSoftInputOnFocus = false
            setPadding(dp(8), 0, dp(8), 0)
            background = null
            typeface = aiRegularTypeface
            setOnClickListener { aiComposeActive = true }
            setOnFocusChangeListener { _, hasFocus -> aiComposeActive = hasFocus }
        }
        composeCard.addView(aiInput, LinearLayout.LayoutParams(-1, dp(aiInputHeightDp())))

        val composeFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composeFooter.addView(aiPanelButton("＋") { pasteClipboardIntoAiInput() }, LinearLayout.LayoutParams(dp(38), dp(aiComposeFooterHeightDp())))
        aiStatus = TextView(this).apply {
            text = activeProviderLabel()
            textSize = if (isLandscape()) 9f else 11f
            maxLines = 1
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(3), 0)
            typeface = aiRegularTypeface
        }
        composeFooter.addView(aiStatus, LinearLayout.LayoutParams(0, dp(aiComposeFooterHeightDp()), 1f))
        composeFooter.addView(aiPanelButton("Pakai", primary = true) { insertPendingResult() }, LinearLayout.LayoutParams(dp(58), dp(aiComposeFooterHeightDp())))
        composeFooter.addView(aiPanelButton("↑", primary = true) { runAiConversation() }, LinearLayout.LayoutParams(dp(42), dp(aiComposeFooterHeightDp())).apply { leftMargin = dp(4) })
        composeCard.addView(composeFooter)
        aiPanel.addView(composeCard, LinearLayout.LayoutParams(-1, dp(aiComposeHeightDp())).apply { topMargin = dp(3) })

        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("Perbaiki", "Balas", "Terjemah", "Ringkas", "Santai", "Sopan").forEach { action ->
            quickActions.addView(aiPanelButton(action) { runAi(action) }, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        aiPanel.addView(quickActions, LinearLayout.LayoutParams(-1, dp(aiQuickActionHeightDp())).apply {
            topMargin = dp(3)
        })
        root.addView(aiPanel, LinearLayout.LayoutParams(-1, dp(currentAiPanelHeightDp())).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })
    }

    /**
     * The camera and search page live above the utility bar so the actual keyboard never gets
     * replaced. This also avoids opening a full-screen scanner activity after camera permission
     * has been granted.
     */
    private fun addSearchSurfacePanel() {
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
    }

    private fun addSettingsPanel() {
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

    private fun addUtilityBar() {
        val barHeight = dp(utilityHeightDp())
        utilityBarFrame = FrameLayout(this).apply {
            setBackgroundColor(keyBg)
            clipChildren = false
            clipToPadding = false
        }

        utilityBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), 0)
            setBackgroundColor(keyBg)
        }

        utilityBar.addView(toolbarButton("✦ AI", dp(50)) { toggleAiPanel() })
        utilityBar.addView(toolbarButton("⌨", dp(36)) {
            aiComposeActive = false
            mode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("😊", dp(36)) {
            aiComposeActive = false
            emojiPage = 0
            mode = KeyboardMode.EMOJI
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("📋", dp(36)) {
            aiComposeActive = false
            addCurrentClipboardToHistory()
            mode = KeyboardMode.CLIPBOARD
            renderKeyboard()
        })
        utilityBar.addView(toolbarButton("Kursor", dp(50)) {
            aiComposeActive = false
            mode = KeyboardMode.CURSOR
            renderKeyboard()
        })
        utilityBar.addView(
            toolbarIconButton(R.drawable.ic_camera_modern, "Kamera penelusuran", dp(36)) { launchScanner() }
        )
        utilityBar.addView(toolbarButton("↕", dp(34)) { toggleResizePanel() })

        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(1), 0, dp(1), 0)
            setBackgroundColor(keyBg)
            visibility = View.VISIBLE
        }
        utilityBar.addView(suggestionBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        utilityBar.addView(
            toolbarIconButton(R.drawable.ic_settings_modern, "Pengaturan", dp(38)) {
                startActivity(
                    Intent(this@RiyanKeyboardService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )

        utilityBarFrame.addView(utilityBar, FrameLayout.LayoutParams(-1, barHeight))

        // Purple light strip modeled after the reference: soft glow plus a crisp core line.
        utilityBarFrame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.TRANSPARENT,
                Color.argb(150, 152, 73, 255),
                Color.argb(230, 193, 92, 255),
                Color.argb(150, 152, 73, 255),
                Color.TRANSPARENT
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(5f)
        }, FrameLayout.LayoutParams(-1, dp(4), Gravity.TOP))
        utilityBarFrame.addView(View(this).apply {
            setBackgroundColor(Color.rgb(188, 82, 255))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(6f)
        }, FrameLayout.LayoutParams(-1, dp(1), Gravity.TOP))

        root.addView(utilityBarFrame, LinearLayout.LayoutParams(-1, barHeight).apply {
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
            aiFullscreenButton.setImageResource(
                if (aiFullscreen) R.drawable.ic_fullscreen_exit_modern else R.drawable.ic_fullscreen_modern
            )
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
                topMargin = dp(3)
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

    private fun fullscreenRootHeightPx(): Int {
        val fallback = resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val measured = runCatching {
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val metrics = wm.currentWindowMetrics
                val statusTop = metrics.windowInsets
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
                    .top
                metrics.bounds.height() - statusTop
            }.getOrNull()
            if (measured != null && measured > dp(420)) return measured
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusTop = if (statusId > 0) resources.getDimensionPixelSize(statusId) else 0
        return (fallback - statusTop).coerceAtLeast(dp(420))
    }

    private fun applyRootHeight() {
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
        lastRenderedEnterLabel = enterKeyLabel()
        refreshSuggestionsSoon()
    }

    private fun refreshEnterKeyIfNeeded() {
        if (!::keyboardPanel.isInitialized) return
        val label = enterKeyLabel()
        if (label == lastRenderedEnterLabel) return
        renderKeyboard()
    }

    private fun refreshSuggestionsSoon() {
        if (!::suggestionBar.isInitialized || !suggestionsEnabled) return
        handler.removeCallbacks(suggestionRefreshRunnable)
        handler.postDelayed(suggestionRefreshRunnable, if (fastTypingMode) 700L else 220L)
    }

    private fun refreshSuggestions() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionAutoHideRunnable)
        suggestionBar.removeAllViews()
        if (!suggestionsEnabled || searchWebComposeActive || isSensitiveEditor() || (mode != KeyboardMode.LETTERS && activeInternalInput() == null)) {
            suggestionBar.visibility = View.VISIBLE
            return
        }
        val before = textBeforeTypingCursor()
        val currentFragment = before.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        if (before.isNotBlank() && !before.last().isWhitespace() && currentFragment.length < 2) {
            suggestionBar.visibility = View.VISIBLE
            return
        }
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
        if (searchWebComposeActive) {
            moveFocusedWebInputCursor(keyCode)
            return
        }
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
        val isSpecial = spec.label.length > 2 || spec.label in listOf("⇧", "⇪", "⌫", "↵", "🔍", "➤", "→", "←", "✓", "◀", "▶")
        val normalColor = if (isSpecial) specialKeyBg else keyBg
        val referenceLargeKey = spec.label in listOf(
            "⇧", "⇪", "⌫", "?123", "↵", "✓", "➤", "→", "←", "🔍"
        )
        val referenceWideKey = referenceLargeKey || spec.label == "spasi"
        val frame = FrameLayout(this).apply {
            // At the old 100% setting the regular caps now fill 90% of their cells instead of
            // only 78%. The new 110% maximum can reach 99%, while clamping prevents overlap.
            scaleX = (keyBoxScale * if (referenceWideKey) 0.98f else 0.94f).coerceAtMost(1f)
            scaleY = (keyBoxScale * if (referenceLargeKey) 0.96f else 0.90f).coerceAtMost(1f)
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
        }, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP).apply {
            setMargins(dp(5), dp(2), dp(5), 0)
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
                setTextColor(Color.rgb(176, 174, 184))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(3), dp(6), 0)
                setShadowLayer(dpFloat(0.8f), 0f, dpFloat(1f), Color.BLACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(7f)
            }, FrameLayout.LayoutParams(-1, -1))
        }



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
                    if (instantKeyResponse) {
                        // Input first. Visual/haptic extras are intentionally deferred so a busy
                        // host app never has to wait for the keyboard's preview animation.
                        spec.action()
                        actionTriggered = true
                        // Keep tactile feedback in ultra-fast mode too, but run it immediately
                        // instead of adding one Runnable per key to the main-thread queue.
                        keyFeedback(view, longPress = false)
                    }
                    spec.longAction?.let { longAction ->
                        longRunnable = Runnable {
                            longTriggered = true
                            if (actionTriggered && spec.alternate != null) deleteInstantCommittedCharacter()
                            spec.alternate?.let(::updateKeyPreview)
                            longAction()
                            keyFeedback(view, longPress = true)
                        }.also { handler.postDelayed(it, longPressDurationMs) }
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
                    true
                }
                else -> false
            }
        }
        return frame
    }

    private fun keyFeedback(view: View, longPress: Boolean) {
        if (soundEnabled) {
            keyboardAudioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.35f)
        }
        if (vibrationEnabled) {
            val duration = if (longPress) (vibrationDurationMs + 12).coerceAtMost(100) else vibrationDurationMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyboardVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                keyboardVibrator.vibrate(duration)
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
        // v18: popup huruf/angka/simbol dinonaktifkan untuk respons tombol maksimal.
    }

    private fun updateKeyPreview(label: String) {
        // No popup preview in v18.
    }

    private fun dismissKeyPreview(release: Boolean = false) {
        keyPreviewDismissRunnable?.let(handler::removeCallbacks)
        keyPreviewDismissRunnable = null
        activeKeyPreview?.let { if (it.isShowing) it.dismiss() }
        if (release) {
            activeKeyPreviewLabel = null
            activeKeyPreview = null
        }
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
                Color.rgb(45, 44, 53),
                Color.rgb(39, 38, 47),
                Color.rgb(35, 34, 43)
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dpFloat(11f)
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

    private fun roundedStrokedBackground(
        color: Int,
        radiusDp: Float,
        strokeColor: Int,
        strokeWidthDp: Int
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpFloat(radiusDp)
        setStroke(dp(strokeWidthDp), strokeColor)
    }

    private fun premiumIconButton(
        iconRes: Int,
        description: String,
        action: () -> Unit
    ) = ImageButton(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(9), dp(7), dp(9), dp(7))
        background = roundedStrokedBackground(
            Color.rgb(49, 48, 58),
            11f,
            Color.rgb(82, 78, 99),
            1
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpFloat(2.2f)
        }
        setOnClickListener {
            keyFeedback(this, longPress = false)
            action()
        }
    }

    private fun toolbarIconButton(
        iconRes: Int,
        description: String,
        widthPx: Int,
        action: () -> Unit
    ) = premiumIconButton(iconRes, description, action).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(widthPx, dp(utilityHeightDp())).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
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
        setOnClickListener {
            keyFeedback(this, longPress = false)
            action()
        }
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
        setOnClickListener {
            keyFeedback(this, longPress = false)
            action()
        }
    }

    private fun aiPanelButton(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = when {
            label == "↑" -> 17f
            label.length > 7 -> 10f
            else -> 12f
        }
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(3), 0, dp(3), 0)
        setTextColor(Color.WHITE)
        typeface = aiBoldTypeface
        background = roundedStrokedBackground(
            if (primary) purple else Color.rgb(49, 48, 58),
            10f,
            if (primary) Color.rgb(137, 105, 243) else Color.rgb(70, 68, 82),
            1
        )
        setOnClickListener {
            keyFeedback(this, longPress = false)
            action()
        }
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

    private fun searchSurfaceHeightDp(): Int = scannerSurfaceHeightDp()

    private fun scannerSurfaceHeightDp(): Int {
        val density = resources.displayMetrics.density
        val screenHeightDp = (resources.displayMetrics.heightPixels / density).toInt()
        val screenWidthDp = (resources.displayMetrics.widthPixels / density).toInt()
        return if (isLandscape()) {
            (screenHeightDp * 0.42f).toInt().coerceIn(118, 205)
        } else {
            val proportionalHeight = (screenHeightDp * 0.50f).toInt().coerceIn(270, 480)
            val portraitViewportHeight = screenWidthDp + searchHeaderHeightDp() + 14
            maxOf(proportionalHeight, portraitViewportHeight).coerceAtMost(480)
        }
    }

    private fun aiHeaderHeightDp(): Int = if (isLandscape()) 30 else 38

    private fun aiHeaderControlHeightDp(): Int = if (isLandscape()) 26 else 34

    private fun aiAnswerHeightDp(): Int = if (isLandscape()) 47 else 70

    private fun aiInputHeightDp(): Int = if (isLandscape()) 28 else 36

    private fun aiComposeFooterHeightDp(): Int = if (isLandscape()) 27 else 31

    private fun aiComposeHeightDp(): Int = if (isLandscape()) 59 else 71

    private fun aiQuickActionHeightDp(): Int = if (isLandscape()) 28 else 34

    private fun currentAiPanelHeightDp(): Int = if (isLandscape()) 184 else 232

    private fun fullscreenKeyboardPanelHeightDp(): Int =
        (baseKeyboardHeightDp - utilityHeightDp() - toolbarKeyboardGapDp())
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
        if (fastTypingMode || isSensitiveEditor()) return
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
        braveBrowserPanel?.release()
        braveBrowserPanel = null
        searchWebView = null

        searchWebBigMode = false
        searchComposeActive = false
        searchWebComposeActive = false
        searchInput?.clearFocus()
        searchInput = null
        internalGalleryPanel?.release()
        internalGalleryPanel = null
        clearScannerGalleryImage()
        stopEmbeddedScanner()
        destroySearchWebView()
        if (::searchSurfaceContent.isInitialized) searchSurfaceContent.removeAllViews()
        searchSurfaceVisible = false
        if (::searchSurfacePanel.isInitialized) searchSurfacePanel.visibility = View.GONE
        applyRootHeight()
    }

    private fun showEmbeddedCameraPanel(resetCandidate: Boolean) {
        searchWebBigMode = false
        internalGalleryPanel?.release()
        internalGalleryPanel = null
        stopEmbeddedScanner()
        destroySearchWebView()
        searchComposeActive = false
        searchWebComposeActive = false
        searchInput = null
        if (resetCandidate) {
            clearScannerGalleryImage()
            scannerBestScore = 0
            scannerSelectedQuery = ""
            scannerSelectedUrl = ""
            scannerVisualSearchPreferred = true
            scannerMainProductText = ""
            scannerBingVisualQuery = ""
            scannerLocalShapeHint = ""
            scannerLastCandidateAt = 0L
            scannerPendingQuery = ""
            scannerPendingHits = 0
            scannerPendingAt = 0L
        }
        scannerActive = true
        searchSurfaceContent.removeAllViews()

        val content = FrameLayout(this).apply {
    background = roundedBackground(Color.TRANSPARENT, 20f)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = true
}
        val header = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(7), dp(2), dp(4), dp(2))
    elevation = dpFloat(8f)
}
        scannerStatusText = TextView(this).apply {
    visibility = View.GONE
}
header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(
            premiumIconButton(R.drawable.ic_gallery_modern, "Buka galeri di keyboard") { showInternalGalleryPanel() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp()))
        )
        header.addView(
            premiumIconButton(R.drawable.ic_flash_modern, "Flash kamera") { toggleScannerTorch() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )
        header.addView(
            premiumIconButton(R.drawable.ic_close_modern, "Tutup kamera penelusuran") { closeSearchSurface() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )
        content.addView(header, FrameLayout.LayoutParams(-1, dp(searchHeaderHeightDp() + 4), Gravity.TOP).apply {
    leftMargin = dp(3)
    rightMargin = dp(3)
    topMargin = dp(2)
})

        val previewFrame = FrameLayout(this)
        scannerPreviewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
            installScannerCameraGestures(this)
        }
        previewFrame.addView(scannerPreviewView, FrameLayout.LayoutParams(-1, -1))
        scannerGalleryImageView = ImageView(this).apply {
            // FIT_CENTER is the neutral 1x state: the complete gallery photo is visible.
            // Users can zoom out below 1x for extra breathing room, zoom in, then drag to pan.
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            installScannerGalleryGestures(this)
        }
        previewFrame.addView(scannerGalleryImageView, FrameLayout.LayoutParams(-1, -1))

        content.addView(previewFrame, FrameLayout.LayoutParams(-1, -1))

        val resultCard = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(6), dp(3), dp(4), dp(3))
    setBackgroundColor(Color.TRANSPARENT)
    elevation = dpFloat(8f)
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
        scannerSearchButton = compactButton("Cari") { performScannerSearch() }.apply {
            isEnabled = true
        }
        resultCard.addView(scannerSearchButton, LinearLayout.LayoutParams(dp(55), if (isLandscape()) dp(30) else dp(38)).apply {
            leftMargin = dp(3)
        })
        content.addView(resultCard, FrameLayout.LayoutParams(-1, if (isLandscape()) dp(40) else dp(55), Gravity.BOTTOM))
header.bringToFront()
resultCard.bringToFront()
        searchSurfaceContent.addView(content, FrameLayout.LayoutParams(-1, -1))
        showSearchSurface()
        scannerGalleryUri?.let(::showGalleryImage)
    }

    private fun startEmbeddedScanner() {
        if (!scannerActive || !searchSurfaceVisible || scannerGalleryUri != null) return
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
                applyScannerCameraZoom(scannerCameraZoomRatio, showStatus = false)
                scannerStatusText?.text = if (scannerCameraZoomRatio > 1.05f) {
                    "Zoom %.1fx · cubit layar untuk atur zoom".format(scannerCameraZoomRatio)
                } else {
                    "Aktif · cubit untuk zoom, ketuk objek untuk fokus"
                }
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


    private fun installScannerCameraGestures(view: PreviewView) {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragged = false
        var lastTapAt = 0L
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = scannerCameraZoomRatio * detector.scaleFactor
                applyScannerCameraZoom(next, showStatus = true)
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress && scannerCameraZoomRatio > 1.03f) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > dpFloat(1f) || abs(dy) > dpFloat(1f)) {
                            // Dragging the picture left reveals the right side, just like a normal
                            // zoomable photo. Pan is normalized so Camera2 can move the real crop,
                            // not merely translate the preview overlay.
                            scannerCameraPanX = (scannerCameraPanX - (dx / view.width.coerceAtLeast(1)) * 2f)
                                .coerceIn(-1f, 1f)
                            scannerCameraPanY = (scannerCameraPanY - (dy / view.height.coerceAtLeast(1)) * 2f)
                                .coerceIn(-1f, 1f)
                            applyScannerCameraZoom(scannerCameraZoomRatio, showStatus = false)
                            scannerStatusText?.text = "Zoom %.1fx · geser untuk pindah area".format(scannerCameraZoomRatio)
                            dragged = true
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!scaleDetector.isInProgress && !dragged && moved <= dpFloat(12f)) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt <= 290L) {
                            val target = if (scannerCameraZoomRatio > 1.35f) 1f else 2.5f
                            if (target <= 1.01f) {
                                scannerCameraPanX = 0f
                                scannerCameraPanY = 0f
                            }
                            applyScannerCameraZoom(target, showStatus = true)
                            lastTapAt = 0L
                        } else {
                            focusScannerAt(event.x, event.y)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            lastTapAt = now
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragged = false
            }
            true
        }
    }

    private fun applyScannerCameraZoom(requested: Float, showStatus: Boolean) {
        val camera = scannerCamera ?: return
        val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
        val activeArray = camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
        ) ?: return
        val maxDigitalZoom = (camera2Info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
        ) ?: 1f).coerceIn(1f, 12f)

        val target = requested.coerceIn(1f, maxDigitalZoom)
        scannerCameraZoomRatio = target
        if (target <= 1.01f) {
            scannerCameraPanX = 0f
            scannerCameraPanY = 0f
        }

        val cropWidth = (activeArray.width() / target).toInt().coerceIn(2, activeArray.width())
        val cropHeight = (activeArray.height() / target).toInt().coerceIn(2, activeArray.height())
        val maxShiftX = ((activeArray.width() - cropWidth) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((activeArray.height() - cropHeight) / 2f).coerceAtLeast(0f)

        // scannerCameraPanX/Y are expressed in display coordinates. Camera2 crop coordinates are
        // sensor coordinates, so rotate the pan vector back into sensor space.
        val displayRotation = scannerPreviewView?.display?.rotation ?: android.view.Surface.ROTATION_0
        val sensorRotation = camera.cameraInfo.getSensorRotationDegrees(displayRotation)
        val displayPanX = scannerCameraPanX.coerceIn(-1f, 1f)
        val displayPanY = scannerCameraPanY.coerceIn(-1f, 1f)
        val sensorPan = when (sensorRotation) {
            90 -> displayPanY to -displayPanX
            180 -> -displayPanX to -displayPanY
            270 -> -displayPanY to displayPanX
            else -> displayPanX to displayPanY
        }

        val centerX = (activeArray.centerX() + sensorPan.first * maxShiftX)
            .toInt().coerceIn(activeArray.left + cropWidth / 2, activeArray.right - cropWidth / 2)
        val centerY = (activeArray.centerY() + sensorPan.second * maxShiftY)
            .toInt().coerceIn(activeArray.top + cropHeight / 2, activeArray.bottom - cropHeight / 2)
        val left = (centerX - cropWidth / 2).coerceIn(activeArray.left, activeArray.right - cropWidth)
        val top = (centerY - cropHeight / 2).coerceIn(activeArray.top, activeArray.bottom - cropHeight)
        val crop = Rect(left, top, left + cropWidth, top + cropHeight)

        val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, crop)
            .build()
        androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
            .setCaptureRequestOptions(options)

        if (showStatus) {
            scannerStatusText?.text = if (target > 1.03f) {
                "Zoom %.1fx · geser untuk pindah area".format(target)
            } else {
                "Zoom 1.0x · cubit untuk dekat/jauh"
            }
        }
    }

    private fun installScannerGalleryGestures(view: ImageView) {
        var lastTapAt = 0L
        var lastX = 0f
        var lastY = 0f
        var dragged = false
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = scannerGalleryZoom.coerceAtLeast(0.01f)
                val newZoom = (oldZoom * detector.scaleFactor).coerceIn(0.55f, 6f)
                if (view.width > 0 && view.height > 0 && oldZoom != newZoom) {
                    val centerX = view.width / 2f
                    val centerY = view.height / 2f
                    val contentX = (detector.focusX - centerX - scannerGalleryPanX) / oldZoom
                    val contentY = (detector.focusY - centerY - scannerGalleryPanY) / oldZoom
                    scannerGalleryPanX = detector.focusX - centerX - contentX * newZoom
                    scannerGalleryPanY = detector.focusY - centerY - contentY * newZoom
                }
                scannerGalleryZoom = newZoom
                applyScannerGalleryZoom(view)
                scannerStatusText?.text = "Foto galeri · zoom %.2fx · geser untuk pindah area".format(scannerGalleryZoom)
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > dpFloat(1f) || abs(dy) > dpFloat(1f)) {
                            scannerGalleryPanX += dx
                            scannerGalleryPanY += dy
                            applyScannerGalleryZoom(view)
                            dragged = true
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!scaleDetector.isInProgress && !dragged) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt <= 290L) {
                            val oldZoom = scannerGalleryZoom.coerceAtLeast(0.01f)
                            val newZoom = if (oldZoom > 1.35f) 1f else 2.5f
                            val centerX = view.width / 2f
                            val centerY = view.height / 2f
                            val contentX = (event.x - centerX - scannerGalleryPanX) / oldZoom
                            val contentY = (event.y - centerY - scannerGalleryPanY) / oldZoom
                            scannerGalleryPanX = event.x - centerX - contentX * newZoom
                            scannerGalleryPanY = event.y - centerY - contentY * newZoom
                            scannerGalleryZoom = newZoom
                            applyScannerGalleryZoom(view)
                            scannerStatusText?.text = "Foto galeri · zoom %.2fx · geser untuk pindah area".format(scannerGalleryZoom)
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragged = false
            }
            true
        }
    }

    private fun applyScannerGalleryZoom(view: ImageView) {
        val bitmap = scannerGalleryPreviewBitmap
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        if (bitmap == null || bitmap.isRecycled || view.width <= 1 || view.height <= 1) {
            view.scaleX = scannerGalleryZoom
            view.scaleY = scannerGalleryZoom
            view.translationX = scannerGalleryPanX
            view.translationY = scannerGalleryPanY
            return
        }

        val fitScale = minOf(
            view.width.toFloat() / bitmap.width.coerceAtLeast(1),
            view.height.toFloat() / bitmap.height.coerceAtLeast(1)
        )
        val displayedWidth = bitmap.width * fitScale * scannerGalleryZoom
        val displayedHeight = bitmap.height * fitScale * scannerGalleryZoom
        val maxPanX = ((displayedWidth - view.width) / 2f).coerceAtLeast(0f)
        val maxPanY = ((displayedHeight - view.height) / 2f).coerceAtLeast(0f)
        scannerGalleryPanX = scannerGalleryPanX.coerceIn(-maxPanX, maxPanX)
        scannerGalleryPanY = scannerGalleryPanY.coerceIn(-maxPanY, maxPanY)

        view.scaleX = scannerGalleryZoom
        view.scaleY = scannerGalleryZoom
        view.translationX = scannerGalleryPanX
        view.translationY = scannerGalleryPanY
    }

    private fun showInternalGalleryPanel() {
        searchWebBigMode = false
        if (!::searchSurfaceContent.isInitialized) return

        stopEmbeddedScanner(keepRequested = true)
        destroySearchWebView()
        searchComposeActive = false
        searchWebComposeActive = false
        searchInput = null
        scannerActive = true

        internalGalleryPanel?.release()
        val panel = InternalGalleryPanel(this)
        internalGalleryPanel = panel

        panel.show(
            container = searchSurfaceContent,
            onSelected = selected@ { uri ->
                if (internalGalleryPanel !== panel) return@selected
                panel.release()
                internalGalleryPanel = null
                scannerGalleryZoom = 1f
                scannerGalleryFocusX = 0.5f
                scannerGalleryFocusY = 0.5f
                scannerGalleryPanX = 0f
                scannerGalleryPanY = 0f
                scannerGalleryUri = uri
                scannerVisualSearchPreferred = true
                scannerSelectedQuery = ""
                scannerSelectedUrl = ""
                showEmbeddedCameraPanel(resetCandidate = false)
            },
            onCamera = camera@ {
                if (internalGalleryPanel !== panel) return@camera
                panel.release()
                internalGalleryPanel = null
                clearScannerGalleryImage()
                scannerActive = true
                showEmbeddedCameraPanel(resetCandidate = false)
                scannerPreviewView?.post { startEmbeddedScanner() }
            },
            onPermissionRequired = permission@ {
                if (internalGalleryPanel !== panel) return@permission
                runCatching {
                    startActivity(
                        Intent(this, GalleryPermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                }.onFailure {
                    scannerStatusText?.text = "Izin foto tidak dapat dibuka."
                    Toast.makeText(this, "Buka aplikasi AI Ads Keyboard lalu izinkan akses Foto & Video.", Toast.LENGTH_LONG).show()
                }
            }
        )
        showSearchSurface()
    }

    private fun showGalleryImage(uri: Uri) {
        val isNewImage = scannerGalleryUri != uri
        scannerGalleryUri = uri
        if (isNewImage) {
            scannerGalleryZoom = 1f
            scannerGalleryFocusX = 0.5f
            scannerGalleryFocusY = 0.5f
            scannerGalleryPanX = 0f
            scannerGalleryPanY = 0f
        }
        scannerVisualSearchPreferred = true
        scannerSelectedQuery = ""
        scannerSelectedUrl = ""
        scannerMainProductText = ""
        scannerBingVisualQuery = ""
        scannerLocalShapeHint = ""
        stopEmbeddedScanner(keepRequested = true)
        scannerPreviewView?.visibility = View.GONE
        scannerGalleryImageView?.apply {
            setImageDrawable(null)
            visibility = View.VISIBLE
        }
        scannerStatusText?.text = "Gambar galeri dipilih · tekan Cari untuk AI Vision"
        scannerResultText?.text = "AI Vision akan mengenali gambar dari galeri"
        scannerSearchButton?.isEnabled = true

        thread {
            val bitmap = decodeGalleryBitmap(uri, 1280)
            handler.post {
                if (scannerGalleryUri != uri) {
                    bitmap?.recycle()
                    return@post
                }
                scannerGalleryPreviewBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
                scannerGalleryPreviewBitmap = bitmap
                if (bitmap == null) {
                    scannerStatusText?.text = "Gambar galeri tidak dapat dibaca · pilih gambar lain"
                    scannerResultText?.text = "Format gambar tidak didukung atau file tidak tersedia"
                    scannerSearchButton?.isEnabled = false
                } else {
                    scannerGalleryImageView?.setImageBitmap(bitmap)
                    scannerGalleryImageView?.let(::applyScannerGalleryZoom)
                }
            }
        }
    }

    private fun clearScannerGalleryImage() {
        scannerGalleryUri = null
        scannerGalleryImageView?.apply {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            pivotX = width / 2f
            pivotY = height / 2f
            setImageDrawable(null)
        }
        scannerGalleryImageView = null
        scannerGalleryPreviewBitmap?.takeIf { !it.isRecycled }?.recycle()
        scannerGalleryPreviewBitmap = null
        scannerGalleryZoom = 1f
        scannerGalleryFocusX = 0.5f
        scannerGalleryFocusY = 0.5f
    }

    private fun decodeGalleryBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modern = runCatching {
                val imageSource = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                    var sample = 1
                    while (longest / sample > maxDimension * 2) sample *= 2
                    decoder.setTargetSampleSize(sample.coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            if (modern != null) {
                val longest = maxOf(modern.width, modern.height)
                if (longest <= maxDimension) return modern
                val scale = maxDimension.toFloat() / longest.toFloat()
                val resized = Bitmap.createScaledBitmap(
                    modern,
                    (modern.width * scale).toInt().coerceAtLeast(1),
                    (modern.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (resized !== modern && !modern.isRecycled) modern.recycle()
                return resized
            }
        }

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDimension * 2) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null
            val decodedLongest = maxOf(decoded.width, decoded.height)
            if (decodedLongest <= maxDimension) return@runCatching decoded
            val scale = maxDimension.toFloat() / decodedLongest.toFloat()
            val width = (decoded.width * scale).toInt().coerceAtLeast(1)
            val height = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded && !decoded.isRecycled) decoded.recycle()
            }
        }.getOrNull()
    }

    private fun cropGalleryForCurrentZoom(source: Bitmap): Bitmap {
        val view = scannerGalleryImageView
        val viewWidth = view?.width ?: 0
        val viewHeight = view?.height ?: 0
        val zoom = scannerGalleryZoom.coerceIn(0.55f, 6f)

        // FIT_CENTER at 1x shows the complete photo. Zooming below 1x only adds margins, so AI
        // should still receive the complete source image. Above 1x, map the exact panned viewport
        // back into bitmap coordinates so "Cari" analyzes what the user is actually looking at.
        if (viewWidth <= 1 || viewHeight <= 1 || zoom <= 1.001f) return source

        val fitScale = minOf(
            viewWidth.toFloat() / source.width.coerceAtLeast(1),
            viewHeight.toFloat() / source.height.coerceAtLeast(1)
        )
        val displayScale = fitScale * zoom
        if (displayScale <= 0f) return source

        val displayedWidth = source.width * displayScale
        val displayedHeight = source.height * displayScale
        val imageLeft = (viewWidth - displayedWidth) / 2f + scannerGalleryPanX
        val imageTop = (viewHeight - displayedHeight) / 2f + scannerGalleryPanY

        val visibleLeftPx = (-imageLeft).coerceIn(0f, displayedWidth)
        val visibleTopPx = (-imageTop).coerceIn(0f, displayedHeight)
        val visibleRightPx = (viewWidth - imageLeft).coerceIn(0f, displayedWidth)
        val visibleBottomPx = (viewHeight - imageTop).coerceIn(0f, displayedHeight)

        val left = (visibleLeftPx / displayScale).toInt().coerceIn(0, source.width - 1)
        val top = (visibleTopPx / displayScale).toInt().coerceIn(0, source.height - 1)
        val right = (visibleRightPx / displayScale).toInt().coerceIn(left + 1, source.width)
        val bottom = (visibleBottomPx / displayScale).toInt().coerceIn(top + 1, source.height)

        if (left == 0 && top == 0 && right == source.width && bottom == source.height) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
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
                val rotated = input.rotationDegrees % 180 != 0
                val frameWidth = if (rotated) input.height else input.width
                val frameHeight = if (rotated) input.width else input.height
                val target = scannerTargetRect(frameWidth, frameHeight)
                val barcode = barcodes
                    .filter { !it.rawValue.isNullOrBlank() }
                    .firstOrNull { scannerLineInsideTarget(it.boundingBox, target) }
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
        var remaining = 3
        var recognizedLines = emptyList<ScannerTextLine>()
        var imageLabels = emptyList<Pair<String, Float>>()
        var objects = emptyList<ScannerObjectSignal>()

        fun completeOne() {
            remaining -= 1
            if (remaining != 0) return
            val rotated = input.rotationDegrees % 180 != 0
            val frameWidth = if (rotated) input.height else input.width
            val frameHeight = if (rotated) input.width else input.height
            publishScannerVisualResult(recognizedLines, imageLabels, objects, frameWidth, frameHeight)
            finishScannerFrame(imageProxy)
        }

        scannerTextRecognizer.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                recognizedLines = task.result?.textBlocks.orEmpty()
                    .flatMap { it.lines }
                    .map { ScannerTextLine(it.text, it.boundingBox) }
            }
            completeOne()
        }

        scannerImageLabeler.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                imageLabels = task.result.orEmpty()
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim() to it.confidence }
                    .filter { it.first.isNotBlank() }
                    .distinctBy { it.first.lowercase() }
                    .take(8)
            }
            completeOne()
        }

        scannerObjectDetector.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val rotated = input.rotationDegrees % 180 != 0
                val frameWidth = if (rotated) input.height else input.width
                val frameHeight = if (rotated) input.width else input.height
                val target = scannerTargetRect(frameWidth, frameHeight)
                objects = task.result.orEmpty()
                    .filter { scannerLineInsideTarget(it.boundingBox, target) }
                    .map { detected ->
                        ScannerObjectSignal(
                            bounds = Rect(detected.boundingBox),
                            labels = detected.labels
                                .sortedByDescending { it.confidence }
                                .map { it.text.trim() to it.confidence }
                                .filter { it.first.isNotBlank() }
                                .take(4)
                        )
                    }
                    .sortedByDescending { scannerObjectRelevance(it.bounds, target) }
                    .take(3)
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
        scannerVisualSearchPreferred = false
        setScannerCandidate(query, directUrl, 1_000, label)
    }

    private fun publishScannerVisualResult(
        detectedLines: List<ScannerTextLine>,
        labels: List<Pair<String, Float>>,
        objects: List<ScannerObjectSignal>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val target = scannerTargetRect(frameWidth, frameHeight)
        val primaryObject = objects.maxByOrNull { scannerObjectRelevance(it.bounds, target) }
        val objectBounds = primaryObject?.bounds

        val lines = detectedLines.asSequence()
            .map { ScannerTextLine(it.text.trim().replace(Regex("\\s+"), " "), it.bounds) }
            .filter { it.text.length in 2..72 && it.text.any(Char::isLetterOrDigit) }
            .filter { scannerLineInsideTarget(it.bounds, target) }
            .filter { line ->
                objectBounds == null || line.bounds == null ||
                    scannerRectOverlapRatio(line.bounds, objectBounds) >= 0.28f ||
                    scannerIsStrongMainProductText(line, target, frameHeight)
            }
            .distinctBy { it.text.lowercase() }
            .toList()

        val directUrl = lines.asSequence()
            .mapNotNull { OCR_URL_REGEX.find(it.text)?.value }
            .map { if (it.startsWith("www.", true)) "https://$it" else it }
            .firstOrNull().orEmpty()

        val ranked = lines
            .map { line ->
                val objectBoost = if (objectBounds != null && line.bounds != null &&
                    scannerRectOverlapRatio(line.bounds, objectBounds) >= 0.35f) 18 else 0
                line to (scannerProminentLineScore(line, target, frameHeight) + objectBoost)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)

        scannerMainProductText = ranked.asSequence()
            .map { it.first }
            .filter { scannerIsStrongMainProductText(it, target, frameHeight) }
            .map { it.text.trim().replace(Regex("\\s+"), " ") }
            .filterNot { scannerLooksLikeSpecNoise(it) }
            .distinctBy { it.lowercase() }
            .take(2)
            .joinToString(" ")
            .take(96)

        val objectLabels = objects.asSequence()
            .flatMap { it.labels.asSequence() }
            .filter { it.second >= 0.38f }
            .map { scannerLabelForSearch(it.first) to it.second }
            .filter { it.first.isNotBlank() }
            .filterNot { it.first.lowercase() in SCANNER_IGNORED_LABELS }
            .toList()

        val broadLabels = labels.asSequence()
            .filter { it.second >= 0.42f }
            .map { scannerLabelForSearch(it.first) to it.second }
            .filter { it.first.isNotBlank() }
            .filterNot { it.first.lowercase() in SCANNER_IGNORED_LABELS }
            .toList()

        val usefulLabels = (objectLabels + broadLabels)
            .sortedByDescending { it.second }
            .distinctBy { it.first.lowercase() }
            .take(5)

        scannerLocalShapeHint = objectBounds?.let { scannerShapeHint(it) }.orEmpty()
        scannerBingVisualQuery = usefulLabels.joinToString(" ") { it.first }.take(140)
        scannerVisualSearchPreferred = directUrl.isBlank()

        if (directUrl.isNotBlank()) {
            setScannerCandidate(directUrl, directUrl, 1_000, "Tautan di dalam objek terdeteksi")
            return
        }

        val previewParts = listOf(scannerMainProductText, scannerBingVisualQuery, scannerLocalShapeHint)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val previewQuery = previewParts.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(200)

        if (previewQuery.isBlank()) {
            scannerSelectedQuery = "objek fisik"
            scannerSelectedUrl = ""
            handler.post {
                scannerStatusText?.text = "Objek siap · tekan Cari untuk AI Vision"
                scannerResultText?.text = "AI Vision akan mengenali foto objek"
                scannerSearchButton?.isEnabled = true
            }
            return
        }

        val score = 45 + usefulLabels.sumOf { (it.second * 20f).toInt() } +
            ranked.sumOf { it.second.coerceAtMost(80) } + (if (objectBounds != null) 35 else 0)
        scannerSelectedQuery = scannerMainProductText.ifBlank { "Objek fisik siap" }
        scannerSelectedUrl = ""
        scannerBestScore = score
        scannerLastCandidateAt = System.currentTimeMillis()
        handler.post {
            scannerStatusText?.text = "Objek siap · tekan Cari untuk AI Vision"
            scannerResultText?.text = scannerMainProductText.ifBlank { "AI Vision akan mengenali foto objek" }
            scannerSearchButton?.isEnabled = true
        }
    }

    private fun scannerTargetRect(width: Int, height: Int): Rect = Rect(
        (width.coerceAtLeast(1) * 0.07f).toInt(),
        (height.coerceAtLeast(1) * 0.24f).toInt(),
        (width.coerceAtLeast(1) * 0.93f).toInt(),
        (height.coerceAtLeast(1) * 0.76f).toInt()
    )

    private fun scannerLineInsideTarget(bounds: Rect?, target: Rect): Boolean {
        bounds ?: return false
        if (!Rect.intersects(bounds, target)) return false
        if (target.contains(bounds.centerX(), bounds.centerY())) return true
        val left = maxOf(bounds.left, target.left)
        val top = maxOf(bounds.top, target.top)
        val right = minOf(bounds.right, target.right)
        val bottom = minOf(bounds.bottom, target.bottom)
        val overlap = (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
        val area = bounds.width().coerceAtLeast(1).toLong() * bounds.height().coerceAtLeast(1).toLong()
        return overlap.toDouble() / area.toDouble() >= 0.60
    }

    private fun scannerProminentLineScore(line: ScannerTextLine, target: Rect, frameHeight: Int): Int {
        var score = scannerTextLineScore(line.text)
        line.bounds?.let { box ->
            score += ((box.height().toFloat() / frameHeight.coerceAtLeast(1)) * 360f).toInt().coerceIn(0, 42)
            val dx = kotlin.math.abs(box.centerX() - target.centerX()).toFloat() / target.width().coerceAtLeast(1)
            val dy = kotlin.math.abs(box.centerY() - target.centerY()).toFloat() / target.height().coerceAtLeast(1)
            score += ((1f - (dx + dy).coerceIn(0f, 1f)) * 18f).toInt()
        }
        if (OCR_PRICE_REGEX.containsMatchIn(line.text) && !line.text.any { ch ->
                ch.isLetter() && ch.uppercaseChar() !in "RPIDRUSD"
            }) score -= 18
        val compact = line.text.filterNot(Char::isWhitespace)
        if (compact.length <= 7 && compact.any(Char::isLetter) && compact.any(Char::isDigit) &&
            !OCR_PRICE_REGEX.containsMatchIn(line.text)) score -= 10
        return score
    }

    private fun scannerObjectRelevance(bounds: Rect, target: Rect): Float {
        val overlap = scannerRectOverlapRatio(bounds, target)
        val dx = kotlin.math.abs(bounds.centerX() - target.centerX()).toFloat() / target.width().coerceAtLeast(1)
        val dy = kotlin.math.abs(bounds.centerY() - target.centerY()).toFloat() / target.height().coerceAtLeast(1)
        val center = 1f - (dx + dy).coerceIn(0f, 1f)
        val size = (bounds.width().toFloat() * bounds.height().toFloat()) /
            (target.width().coerceAtLeast(1).toFloat() * target.height().coerceAtLeast(1).toFloat())
        return overlap * 2.2f + center + size.coerceIn(0f, 1.2f)
    }

    private fun scannerRectOverlapRatio(first: Rect, second: Rect): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val overlap = (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
        val area = first.width().coerceAtLeast(1).toLong() * first.height().coerceAtLeast(1).toLong()
        return (overlap.toDouble() / area.toDouble()).toFloat()
    }

    private fun scannerShapeHint(bounds: Rect): String {
        val ratio = bounds.width().toFloat() / bounds.height().coerceAtLeast(1).toFloat()
        return when {
            ratio >= 1.65f -> "bentuk horizontal memanjang"
            ratio <= 0.62f -> "bentuk vertikal memanjang"
            ratio in 0.86f..1.16f -> "bentuk kompak hampir persegi"
            ratio > 1.16f -> "bentuk melebar"
            else -> "bentuk meninggi"
        }
    }

    private fun scannerLooksLikeSpecNoise(value: String): Boolean {
        val compact = value.trim().replace(Regex("\\s+"), " ")
        if (compact.length <= 2) return true
        if (SCANNER_SMALL_SPEC_REGEX.matches(compact)) return true
        val letters = compact.count(Char::isLetter)
        val digits = compact.count(Char::isDigit)
        return compact.length <= 7 && digits > 0 && letters <= 3
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
        if (score < 900 && url.isBlank()) {
            val stable = scannerQueriesSimilar(cleanQuery, scannerPendingQuery) && now - scannerPendingAt <= SCANNER_STABILITY_WINDOW_MS
            if (stable) scannerPendingHits += 1 else {
                scannerPendingQuery = cleanQuery
                scannerPendingHits = 1
            }
            scannerPendingAt = now
            if (scannerPendingHits < SCANNER_REQUIRED_STABLE_HITS) {
                handler.post { scannerStatusText?.text = "Mengenali objek utama di area bidik…" }
                return
            }
        }
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

    private fun scannerQueriesSimilar(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        val a = scannerQueryTokens(first)
        val b = scannerQueryTokens(second)
        if (a.isEmpty() || b.isEmpty()) return first.equals(second, ignoreCase = true)
        return a.intersect(b).size.toFloat() / minOf(a.size, b.size).coerceAtLeast(1) >= 0.50f
    }

    private fun scannerQueryTokens(value: String): Set<String> = value.lowercase()
        .split(Regex("[^a-z0-9À-ÿ]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun finishScannerFrame(imageProxy: ImageProxy) {
        imageProxy.close()
        scannerProcessingFrame.set(false)
    }

    private fun openSearchResults(query: String, directUrl: String = "") {
        val cleanQuery = query.trim().ifBlank { directUrl.trim() }
        if (cleanQuery.isBlank()) return
        searchQuery = cleanQuery
        searchUrl = directUrl.takeIf(::isWebUrl) ?: if (isWebUrl(cleanQuery)) cleanQuery else selectedWebSearchUrl(cleanQuery)
        stopEmbeddedScanner()
        showSearchWebPanel()
    }

    private fun showSearchWebPanel() {
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
                // Native Brave controls are not webpage inputs. Web focus is probed separately.
                searchWebComposeActive = false
                aiComposeActive = false
            }
        )
        braveBrowserPanel = browser
        searchWebView = browser.webView
        searchInput = browser.addressField
        browser.webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                handler.postDelayed({
                    if (searchWebView === browser.webView) probeFocusedWebInput()
                }, 24L)
            }
            false
        }
        browser.webView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                handler.postDelayed({
                    if (searchWebView === browser.webView) probeFocusedWebInput()
                }, 24L)
            } else {
                searchWebComposeActive = false
            }
        }
        searchSurfaceContent.addView(browser, FrameLayout.LayoutParams(-1, -1))
        showSearchSurface()
    }

    /**
     * Keep embedded pages usable while the phone itself is portrait. The old web area could be
     * wider than it was tall after the keyboard consumed the lower half of the screen, causing
     * responsive sites to cover their content with a misleading rotate-device message.
     */
    private fun installBingCameraQualityShim(webView: WebView, rawUrl: String?) {
        val host = runCatching { Uri.parse(rawUrl.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
        if (host != "bing.com" && !host.endsWith(".bing.com")) return
        val script = """
            (function() {
              if (window.__aiAdsBingCameraQualityPatched) return true;
              var media = navigator.mediaDevices;
              if (!media || typeof media.getUserMedia !== 'function') return false;
              var original = media.getUserMedia.bind(media);
              media.getUserMedia = function(constraints) {
                var next = Object.assign({}, constraints || {});
                if (next.video !== false) {
                  var video = (next.video && typeof next.video === 'object') ? Object.assign({}, next.video) : {};
                  video.width = { min: 640, ideal: 1920 };
                  video.height = { min: 480, ideal: 1080 };
                  video.frameRate = { min: 15, ideal: 30 };
                  if (!video.facingMode) video.facingMode = { ideal: 'environment' };
                  next.video = video;
                }
                return original(next).then(function(stream) {
                  try {
                    var track = stream.getVideoTracks && stream.getVideoTracks()[0];
                    if (track && typeof track.applyConstraints === 'function') {
                      track.applyConstraints({
                        width: { ideal: 1920 },
                        height: { ideal: 1080 },
                        frameRate: { ideal: 30 },
                        advanced: [{ focusMode: 'continuous' }]
                      }).catch(function(){});
                    }
                  } catch (_) {}
                  return stream;
                });
              };
              window.__aiAdsBingCameraQualityPatched = true;
              return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun applyPortraitWebCompatibility(webView: WebView) {
        if (isLandscape() || searchWebView !== webView) return
        val script = """
            (function() {
              if (window.innerHeight < window.innerWidth) return false;
              try {
                Object.defineProperty(window, 'orientation', {
                  configurable: true,
                  get: function() { return 0; }
                });
              } catch (_) {}
              try {
                var orientation = window.screen && window.screen.orientation;
                if (orientation) {
                  Object.defineProperty(orientation, 'type', {
                    configurable: true,
                    get: function() { return 'portrait-primary'; }
                  });
                  Object.defineProperty(orientation, 'angle', {
                    configurable: true,
                    get: function() { return 0; }
                  });
                }
              } catch (_) {}

              var blockerText = /(putar\\s+(?:perangkat|ponsel)|jaga\\s+orientasi|orientasi[^.!?]{0,40}portrait|rotate\\s+(?:your\\s+)?device|turn\\s+(?:your\\s+)?device|portrait\\s+mode)/i;
              function isLarge(rect) {
                return rect.width >= window.innerWidth * 0.55 &&
                       rect.height >= window.innerHeight * 0.35;
              }
              function removeOrientationBlockers() {
                var nodes = document.querySelectorAll('body *');
                for (var i = 0; i < nodes.length; i++) {
                  var node = nodes[i];
                  if (node.dataset && node.dataset.aiAdsOrientationChecked === '1') continue;
                  var text = (node.innerText || node.textContent || '').replace(/\\s+/g, ' ').trim();
                  if (!text || text.length > 180 || !blockerText.test(text)) continue;
                  var target = node;
                  for (var depth = 0; depth < 4 && target.parentElement; depth++) {
                    var style = window.getComputedStyle(target);
                    var rect = target.getBoundingClientRect();
                    if ((style.position === 'fixed' || style.position === 'absolute') && isLarge(rect)) break;
                    target = target.parentElement;
                    if (target === document.body || target === document.documentElement) {
                      target = node;
                      break;
                    }
                  }
                  var targetRect = target.getBoundingClientRect();
                  var targetStyle = window.getComputedStyle(target);
                  if (isLarge(targetRect) &&
                      (targetStyle.position === 'fixed' || targetStyle.position === 'absolute' || target === node)) {
                    target.style.setProperty('display', 'none', 'important');
                    target.setAttribute('aria-hidden', 'true');
                    if (document.body) document.body.style.setProperty('overflow', 'auto', 'important');
                  } else if (node.dataset) {
                    node.dataset.aiAdsOrientationChecked = '1';
                  }
                }
              }
              removeOrientationBlockers();
              if (!window.__aiAdsPortraitObserver && document.body) {
                window.__aiAdsPortraitObserver = new MutationObserver(removeOrientationBlockers);
                window.__aiAdsPortraitObserver.observe(document.body, { childList: true, subtree: true });
              }
              try { window.dispatchEvent(new Event('orientationchange')); } catch (_) {}
              try { window.dispatchEvent(new Event('resize')); } catch (_) {}
              return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun probeFocusedWebInput() {
        val webView = searchWebView ?: run {
            searchWebComposeActive = false
            return
        }
        webView.evaluateJavascript(
            "(function(){var e=document.activeElement;if(!e)return false;var t=(e.tagName||'').toUpperCase();return t==='INPUT'||t==='TEXTAREA'||e.isContentEditable===true;})()"
        ) { raw ->
            if (searchWebView !== webView) return@evaluateJavascript
            val active = raw == "true"
            val changed = active != searchWebComposeActive
            searchWebComposeActive = active
            if (active) {
                searchComposeActive = false
                searchInput?.clearFocus()
            }
            if (changed && ::keyboardPanel.isInitialized && mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun runFocusedWebEdit(action: String, payload: String = "") {
        val webView = searchWebView ?: return
        val actionJson = JSONObject.quote(action)
        val payloadJson = JSONObject.quote(payload)
        val script = """
            (function(){
              var e=document.activeElement;if(!e)return false;
              var tag=(e.tagName||'').toUpperCase();
              var editable=tag==='INPUT'||tag==='TEXTAREA';
              var content=e.isContentEditable===true;
              if(!editable&&!content)return false;
              var action=$actionJson,payload=$payloadJson;
              function setValue(next,cursor,inputType,data){
                var proto=tag==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
                var d=Object.getOwnPropertyDescriptor(proto,'value');
                if(d&&d.set)d.set.call(e,next);else e.value=next;
                try{e.setSelectionRange(cursor,cursor);}catch(_){}
                try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:inputType,data:data}));}
                catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}
              }
              if(action==='insert'){
                if(content){e.focus();document.execCommand('insertText',false,payload);e.dispatchEvent(new Event('input',{bubbles:true}));return true;}
                var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a;
                setValue(v.slice(0,a)+payload+v.slice(b),a+payload.length,'insertText',payload);return true;
              }
              if(action==='delete'&&editable){
                var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a,from=a;
                if(a===b){if(a<=0)return true;if(payload==='word'){while(from>0&&/\\s/.test(v.charAt(from-1)))from--;while(from>0&&!/\\s/.test(v.charAt(from-1)))from--;}
                else{var cps=Array.from(v.slice(0,a));cps.pop();from=cps.join('').length;}}
                setValue(v.slice(0,from)+v.slice(b),from,'deleteContentBackward',null);return true;
              }
              if(action==='cursor'&&editable){
                var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a;
                var pos=a!==b?((payload==='left'||payload==='up')?a:b):b;
                if(payload==='left')pos=Math.max(0,pos-1);if(payload==='right')pos=Math.min(v.length,pos+1);
                if(payload==='up'||payload==='down'){
                  var ls=v.lastIndexOf('\\n',Math.max(0,pos-1))+1,col=pos-ls;
                  if(payload==='up'&&ls>0){var pe=ls-1,ps=v.lastIndexOf('\\n',Math.max(0,pe-1))+1;pos=Math.min(ps+col,pe);}
                  if(payload==='down'){var ce=v.indexOf('\\n',pos);if(ce>=0){var ns=ce+1,ne=v.indexOf('\\n',ns);if(ne<0)ne=v.length;pos=Math.min(ns+col,ne);}}
                }
                try{e.setSelectionRange(pos,pos);}catch(_){}return true;
              }
              if(action==='enter'){
                var opt={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
                try{e.dispatchEvent(new KeyboardEvent('keydown',opt));}catch(_){}
                if(e.form){try{if(typeof e.form.requestSubmit==='function')e.form.requestSubmit();else e.form.submit();}catch(_){}return true;}
                if(tag==='TEXTAREA'&&editable){var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a;setValue(v.slice(0,a)+'\\n'+v.slice(b),a+1,'insertLineBreak','\\n');return true;}
                try{e.dispatchEvent(new KeyboardEvent('keyup',opt));}catch(_){}return true;
              }
              return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result -> if (result != "true") probeFocusedWebInput() }
    }

    private fun commitToFocusedWebInput(value: String) = runFocusedWebEdit("insert", value)
    private fun deleteFromFocusedWebInput(word: Boolean = false) = runFocusedWebEdit("delete", if (word) "word" else "char")
    private fun pressEnterInFocusedWebInput() = runFocusedWebEdit("enter")
    private fun moveFocusedWebInputCursor(keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            else -> return
        }
        runFocusedWebEdit("cursor", direction)
    }

    private fun scannerIsStrongMainProductText(line: ScannerTextLine, target: Rect, frameHeight: Int): Boolean {
        val text = line.text.trim().replace(Regex("\\s+"), " ")
        if (text.length !in 3..64 || text.count(Char::isLetter) < 2) return false
        if (OCR_URL_REGEX.containsMatchIn(text) || OCR_EMAIL_REGEX.containsMatchIn(text) ||
            OCR_PHONE_REGEX.containsMatchIn(text) || OCR_PRICE_REGEX.containsMatchIn(text) ||
            OCR_DATE_REGEX.containsMatchIn(text)) return false
        if (SCANNER_SMALL_SPEC_REGEX.matches(text)) return false

        val box = line.bounds ?: return text.length >= 9
        val heightRatio = box.height().toFloat() / frameHeight.coerceAtLeast(1)
        val widthRatio = box.width().toFloat() / target.width().coerceAtLeast(1)
        val compact = text.filterNot(Char::isWhitespace)
        val upperShort = compact.length <= 4 && compact.any(Char::isLetter) &&
            compact.filter(Char::isLetter).all(Char::isUpperCase)

        // Short all-caps snippets are only accepted when they are physically prominent. This keeps
        // a large brand such as HIT, JBL, ASUS, etc. but rejects tiny 50MP/SOMP-like camera specs.
        if (upperShort && heightRatio < 0.050f && widthRatio < 0.24f) return false
        if (compact.length <= 6 && compact.any(Char::isDigit) && heightRatio < 0.045f) return false
        return heightRatio >= 0.028f || widthRatio >= 0.20f || text.length >= 9
    }

    private fun scaleBitmapForLens(source: Bitmap, maxDimension: Int = 1000): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }


    private fun uploadBitmapForVisualSearch(source: Bitmap, queryHint: String): String? {
        val prepared = scaleBitmapForLens(source, 1280)
        val jpeg = runCatching {
            val output = ByteArrayOutputStream()
            check(prepared.compress(Bitmap.CompressFormat.JPEG, 88, output))
            output.toByteArray()
        }.getOrNull()
        if (prepared !== source && !prepared.isRecycled) prepared.recycle()
        if (jpeg == null || jpeg.isEmpty()) return null

        val timestamp = System.currentTimeMillis()
        val endpoints = listOf(
            "https://lens.google.com/v3/upload?ep=ccm&s=&st=$timestamp&hl=id" to true,
            "https://images.google.com/searchbyimage/upload" to false
        )
        for ((endpoint, includeDimensions) in endpoints) {
            val result = runCatching {
                uploadVisualSearchMultipart(endpoint, jpeg, source.width, source.height, includeDimensions)
            }.getOrNull()
            if (!result.isNullOrBlank()) return refineLensResultUrl(result, queryHint)
        }
        return null
    }

    private fun uploadVisualSearchMultipart(
        endpoint: String,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        includeDimensions: Boolean
    ): String? {
        val boundary = "----AIAdsKeyboard${UUID.randomUUID().toString().replace("-", "")}" 
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.6")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        java.io.DataOutputStream(connection.outputStream).use { body ->
            fun text(value: String) = body.write(value.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"encoded_image\"; filename=\"ai_ads_scan.jpg\"\r\n")
            text("Content-Type: image/jpeg\r\n\r\n")
            body.write(jpeg)
            text("\r\n")
            if (includeDimensions) {
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"processed_image_dimensions\"\r\n\r\n")
                text("$width,$height\r\n")
            } else {
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"image_content\"\r\n\r\n\r\n")
            }
            text("--$boundary--\r\n")
            body.flush()
        }

        val responseCode = connection.responseCode
        val responseCookies = connection.headerFields.entries
            .filter { (key, _) -> key?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value.orEmpty() }
            .filter { it.isNotBlank() }
        var location = connection.getHeaderField("Location").orEmpty().trim()
        if (location.isBlank()) {
            val stream = if (responseCode >= 400) connection.errorStream else connection.inputStream
            val html = runCatching { stream?.bufferedReader()?.use { it.readText().take(160_000) } }.getOrNull().orEmpty()
            location = Regex("(?i)href=[\\\"']([^\\\"']+)").find(html)?.groupValues?.getOrNull(1).orEmpty()
                .replace("&amp;", "&")
        }
        if (location.startsWith("//")) location = "https:$location"
        if (location.startsWith("/")) {
            val origin = Uri.parse(endpoint)
            location = "${origin.scheme}://${origin.host}$location"
        }
        val cookieTarget = location.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: endpoint
        if (responseCookies.isNotEmpty()) {
            runCatching {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                responseCookies.forEach { cookie -> cookieManager.setCookie(cookieTarget, cookie) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cookieManager.flush()
            }
        }
        connection.disconnect()
        return location.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }


    private fun refineLensResultUrl(rawUrl: String, mainText: String): String {
        // A Lens upload result already contains the visual-search token that identifies the image.
        // Appending q/udm turns that URL into a normal text/image Google search and discards the
        // visual-search experience. Preserve the original result and only localize the language.
        return runCatching {
            val uri = Uri.parse(rawUrl)
            if (!uri.getQueryParameter("hl").isNullOrBlank()) return@runCatching rawUrl
            uri.buildUpon().appendQueryParameter("hl", "id").build().toString()
        }.getOrDefault(rawUrl)
    }

    private fun performScannerSearch() {
        // Barcode/QR/direct URL remains exact and never needs AI vision.
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }

        scannerGalleryUri?.let {
            performGalleryAiVisionSearch(it)
            return
        }

        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
            return
        }
        scannerSearchButton?.isEnabled = false
        scannerSearchButton?.text = "…"
        scannerStatusText?.text = "Mengambil foto objek untuk AI Vision…"

        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar kamera belum siap · fokuskan objek lalu coba lagi"
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
                return@post
            }

            // Search exactly what the user is aiming at. PreviewView already reflects CameraX zoom,
            // then we crop to the scanner target so background outside the aimed area cannot dominate.
            val targetFrame = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForAiVision(targetFrame, 1536)
            val localHint = buildString {
                if (scannerCameraZoomRatio > 1.05f) {
                    append("Pengguna sedang memperbesar area target sekitar %.1fx. ".format(scannerCameraZoomRatio))
                }
                append("Prioritaskan subjek utama pada area ini dan detail kecil pembeda yang benar-benar terlihat; abaikan latar yang tidak relevan.")
                scannerMainProductText.trim().takeIf { it.isNotBlank() }?.let {
                    append(" Teks lokal yang mungkin relevan: ")
                    append(it.take(120))
                }
            }

            thread {
                val encoded = runCatching {
                    val output = java.io.ByteArrayOutputStream()
                    check(prepared.compress(Bitmap.CompressFormat.JPEG, 92, output))
                    android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
                }.getOrNull()

                val visualUrl: String? = null // Brave Search remains the embedded search surface.
                val result = if (encoded.isNullOrBlank()) {
                    Result.failure<AiResponse>(IllegalStateException("Foto kamera gagal disiapkan."))
                } else {
                    AiClient.visionProduct(aiSettings(), encoded, localHint)
                }

                if (prepared !== targetFrame && !prepared.isRecycled) prepared.recycle()
                if (targetFrame !== frame && !targetFrame.isRecycled) targetFrame.recycle()
                if (!frame.isRecycled) frame.recycle()

                handler.post {
                    val response = result.getOrNull()
                    val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                    if (!visualUrl.isNullOrBlank()) {
                        scannerSelectedQuery = query
                        scannerSelectedUrl = visualUrl
                        scannerResultText?.text = query.ifBlank { "Pencarian visual dari foto kamera" }
                        scannerStatusText?.text = if (query.isBlank()) "Mencari kecocokan visual dari foto asli…" else "AI Vision: $query"
                        searchQuery = query
                        searchUrl = refineLensResultUrl(visualUrl, query)
                        stopEmbeddedScanner()
                        showSearchWebPanel()
                        scannerSearchButton?.text = "Cari"
                        scannerSearchButton?.isEnabled = true
                        return@post
                    }
                    if (query.isBlank()) {
                        scannerSearchButton?.text = "Cari"
                        scannerSearchButton?.isEnabled = true
                        scannerStatusText?.text = aiVisionFailureMessage(result.exceptionOrNull())
                        scannerResultText?.text = "Foto tidak dikirim ke pencarian sampai AI mengenali objek dengan benar"
                        return@post
                    }

                    scannerSelectedQuery = query
                    scannerSelectedUrl = ""
                    scannerResultText?.text = query
                    scannerStatusText?.text = "AI Vision: $query"
                    searchQuery = query
                    searchUrl = selectedImageSearchUrl(query)
                    stopEmbeddedScanner()
                    showSearchWebPanel()
                    scannerSearchButton?.text = "Cari"
                    scannerSearchButton?.isEnabled = true
                }
            }
        }
    }

    private fun performGalleryAiVisionSearch(uri: Uri) {
        scannerSearchButton?.isEnabled = false
        scannerSearchButton?.text = "…"
        scannerStatusText?.text = "Menyiapkan gambar galeri untuk AI Vision…"

        thread {
            val decodedFrame = decodeGalleryBitmap(uri, 1800)
            if (decodedFrame == null || decodedFrame.width < 40 || decodedFrame.height < 40) {
                handler.post {
                    scannerSearchButton?.text = "Cari"
                    scannerSearchButton?.isEnabled = true
                    scannerStatusText?.text = "Gambar galeri gagal dibaca · pilih gambar lain"
                }
                return@thread
            }

            val frame = cropGalleryForCurrentZoom(decodedFrame)
            if (frame !== decodedFrame && !decodedFrame.isRecycled) decodedFrame.recycle()
            val prepared = scaleBitmapForAiVision(frame, 1536)
            val encoded = runCatching {
                val output = ByteArrayOutputStream()
                check(prepared.compress(Bitmap.CompressFormat.JPEG, 92, output))
                android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
            val visualUrl: String? = null // Brave Search remains the embedded search surface.
            val result = if (encoded.isNullOrBlank()) {
                Result.failure<AiResponse>(IllegalStateException("Gambar galeri gagal disiapkan."))
            } else {
                AiClient.visionProduct(aiSettings(), encoded, "")
            }

            if (prepared !== frame && !prepared.isRecycled) prepared.recycle()
            if (!frame.isRecycled) frame.recycle()

            handler.post {
                if (scannerGalleryUri != uri) return@post
                val response = result.getOrNull()
                val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                if (!visualUrl.isNullOrBlank()) {
                    scannerSelectedQuery = query
                    scannerSelectedUrl = visualUrl
                    scannerResultText?.text = query.ifBlank { "Pencarian visual dari foto galeri" }
                    scannerStatusText?.text = if (query.isBlank()) "Mencari kecocokan visual dari foto galeri…" else "AI Vision: $query"
                    searchQuery = query
                    searchUrl = refineLensResultUrl(visualUrl, query)
                    stopEmbeddedScanner()
                    showSearchWebPanel()
                    scannerSearchButton?.text = "Cari"
                    scannerSearchButton?.isEnabled = true
                    return@post
                }
                if (query.isBlank()) {
                    scannerSearchButton?.text = "Cari"
                    scannerSearchButton?.isEnabled = true
                    scannerStatusText?.text = aiVisionFailureMessage(result.exceptionOrNull())
                    scannerResultText?.text = "Gambar tidak dikirim ke pencarian sampai AI mengenalinya"
                    return@post
                }

                scannerSelectedQuery = query
                scannerSelectedUrl = ""
                scannerResultText?.text = query
                scannerStatusText?.text = "AI Vision: $query"
                searchQuery = query
                searchUrl = selectedImageSearchUrl(query)
                stopEmbeddedScanner()
                showSearchWebPanel()
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
            }
        }
    }

    private fun scaleBitmapForAiVision(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun cleanAiVisionSearchQuery(raw: String): String {
    val line = raw.lineSequence()
        .map { it.trim().trim('"', '\'', '`') }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    val cleaned = line
        .replace(Regex("(?i)^(?:query|search query|pencarian|hasil)\\s*:\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    val filler = setOf("the", "a", "an", "and", "in", "with", "setting", "of", "at", "on", "yang", "sedang", "terlihat")
    return cleaned.split(Regex("\\s+"))
        .filter { it.isNotBlank() && it.lowercase() !in filler }
        .take(7)
        .joinToString(" ")
        .take(64)
        .trim()
}

    private fun aiVisionFailureMessage(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains("API key", ignoreCase = true) -> "AI Vision belum bisa dipakai · isi API key di pengaturan"
            message.contains("model", ignoreCase = true) || message.contains("image", ignoreCase = true) ||
                message.contains("vision", ignoreCase = true) -> "Model AI yang dipilih belum mendukung gambar · pilih model vision/multimodal"
            else -> "AI Vision gagal · cek gateway 9Router :20130, API key, dan model/combo"
        }
    }

    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {
        val left = (frame.width * 0.07f).toInt().coerceIn(0, frame.width - 1)
        val top = (frame.height * 0.22f).toInt().coerceIn(0, frame.height - 1)
        val right = (frame.width * 0.93f).toInt().coerceIn(left + 1, frame.width)
        val bottom = (frame.height * 0.78f).toInt().coerceIn(top + 1, frame.height)
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }

    private fun openBraveImageResults(query: String) {
        val clean = query.replace(Regex("\\s+"), " ").trim().ifBlank { "produk benda fisik bentuk serupa" }
        searchQuery = clean
        searchUrl = selectedImageSearchUrl(clean)
        stopEmbeddedScanner()
        showSearchWebPanel()
    }

    private fun navigateEmbeddedSearchBack() {
        val web = searchWebView
        when {
            web != null && web.canGoBack() -> web.goBack()
            scannerActive -> closeSearchSurface()
            else -> {
                destroySearchWebView()
                showEmbeddedCameraPanel(resetCandidate = false)
                scannerPreviewView?.post { startEmbeddedScanner() }
            }
        }
    }

    private fun performSearchFromInput() {
        val query = searchInput?.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return
        searchQuery = query
        searchUrl = if (isWebUrl(query)) query else selectedWebSearchUrl(query)
        searchWebView?.loadUrl(searchUrl)
        searchInput?.setSelection(searchInput?.text?.length ?: 0)
    }

    private fun openInstalledAppForLink(rawUrl: String): Boolean {
        val searchUnwrapped = unwrapSearchRedirect(rawUrl)
        val url = unwrapBingRedirect(searchUnwrapped)
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

            // Known services: send the original HTTPS/App-Link URL directly to the
            // native package. This avoids Bing/browser claiming the link first.
            val knownPackages = nativePackagesForUrl(uri)
            for (candidate in knownPackages) {
                val direct = Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setPackage(candidate)
                val handled = runCatching {
                    startActivity(direct)
                    true
                }.getOrDefault(false)
                if (handled) return@runCatching true
            }

            // Generic App-Link fallback for other installed apps. Browsers are
            // deliberately excluded; if no native handler exists WebView returns false
            // and continues loading the page inside the keyboard panel.
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
    }

    private fun unwrapBingRedirect(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith("bing.com")) return rawUrl

        listOf("url", "r", "target").forEach { key ->
            val direct = uri.getQueryParameter(key).orEmpty().trim()
            if (direct.startsWith("http://", true) || direct.startsWith("https://", true)) {
                return direct
            }
        }

        val encoded = uri.getQueryParameter("u").orEmpty().trim()
        if (encoded.startsWith("http://", true) || encoded.startsWith("https://", true)) {
            return encoded
        }
        if (encoded.startsWith("a1") && encoded.length > 4) {
            val payload = encoded.substring(2)
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = runCatching {
                String(
                    android.util.Base64.decode(
                        padded,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                    ),
                    Charsets.UTF_8
                )
            }.getOrNull().orEmpty().trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                return decoded
            }
        }
        return rawUrl
    }

    private fun nativePackagesForUrl(uri: Uri): List<String> {
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        return when {
            host == "shopee.co.id" || host.endsWith(".shopee.co.id") ||
                host == "shopee.com" || host.endsWith(".shopee.com") -> listOf("com.shopee.id")
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                listOf("com.google.android.youtube")
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                listOf("com.instagram.android")
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") ->
                listOf("com.twitter.android")
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" ->
                listOf("com.facebook.katana")
            else -> emptyList()
        }
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

    private fun selectedSearchEngineId(): String =
    getSharedPreferences(PREFS, MODE_PRIVATE).getString("browser_search_engine", "brave") ?: "brave"

private fun selectedWebSearchUrl(query: String): String {
    val encoded = Uri.encode(query.trim())
    return when (selectedSearchEngineId()) {
        "google" -> "https://www.google.com/search?q=$encoded"
        "bing" -> "https://www.bing.com/search?q=$encoded"
        "ddg" -> "https://duckduckgo.com/?q=$encoded"
        else -> "https://search.brave.com/search?q=$encoded&source=web"
    }
}

private fun selectedImageSearchUrl(query: String): String {
    val encoded = Uri.encode(query.trim())
    return when (selectedSearchEngineId()) {
        "google" -> "https://www.google.com/search?tbm=isch&q=$encoded"
        "bing" -> "https://www.bing.com/images/search?q=$encoded"
        "ddg" -> "https://duckduckgo.com/?q=$encoded&iax=images&ia=images"
        else -> "https://search.brave.com/images?q=$encoded&source=web"
    }
}

    private fun braveSearchUrl(query: String): String = Uri.Builder()
        .scheme("https")
        .authority("search.brave.com")
        .path("search")
        .appendQueryParameter("q", query)
        .appendQueryParameter("source", "web")
        .build()
        .toString()

    private fun isWebUrl(value: String): Boolean = runCatching {
        Uri.parse(value).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun destroySearchWebView() {
        searchWebComposeActive = false
        searchWebView?.let { webView ->
            webView.stopLoading()
            webView.webChromeClient = android.webkit.WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        searchWebView = null
    }

    private fun openSettings() {
        toggleSettingsPanel(true)
    }

    private fun activeInternalInput(): EditText? = when {
        settingsPanelVisible && ::settingsPanel.isInitialized && settingsPanel.activeInput != null -> settingsPanel.activeInput
        searchComposeActive -> searchInput
        aiComposeActive && ::aiInput.isInitialized -> aiInput
        else -> null
    }

    private fun deleteOne() {
        if (searchWebComposeActive) {
            deleteFromFocusedWebInput()
            refreshSuggestionsSoon()
            return
        }
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceAtLeast(0)
            val end = internalInput.selectionEnd.coerceAtLeast(0)
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
        if (searchWebComposeActive) {
            deleteFromFocusedWebInput(word = true)
            refreshSuggestionsSoon()
            return
        }
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceAtLeast(0)
            val end = internalInput.selectionEnd.coerceAtLeast(0)
            if (start != end) editable.delete(minOf(start, end), maxOf(start, end))
            else if (start > 0) {
                val before = editable.substring(0, start)
                val trailing = before.takeLastWhile(Char::isWhitespace).length
                val body = before.dropLast(trailing)
                val count = (trailing + body.takeLastWhile { !it.isWhitespace() }.length).coerceAtLeast(1)
                editable.delete((start - count).coerceAtLeast(0), start)
            }
            refreshSuggestionsSoon()
            return
        }
        val ic = currentInputConnection ?: return
        if (deleteSelectedText(ic)) { refreshSuggestionsSoon(); return }
        val before = runCatching { ic.getTextBeforeCursor(100, 0)?.toString().orEmpty() }.getOrDefault("")
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }
        refreshSuggestionsSoon()
    }

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
        if (searchWebComposeActive) {
            deleteFromFocusedWebInput()
            return true
        }
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceAtLeast(0)
            val end = internalInput.selectionEnd.coerceAtLeast(0)
            return when {
                start != end -> { editable.delete(minOf(start, end), maxOf(start, end)); true }
                start > 0 -> { editable.delete(start - 1, start); true }
                else -> false
            }
        }
        val ic = currentInputConnection ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ic.deleteSurroundingTextInCodePoints(1, 0)
            else ic.deleteSurroundingText(1, 0)
        }.getOrDefault(false)
    }

    private fun deleteSelectedText(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isEmpty()) return false
        ic.commitText("", 1)
        return true
    }


    private fun standardEditorAction(info: EditorInfo? = currentInputEditorInfo): Int =
        info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE

    private fun editorHasAction(info: EditorInfo? = currentInputEditorInfo): Boolean {
        info ?: return false
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        val action = standardEditorAction(info)
        return (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ||
            info.actionId != 0
    }

    private fun resolvedEditorAction(info: EditorInfo? = currentInputEditorInfo): Int {
        info ?: return EditorInfo.IME_ACTION_NONE
        val action = standardEditorAction(info)
        return if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            action
        } else {
            info.actionId.takeIf { it != 0 } ?: action
        }
    }

    private fun editorSupportsNewLine(): Boolean {
        if (searchWebComposeActive || activeInternalInput() != null) return false
        val info = currentInputEditorInfo ?: return false
        if (editorHasAction(info)) return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        val multiline = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val imeMultiline = info.inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        return multiline || imeMultiline || noEnterAction
    }

    private fun customEditorActionIcon(info: EditorInfo?): String {
        val label = info?.actionLabel?.toString()?.trim()?.lowercase().orEmpty()
        return when {
            label.contains("search") || label.contains("cari") -> "🔍"
            label.contains("send") || label.contains("kirim") -> "➤"
            label.contains("done") || label.contains("selesai") -> "✓"
            label.contains("previous") || label.contains("sebelum") -> "←"
            else -> "→"
        }
    }

    private fun enterKeyLabel(): String {
        if (searchComposeActive || searchWebComposeActive) return "🔍"
        if (aiComposeActive) return "↑"

        val info = currentInputEditorInfo
        val action = resolvedEditorAction(info)
        if (editorHasAction(info)) {
            return when (action) {
                EditorInfo.IME_ACTION_SEARCH -> "🔍"
                EditorInfo.IME_ACTION_SEND -> "➤"
                EditorInfo.IME_ACTION_GO -> "→"
                EditorInfo.IME_ACTION_NEXT -> "→"
                EditorInfo.IME_ACTION_PREVIOUS -> "←"
                EditorInfo.IME_ACTION_DONE -> "✓"
                else -> customEditorActionIcon(info)
            }
        }
        return "↵"
    }

    private fun performEditorActionCompat(action: Int) {
        val ic = currentInputConnection ?: return
        val performed = runCatching { ic.performEditorAction(action) }.getOrDefault(false)
        if (performed) return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    private fun pressEnter() {
        if (searchWebComposeActive) {
            pressEnterInFocusedWebInput()
            return
        }
        if (searchComposeActive) {
            performSearchFromInput()
            return
        }
        if (aiComposeActive) {
            runAiConversation()
            return
        }
        learnCurrentBoundary(completed = true)
        val info = currentInputEditorInfo
        val action = resolvedEditorAction(info)
        if (editorHasAction(info)) {
            performEditorActionCompat(action)
        } else if (editorSupportsNewLine()) {
            val ic = currentInputConnection
            val committed = runCatching { ic?.commitText("\n", 1) == true }.getOrDefault(false)
            if (!committed && ic != null) {
                val now = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
            }
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
        if (searchWebComposeActive) {
            commitToFocusedWebInput(text)
            refreshSuggestionsSoon()
            return
        }
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
        if (searchWebComposeActive) {
            commitToFocusedWebInput(" ")
            return
        }
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
        return aiInput.text.toString().trim()
    }

    private fun automaticReplyContext(ic: InputConnection): String {
        return aiInput.text.toString().trim()
    }

    /**
     * Chooses a translation source without requiring text to be pasted into the AI composer.
     * An explicit selection wins; otherwise the currently visible application is read on demand.
     * Shared text, the active editor, and the clipboard are safe fallbacks when Accessibility
     * cannot expose the application's view hierarchy.
     */
    private fun automaticTranslationContext(ic: InputConnection): String {
        return aiInput.text.toString().trim()
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
            nineRouterApiKey = prefs.getString("9router_api_key", "").orEmpty(),
            nineRouterBaseUrl = prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1").orEmpty(),
            nineRouterModel = prefs.getString("9router_model", "cc/claude-sonnet-4-20250514").orEmpty(),
            bluesMindsApiKey = prefs.getString("bluesminds_api_key", "").orEmpty(),
            bluesMindsBaseUrl = prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),
            bluesMindsModel = prefs.getString("bluesminds_model", "deepseek-ai/deepseek-v4-flash").orEmpty(),
            xKiroApiKey = prefs.getString("xkiro_api_key", "").orEmpty(),
            xKiroBaseUrl = prefs.getString("xkiro_base_url", "https://api.xkiro.com/v1").orEmpty(),
            xKiroModel = prefs.getString("xkiro_model", "openai/gpt-5.6-sol").orEmpty(),
            orcaRouterApiKey = prefs.getString("orcarouter_api_key", "").orEmpty(),
            orcaRouterBaseUrl = prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1").orEmpty(),
            orcaRouterModel = prefs.getString("orcarouter_model", "orcarouter/free").orEmpty(),
            aiHordeApiKey = prefs.getString("aihorde_api_key", "").orEmpty(),
            aiHordeModel = prefs.getString("aihorde_model", "aphrodite/TheDrummer/Cydonia-24B-v4.3,koboldcpp/DarkIdol-Llama-3.1-8B-Instruct-1.2-Uncensored.Q8_0").orEmpty(),
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
        if (!aiPanelVisible) toggleAiPanel(true)

        val input = aiInput.text.toString().trim()
        if (input.isBlank()) {
            aiStatus.text = "Tempel atau ketik teks di kotak AI terlebih dahulu."
            aiAnswer.text = "Jawaban AI akan muncul di sini."
            aiInput.requestFocus()
            aiComposeActive = true
            return
        }

        aiComposeActive = false
        aiInput.clearFocus()
        if (styleMemoryEnabled) {
            TypingStyleMemory.observeCompletedText(getSharedPreferences(PREFS, MODE_PRIVATE), input)
        }
        aiStatus.text = "$action sedang diproses dari teks yang kamu masukkan…"
        aiAnswer.text = "Menunggu jawaban…"

        thread {
            val result = AiClient.transform(aiSettings(), action, input)
            aiStatus.post {
                result.onSuccess { response ->
                    pendingText = response.text
                    aiAnswer.text = response.text
                    aiStatus.text = "Hasil via ${response.provider.label} · ketuk jawaban atau Pakai"
                }.onFailure { error ->
                    aiAnswer.text = ""
                    aiStatus.text = error.message ?: "Permintaan AI gagal."
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
        val appContext = ""
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
        private const val SCANNER_FRAME_INTERVAL_MS = 240L
        private const val SCANNER_CANDIDATE_HOLD_MS = 1_250L
        private const val SCANNER_STABILITY_WINDOW_MS = 1_400L
        private const val SCANNER_REQUIRED_STABLE_HITS = 2
        private const val WEB_FOCUS_PROBE_DELAY_MS = 55L
        private const val WEB_USER_GESTURE_TIMEOUT_MS = 240L
        private const val WEB_ORIENTATION_RECHECK_DELAY_MS = 900L

        private val OCR_URL_REGEX = Regex("""(?i)\b(?:https?://|www\.)[^\s<>\"']+""")
        private val OCR_EMAIL_REGEX = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
        private val OCR_PHONE_REGEX = Regex("""(?<!\d)(?:\+?62|0)[\d\s-]{8,15}(?!\d)""")
        private val OCR_PRICE_REGEX = Regex("""(?i)(?:Rp\.?|IDR|USD|\$)\s?\d[\d.,]*""")
        private val OCR_DATE_REGEX = Regex("""(?i)\b(?:\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2}\s+(?:jan|feb|mar|apr|mei|jun|jul|agu|sep|okt|nov|des)[a-z]*\s+\d{2,4})\b""")
        private val SCANNER_SMALL_SPEC_REGEX = Regex("""(?i)^\s*(?:\d{1,5}\s*(?:mp|mah|gb|tb|hz|khz|mhz|ghz|w|kw|v|a|cm|mm|ml|l|g|kg|pcs?)|[so5][o0]\s*mp)\s*$""")

        private val SCANNER_IGNORED_LABELS = setOf(
            "pattern", "font", "text", "line", "rectangle", "design", "graphics", "screenshot",
            "display", "screen", "material", "event", "art", "photography", "brand"
        )

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
