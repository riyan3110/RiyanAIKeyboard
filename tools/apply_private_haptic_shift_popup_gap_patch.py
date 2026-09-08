#!/usr/bin/env python3
from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
SETTINGS = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch marker not found: {label}")
    return text.replace(old, new, 1)


s = SERVICE.read_text(encoding="utf-8")

# Keep normal key haptics ahead of actions that can synchronously re-render the
# keyboard (especially auto-capitalization). This removes the "capital appears,
# then vibration arrives" feeling.
s = replace_once(
    s,
    '''                        spec.action()
                        actionTriggered = true
                        // Keep tactile feedback in ultra-fast mode too, but run it immediately
                        // instead of adding one Runnable per key to the main-thread queue.
                        keyFeedback(view, longPress = false)''',
    '''                        // Give tactile feedback first so an auto-cap render can never delay vibration.
                        keyFeedback(view, longPress = false)
                        spec.action()
                        actionTriggered = true''',
    "instant key haptic before action",
)

s = replace_once(
    s,
    '''                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        spec.action()
                        keyFeedback(view, longPress = false)
                        actionTriggered = true
                    }''',
    '''                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        // Feedback must happen before actions such as auto-capitalization re-render the keyboard.
                        keyFeedback(view, longPress = false)
                        spec.action()
                        actionTriggered = true
                    }''',
    "release key haptic before action",
)

# Restore optional key-preview state without changing current behavior by default.
# Existing users stay OFF until they enable the new setting.
s = replace_once(
    s,
    '''    private var automaticCapitalizationEnabled = true
    private var brandTextEnabled = true
    private var punctuationSpaceEnabled = false''',
    '''    private var automaticCapitalizationEnabled = true
    private var brandTextEnabled = true
    private var keyPreviewEnabled = false
    private var punctuationSpaceEnabled = false''',
    "key preview service state",
)

s = replace_once(
    s,
    '''        brandTextEnabled = prefs.getBoolean("brand_text_enabled", true)
        if (::bottomBrandBar.isInitialized) {
            bottomBrandBar.visibility = if (brandTextEnabled) View.VISIBLE else View.GONE
            bottomBrandBar.layoutParams = bottomBrandBar.layoutParams.apply { height = dp(brandBarHeightDp()) }
        }
        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)''',
    '''        brandTextEnabled = prefs.getBoolean("brand_text_enabled", true)
        if (::bottomBrandBar.isInitialized) {
            bottomBrandBar.visibility = if (brandTextEnabled) View.VISIBLE else View.GONE
            bottomBrandBar.layoutParams = bottomBrandBar.layoutParams.apply { height = dp(brandBarHeightDp()) }
        }
        keyPreviewEnabled = prefs.getBoolean("key_preview_enabled", false)
        punctuationSpaceEnabled = prefs.getBoolean("punctuation_space_enabled", false)''',
    "load key preview preference",
)

# A Shift press can re-render every key. In ultra-fast mode, triggering Shift on
# ACTION_DOWN can replace the touched view while the finger is still down and can
# occasionally look like a second press. Keep haptic feedback instant, but execute
# Shift exactly once on ACTION_UP.
s = replace_once(
    s,
    '''                    keyFace.background = referenceBubbleKeyBackground(pressed = true, baseColor = normalColor)
                    if (instantKeyResponse) {
                        // Input first. Visual/haptic extras are intentionally deferred so a busy
                        // host app never has to wait for the keyboard's preview animation.
                        // Give tactile feedback first so an auto-cap render can never delay vibration.
                        keyFeedback(view, longPress = false)
                        spec.action()
                        actionTriggered = true
                    }''',
    '''                    keyFace.background = referenceBubbleKeyBackground(pressed = true, baseColor = normalColor)
                    val shiftKey = spec.label == "⇧" || spec.label == "⇪"
                    if (instantKeyResponse) {
                        // Haptic stays on ACTION_DOWN, before any action/render work.
                        keyFeedback(view, longPress = false)
                    }
                    if (keyPreviewEnabled) showKeyPreview(view, spec)
                    if (instantKeyResponse && !shiftKey) {
                        spec.action()
                        actionTriggered = true
                    }''',
    "defer shift action and show optional preview",
)

s = replace_once(
    s,
    '''                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        // Feedback must happen before actions such as auto-capitalization re-render the keyboard.
                        keyFeedback(view, longPress = false)
                        spec.action()
                        actionTriggered = true
                    }''',
    '''                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        val shiftKey = spec.label == "⇧" || spec.label == "⇪"
                        // Shift already vibrated on ACTION_DOWN in instant mode. Other release-mode
                        // keys vibrate here, still before their action.
                        if (!(instantKeyResponse && shiftKey)) {
                            keyFeedback(view, longPress = false)
                        }
                        spec.action()
                        actionTriggered = true
                    }''',
    "single shift action on release",
)

# Extra guard against duplicate Shift callbacks while preserving intentional
# double-tap Caps Lock. Human double taps remain valid; only near-simultaneous
# duplicate callbacks are ignored.
s = replace_once(
    s,
    '''    private var capsLock = false
    private var lastShiftTapAt = 0L
    private var pendingText: String? = null''',
    '''    private var capsLock = false
    private var lastShiftTapAt = 0L
    private var lastShiftActionAt = 0L
    private var pendingText: String? = null''',
    "shift duplicate guard state",
)

