package com.riyan.aikeyboard

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("riyan_ai", MODE_PRIVATE) }
    private lateinit var settingsView: KeyboardSettingsOverlay
    private var themePickerOpen = false

    private val themeImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        themePickerOpen = false
        if (uri == null) return@registerForActivityResult

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        prefs.edit()
            .putString("keyboard_theme_image_uri", uri.toString())
            .putString("keyboard_theme_mode", KeyboardTheme.MODE_PHOTO)
            .apply()

        if (::settingsView.isInitialized) {
            settingsView.refreshExternalChanges()
        }
        Toast.makeText(this, "Foto tema tersimpan.", Toast.LENGTH_SHORT).show()
    }

    override fun attachBaseContext(newBase: Context) {
        val compactConfig = Configuration(newBase.resources.configuration).apply {
            val sourceDensity = if (densityDpi > 0) {
                densityDpi
            } else {
                newBase.resources.displayMetrics.densityDpi
            }
            densityDpi = (sourceDensity * PAGE_UI_SCALE).roundToInt().coerceAtLeast(120)
            fontScale = minOf(fontScale, 1.0f)
        }
        super.attachBaseContext(newBase.createConfigurationContext(compactConfig))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = BACKGROUND_DIM }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val host = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        settingsView = KeyboardSettingsOverlay(
            context = this,
            prefs = prefs,
            onApply = {
                // Settings are persisted by KeyboardSettingsOverlay itself.
            },
            onClose = { closeSettingsTask() },
            onInputFocusChanged = { hasFocus ->
                if (hasFocus && ::settingsView.isInitialized) {
                    settingsView.activeInput?.let { field ->
                        field.post {
                            field.showSoftInputOnFocus = true
                            field.requestFocus()
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            },
            onPickThemePhoto = { openThemePhotoPicker() }
        ).apply {
            visibility = android.view.View.VISIBLE
        }

        host.addView(
            settingsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )

        setContentView(host)

        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom)
            )
            view.post { applyReferencePanelBounds(host) }
            insets
        }
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyReferencePanelBounds(host)
        }
        ViewCompat.requestApplyInsets(host)

        settingsView.show()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsView.isInitialized) settingsView.refreshExternalChanges()
    }

    fun openThemePhotoPicker() {
        if (themePickerOpen) return
        themePickerOpen = true
        runCatching {
            themeImagePicker.launch(arrayOf("image/*"))
        }.onFailure {
            themePickerOpen = false
            Toast.makeText(this, "Galeri tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Android API; retained so Back matches the close button behavior")
    override fun onBackPressed() {
        closeSettingsTask()
    }

    private fun applyReferencePanelBounds(host: FrameLayout) {
        if (!::settingsView.isInitialized || host.width <= 0 || host.height <= 0) return

        val safeWidth = (host.width - host.paddingLeft - host.paddingRight).coerceAtLeast(1)
        val safeHeight = (host.height - host.paddingTop - host.paddingBottom).coerceAtLeast(1)
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

        val widthRatio = if (portrait) PORTRAIT_WIDTH_RATIO else LANDSCAPE_WIDTH_RATIO
        val heightRatio = if (portrait) PORTRAIT_HEIGHT_RATIO else LANDSCAPE_HEIGHT_RATIO
        val topRatio = if (portrait) PORTRAIT_TOP_OFFSET_RATIO else LANDSCAPE_TOP_OFFSET_RATIO

        val params = (settingsView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        val targetWidth = (safeWidth * widthRatio).roundToInt()
        val targetHeight = (safeHeight * heightRatio).roundToInt()
        val targetTopMargin = (safeHeight * topRatio).roundToInt()

        if (params.width == targetWidth &&
            params.height == targetHeight &&
            params.topMargin == targetTopMargin &&
            params.gravity == (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        ) return

        params.width = targetWidth
        params.height = targetHeight
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.topMargin = targetTopMargin
        settingsView.layoutParams = params
    }

    private fun closeSettingsTask() {
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PAGE_UI_SCALE = 0.86f
        private const val BACKGROUND_DIM = 0.52f
        private const val PORTRAIT_WIDTH_RATIO = 0.95f
        private const val PORTRAIT_HEIGHT_RATIO = 0.84f
        private const val PORTRAIT_TOP_OFFSET_RATIO = 0.045f
        private const val LANDSCAPE_WIDTH_RATIO = 0.90f
        private const val LANDSCAPE_HEIGHT_RATIO = 0.90f
        private const val LANDSCAPE_TOP_OFFSET_RATIO = 0.02f
    }
}
