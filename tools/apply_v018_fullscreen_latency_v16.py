from pathlib import Path

path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = path.read_text(encoding='utf-8')


def rep(old: str, new: str, label: str, count: int = 1):
    global s
    if new in s:
        return
    if old not in s:
        raise RuntimeError(f'{label}: target not found')
    s = s.replace(old, new, count)

# 1) The fullscreen root used the raw physical display height. An IME window already starts
# below the status bar, so using the full display height pushes the last keyboard rows below the
# visible IME area. Subtract only the top status-bar inset (the IME owns/draws the bottom nav area).
helper = '''    private fun fullscreenRootHeightPx(): Int {
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

'''
if 'private fun fullscreenRootHeightPx()' not in s:
    marker = '    private fun applyRootHeight() {'
    if marker not in s:
        raise RuntimeError('fullscreen height helper insertion marker not found')
    s = s.replace(marker, helper + marker, 1)

rep(
    '            val screenHeight = resources.displayMetrics.heightPixels\n',
    '            val screenHeight = fullscreenRootHeightPx()\n',
    'fullscreen available height'
)

# 2) Key response must always happen on ACTION_DOWN. Sensitivity still controls touch tolerance,
# but it no longer adds a release-wait delay at lower settings.
rep(
    '        instantKeyResponse = sensitivity >= INSTANT_RESPONSE_THRESHOLD\n',
    '        instantKeyResponse = true\n',
    'always immediate key response'
)

# 3) Suggestions are useful, but parsing/ranking them after nearly every keystroke competes with
# the IME main thread on heavy apps such as TikTok. Debounce until the user pauses briefly.
rep(
    '        handler.postDelayed(suggestionRefreshRunnable, 35L)\n',
    '        handler.postDelayed(suggestionRefreshRunnable, 140L)\n',
    'suggestion debounce'
)

rep(
    '''        val before = textBeforeTypingCursor()
        val candidates = SuggestionEngine''',
    '''        val before = textBeforeTypingCursor()
        val currentFragment = before.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        if (before.isNotBlank() && !before.last().isWhitespace() && currentFragment.length < 2) {
            suggestionBar.visibility = View.VISIBLE
            return
        }
        val candidates = SuggestionEngine''',
    'skip expensive prediction on first character'
)

# 4) Commit the key before popup-preview/vibration work. PopupWindow construction and vibration
# binder calls were previously ahead of commitText(), making the keyboard feel delayed even when
# instant response was enabled.
old_down = '''                MotionEvent.ACTION_DOWN -> {
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
                }'''
new_down = '''                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longTriggered = false
                    actionTriggered = false
                    if (instantKeyResponse) {
                        // Input first. Visual/haptic extras are intentionally deferred so a busy
                        // host app never has to wait for the keyboard's preview animation.
                        spec.action()
                        actionTriggered = true
                        handler.post { keyFeedback(view, longPress = false) }
                    }
                    keyFace.background = referenceBubbleKeyBackground(pressed = true)
                    view.translationY = dpFloat(1.7f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(0.6f)
                    handler.post {
                        if (view.isAttachedToWindow) showKeyPreview(view, spec)
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
                }'''
rep(old_down, new_down, 'ACTION_DOWN input-first routing')

# 5) Reuse one popup instead of allocating a PopupWindow + TextView + gradients for every key.
start = s.find('    private fun showKeyPreview(anchor: View, spec: KeySpec) {')
end = s.find('    private fun updateKeyPreview(label: String) {', start)
if start < 0 or end < 0:
    raise RuntimeError('key preview function markers not found')
new_preview = '''    private fun showKeyPreview(anchor: View, spec: KeySpec) {
        if (!shouldShowKeyPreview(spec) || !anchor.isAttachedToWindow) return
        keyPreviewDismissRunnable?.let(handler::removeCallbacks)
        keyPreviewDismissRunnable = null

        val previewWidth = dp(if (isLandscape()) 48 else 58)
        val previewHeight = dp(if (isLandscape()) 58 else 72)
        var previewLabel = activeKeyPreviewLabel
        var popup = activeKeyPreview
        if (previewLabel == null || popup == null) {
            previewLabel = TextView(this).apply {
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
            popup = PopupWindow(previewLabel, previewWidth, previewHeight, false).apply {
                isTouchable = false
                isOutsideTouchable = false
                isClippingEnabled = false
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dpFloat(10f)
            }
            activeKeyPreview = popup
            activeKeyPreviewLabel = previewLabel
        } else {
            if (popup.isShowing) popup.dismiss()
            popup.width = previewWidth
            popup.height = previewHeight
        }

        previewLabel.text = spec.label
        previewLabel.textSize = if (spec.label.length > 2) 25f else 31f
        val xOffset = (anchor.width - previewWidth) / 2
        val yOffset = -(anchor.height + previewHeight + dp(4))
        runCatching { popup.showAsDropDown(anchor, xOffset, yOffset) }

        keyPreviewDismissRunnable = Runnable {
            if (activeKeyPreview === popup && popup.isShowing) popup.dismiss()
        }.also { handler.postDelayed(it, longPressDurationMs + 350L) }
    }

'''
s = s[:start] + new_preview + s[end:]

old_dismiss = '''    private fun dismissKeyPreview() {
        keyPreviewDismissRunnable?.let(handler::removeCallbacks)
        keyPreviewDismissRunnable = null
        activeKeyPreviewLabel = null
        activeKeyPreview?.dismiss()
        activeKeyPreview = null
    }'''
new_dismiss = '''    private fun dismissKeyPreview(release: Boolean = false) {
        keyPreviewDismissRunnable?.let(handler::removeCallbacks)
        keyPreviewDismissRunnable = null
        activeKeyPreview?.let { if (it.isShowing) it.dismiss() }
        if (release) {
            activeKeyPreviewLabel = null
            activeKeyPreview = null
        }
    }'''
rep(old_dismiss, new_dismiss, 'reusable key preview dismiss')
rep(
    '        dismissKeyPreview()\n        handler.removeCallbacksAndMessages(null)\n',
    '        dismissKeyPreview(release = true)\n        handler.removeCallbacksAndMessages(null)\n',
    'release popup on service destroy'
)

# 6) Cache system services used on every key tap instead of resolving them each time.
cache_marker = '    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }\n'
cache_new = '''    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    private val keyboardAudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val keyboardVibrator by lazy { getSystemService(VIBRATOR_SERVICE) as Vibrator }
'''
rep(cache_marker, cache_new, 'cache per-key system services')

rep(
    '            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEY_CLICK, 0.35f)\n',
    '            keyboardAudioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.35f)\n',
    'cached audio manager'
)
rep(
    '            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator\n            val duration = if (longPress) (vibrationDurationMs + 12).coerceAtMost(100) else vibrationDurationMs\n            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))\n            } else {\n                @Suppress("DEPRECATION")\n                vibrator.vibrate(duration)\n            }\n',
    '            val duration = if (longPress) (vibrationDurationMs + 12).coerceAtMost(100) else vibrationDurationMs\n            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {\n                keyboardVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))\n            } else {\n                @Suppress("DEPRECATION")\n                keyboardVibrator.vibrate(duration)\n            }\n',
    'cached vibrator'
)

path.write_text(s, encoding='utf-8')
print('Applied v0.18 v16: correct fullscreen top inset and input-first low-latency keys.')