s = replace_once(
    s,
    '''        val now = SystemClock.elapsedRealtime()
        when {
            capsLock -> {''',
    '''        val now = SystemClock.elapsedRealtime()
        if (now - lastShiftActionAt < SHIFT_ACTION_DEBOUNCE_MS) return
        lastShiftActionAt = now
        when {
            capsLock -> {''',
    "shift duplicate callback debounce",
)

s = replace_once(
    s,
    '''        private const val INSTANT_RESPONSE_THRESHOLD = 150
        private const val DOUBLE_TAP_SHIFT_MS = 420L''',
    '''        private const val INSTANT_RESPONSE_THRESHOLD = 150
        private const val SHIFT_ACTION_DEBOUNCE_MS = 110L
        private const val DOUBLE_TAP_SHIFT_MS = 420L''',
    "shift debounce constant",
)

# Re-enable the letter/number/symbol popup implementation behind the new toggle.
s = replace_once(
    s,
    '''    private fun showKeyPreview(anchor: View, spec: KeySpec) {
        // v18: popup huruf/angka/simbol dinonaktifkan untuk respons tombol maksimal.
    }

    private fun updateKeyPreview(label: String) {
        // No popup preview in v18.
    }''',
    '''    private fun showKeyPreview(anchor: View, spec: KeySpec) {
        if (!keyPreviewEnabled || !shouldShowKeyPreview(spec) || !anchor.isAttachedToWindow) return
        dismissKeyPreview()

        val previewWidth = dp(if (isLandscape()) 48 else 58)
        val previewHeight = dp(if (isLandscape()) 58 else 72)
        val previewLabel = TextView(this).apply {
            text = spec.label
            textSize = if (spec.label.length > 2) 25f else 31f
            gravity = Gravity.CENTER
            setTextColor(keyTextColor)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(dpFloat(1.5f), 0f, dpFloat(1f), Color.BLACK)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(79, 76, 90),
                    Color.rgb(41, 39, 49)
                )
            ).apply {
                cornerRadius = dpFloat(18f)
                setStroke(dp(2), keyBorderColor)
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
        if (keyPreviewEnabled) activeKeyPreviewLabel?.text = label
    }''',
    "restore toggleable key preview popup",
)

# Tighten left/right spacing to the same visual rhythm as top/bottom spacing.
# 1dp margin on all four sides gives a 2dp gap between adjacent standard keys
# both horizontally and vertically.
s = replace_once(
    s,
    '''                    setMargins(dp(2), if (isFirstKeyboardRow) dp(1) else dp(3), dp(2), dp(3))''',
    '''                    setMargins(dp(1), dp(1), dp(1), dp(1))''',
    "uniform standard key gaps",
)

SERVICE.write_text(s, encoding="utf-8")


k = SETTINGS.read_text(encoding="utf-8")

k = replace_once(
    k,
    '''        var autoCaps: Boolean,
        var showBrandText: Boolean,
        var punctuationSpace: Boolean,''',
    '''        var autoCaps: Boolean,
        var showBrandText: Boolean,
        var keyPreview: Boolean,
        var punctuationSpace: Boolean,''',
    "settings key preview draft field",
)

k = replace_once(
    k,
    '''        body.addView(toggleCard("Getaran / Umpan Balik Haptik", "Memberikan getaran taktil saat mengetik.", draft.vibration) { draft.vibration = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val vibrationCard = cardContainer()''',
    '''        body.addView(toggleCard("Getaran / Umpan Balik Haptik", "Memberikan getaran taktil saat mengetik.", draft.vibration) { draft.vibration = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(toggleCard("Pop-up Tombol Keyboard", "Menampilkan huruf, angka, atau simbol dalam pop-up saat tombol ditekan.", draft.keyPreview) { draft.keyPreview = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val vibrationCard = cardContainer()''',
    "settings key preview toggle",
)

k = replace_once(
    k,
    '''            .putBoolean("automatic_capitalization_enabled", draft.autoCaps)
            .putBoolean("brand_text_enabled", draft.showBrandText)
            .putBoolean("punctuation_space_enabled", draft.punctuationSpace)''',
    '''            .putBoolean("automatic_capitalization_enabled", draft.autoCaps)
            .putBoolean("brand_text_enabled", draft.showBrandText)
            .putBoolean("key_preview_enabled", draft.keyPreview)
            .putBoolean("punctuation_space_enabled", draft.punctuationSpace)''',
    "save key preview preference",
)

k = replace_once(
    k,
    '''        autoCaps = prefs.getBoolean("automatic_capitalization_enabled", true),
        showBrandText = prefs.getBoolean("brand_text_enabled", true),
        punctuationSpace = prefs.getBoolean("punctuation_space_enabled", false),''',
    '''        autoCaps = prefs.getBoolean("automatic_capitalization_enabled", true),
        showBrandText = prefs.getBoolean("brand_text_enabled", true),
        keyPreview = prefs.getBoolean("key_preview_enabled", false),
        punctuationSpace = prefs.getBoolean("punctuation_space_enabled", false),''',
    "load key preview preference in settings",
)

k = replace_once(
    k,
    '''        autoCaps = true,
        showBrandText = true,
        punctuationSpace = false,''',
    '''        autoCaps = true,
        showBrandText = true,
        keyPreview = false,
        punctuationSpace = false,''',
    "reset key preview preference",
)

SETTINGS.write_text(k, encoding="utf-8")

print("Applied PRIVATE keyboard interaction fixes: immediate haptic, single Shift action, optional key popup, uniform gaps")
