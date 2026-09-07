from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
CORE_PATCH = ROOT / "tools/apply_bluesminds_provider_patch_core.py"
KEYBOARD_SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
BRAVE_BROWSER = ROOT / "app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt"

# Preserve the existing BluesMinds patch exactly, then apply only the requested
# keyboard visual/touch fixes and visual-search attachment fixes on top of the
# generated source. The AI/browser patches are intentionally narrow so unrelated
# keyboard behavior remains untouched.
runpy.run_path(str(CORE_PATCH), run_name="__main__")

text = KEYBOARD_SERVICE.read_text()


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    if old not in source:
        raise RuntimeError(f"Keyboard visual patch marker not found: {label}")
    return source.replace(old, new, 1)


def patch_key_view(source: str) -> str:
    start_marker = "    private fun keyView(spec: KeySpec): View {"
    end_marker = "\n    private fun keyFeedback(view: View, longPress: Boolean) {"
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: keyView block")

    block = source[start:end]

    # Keep the previous optical centering correction for descender letters.
    old_label = '            if (spec.alternate != null) translationY = dpFloat(4f)'
    new_label = '''            if (spec.label in setOf("q", "y", "p", "g", "j")) {
                translationY = dpFloat(-2f)
            } else if (spec.alternate != null && spec.label.none { it.isLetterOrDigit() }) translationY = dpFloat(4f)'''
    block = replace_once(block, old_label, new_label, "descender centering")

    # Keep the proven working touch path on the actual key frame. The border is
    # part of this frame, so pressing anywhere on the visible face/border uses
    # the same listener and action as the legend area.
    block = replace_once(
        block,
        '''                    actionTriggered = false
                    if (instantKeyResponse) {''',
        '''                    actionTriggered = false
                    keyFace.background = referenceBubbleKeyBackground(pressed = true)
                    if (instantKeyResponse) {''',
        "pressed key border feedback",
    )
    block = replace_once(
        block,
        '''                    dismissKeyPreview()
                    val moved = hypot(event.x - downX, event.y - downY)''',
        '''                    dismissKeyPreview()
                    keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    val moved = hypot(event.x - downX, event.y - downY)''',
        "release key border feedback",
    )
    block = replace_once(
        block,
        '''                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    dismissKeyPreview()
                    true''',
        '''                MotionEvent.ACTION_CANCEL -> {
                    longRunnable?.let(handler::removeCallbacks)
                    dismissKeyPreview()
                    keyFace.background = referenceBubbleKeyBackground(pressed = false)
                    true''',
        "cancel key border feedback",
    )

    return source[:start] + block + source[end:]


def patch_neon_key_border(source: str) -> str:
    start_marker = "    private fun referenceBubbleKeyBackground(pressed: Boolean): GradientDrawable {"
    end_marker = "\n    private fun referenceTopRimBackground(): GradientDrawable ="
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: reference key background")

    block = source[start:end]
    old_stroke = '''            setStroke(
                dp(1),
                if (pressed) Color.rgb(67, 65, 77) else Color.rgb(97, 94, 108)
            )'''
    new_stroke = '''            setStroke(
                dp(if (pressed) 3 else 2),
                if (pressed) Color.rgb(205, 124, 255) else Color.rgb(181, 86, 249)
            )'''
    block = replace_once(block, old_stroke, new_stroke, "reference neon purple border")
    return source[:start] + block + source[end:]


def patch_cursor_keys(source: str) -> str:
    # Cursor-arrow buttons keep their original proven touch listener directly on
    # the visible frame, while using the same thinner neon purple border.
    start_marker = "    private fun cursorDirectionButton(label: String, keyCode: Int): View {"
    end_marker = "\n    private fun cursorPadTouchListener(): View.OnTouchListener {"
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("Keyboard visual patch marker not found: cursorDirectionButton")

    block = source[start:end]
    block = replace_once(
        block,
        "            background = roundedBackground(specialKeyBg, 11f)\n",
        "            background = roundedStrokedBackground(specialKeyBg, 11f, Color.rgb(181, 86, 249), 2)\n",
        "cursor normal purple border",
    )
    block = replace_once(
        block,
        "                    view.background = roundedBackground(pressedKeyBg, 11f)\n",
        "                    view.background = roundedStrokedBackground(pressedKeyBg, 11f, Color.rgb(205, 124, 255), 3)\n",
        "cursor pressed border",
    )
    block = replace_once(
        block,
        "                    view.background = roundedBackground(specialKeyBg, 11f)\n",
        "                    view.background = roundedStrokedBackground(specialKeyBg, 11f, Color.rgb(181, 86, 249), 2)\n",
        "cursor released border",
    )
    source = source[:start] + block + source[end:]

    # Touchpad/d-pad container borders use the same thinner reference purple.
    old_cursor_stroke = "        setStroke(dp(2), if (pressed) Color.rgb(214, 133, 255) else Color.rgb(127, 86, 180))"
    new_cursor_stroke = "        setStroke(dp(if (pressed) 3 else 2), if (pressed) Color.rgb(205, 124, 255) else Color.rgb(181, 86, 249))"
    source = replace_once(source, old_cursor_stroke, new_cursor_stroke, "cursor pad neon border")
    return source


def patch_vision_attachment_service(source: str) -> str:
    # Preserve the exact camera/gallery crop the user is looking at before the preview
    # bitmap is recycled. The browser can then carry that image together with the AI
    # Vision query instead of reducing the search to text only.
    camera_marker = '''            val prepared = scaleBitmapForAiVision(targetFrame, 1536)
            val localHint = buildString {'''
    camera_replacement = '''            val prepared = scaleBitmapForAiVision(targetFrame, 1536)
            VisionBrowserAttachment.capture(prepared, "camera")
            val localHint = buildString {'''
    source = replace_once(source, camera_marker, camera_replacement, "camera vision attachment capture")

    gallery_marker = '''            val prepared = scaleBitmapForAiVision(frame, 1536)
            val encoded = runCatching {'''
    gallery_replacement = '''            val prepared = scaleBitmapForAiVision(frame, 1536)
            VisionBrowserAttachment.capture(prepared, "gallery")
            val encoded = runCatching {'''
    source = replace_once(source, gallery_marker, gallery_replacement, "gallery vision attachment capture")

    query_marker = '''                    val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()'''
    query_replacement = '''                    val query = response?.text?.let(::cleanAiVisionSearchQuery).orEmpty()
                    VisionBrowserAttachment.updateQuery(query)'''
    query_count = source.count(query_marker)
    if query_count < 2 and query_replacement not in source:
        raise RuntimeError("Keyboard visual patch marker not found: camera/gallery Vision query")
    if query_replacement not in source:
        source = source.replace(query_marker, query_replacement, 2)

    direct_marker = '''        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)'''
    direct_replacement = '''        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            VisionBrowserAttachment.clear()
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)'''
    source = replace_once(source, direct_marker, direct_replacement, "clear stale attachment for direct scan")

    failure_marker = '''                    if (query.isBlank()) {
                        scannerSearchButton?.text = "Cari"'''
    failure_replacement = '''                    if (query.isBlank()) {
                        VisionBrowserAttachment.clear()
                        scannerSearchButton?.text = "Cari"'''
    if failure_replacement not in source:
        if source.count(failure_marker) < 2:
            raise RuntimeError("Keyboard visual patch marker not found: clear failed Vision attachment")
        source = source.replace(failure_marker, failure_replacement, 2)

    return source


def patch_brave_visual_attachment(source: str) -> str:
    source = replace_once(
        source,
        "import android.graphics.Color\n",
        "import android.graphics.Color\nimport android.graphics.BitmapFactory\n",
        "browser BitmapFactory import",
    )
    source = replace_once(
        source,
        "import android.widget.FrameLayout\n",
        "import android.widget.FrameLayout\nimport android.widget.ImageView\n",
        "browser ImageView import",
    )

    field_marker = '''    private var trackerCount = 0
'''
    field_replacement = '''    private var trackerCount = 0
    private var visionAttachmentInjectedAt = 0L
    private var visionAttachmentRetryCount = 0
    private var visionPromptSubmittedAt = 0L
    private var visionPromptRetryCount = 0
'''
    source = replace_once(source, field_marker, field_replacement, "browser Vision attachment state")

    top_marker = '''        topBar = buildTopBar()
        mainColumn.addView(topBar, LinearLayout.LayoutParams(-1, dp(54)))

        webView = WebView(context).apply {'''
    top_replacement = '''        topBar = buildTopBar()
        mainColumn.addView(topBar, LinearLayout.LayoutParams(-1, dp(54)))
        buildVisionAttachmentBar()?.let { attachmentBar ->
            mainColumn.addView(attachmentBar, LinearLayout.LayoutParams(-1, dp(50)))
        }

        webView = WebView(context).apply {'''
    source = replace_once(source, top_marker, top_replacement, "browser attached-image bar")

    finished_marker = '''                        installBraveSearchSettingsBridge(view, clean)
                    }
                }'''
    finished_replacement = '''                        installBraveSearchSettingsBridge(view, clean)
                        if (view != null) injectPendingVisionAttachment(view, clean)
                    }
                }'''
    source = replace_once(source, finished_marker, finished_replacement, "browser visual attachment page hook")

    method_marker = '''    private fun buildBottomBar(): View {'''
    if "private fun buildVisionAttachmentBar(): View?" not in source:
        if method_marker not in source:
            raise RuntimeError("Keyboard visual patch marker not found: browser bottom bar")
        methods = r'''    private fun buildVisionAttachmentBar(): View? {
        val snapshot = VisionBrowserAttachment.snapshot() ?: return null
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(7), dp(5))
            setBackgroundColor(darkBar)
        }

        val thumbBitmap = runCatching {
            BitmapFactory.decodeByteArray(snapshot.jpegBytes, 0, snapshot.jpegBytes.size)
        }.getOrNull()
        row.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(thumbBitmap)
            background = rounded(darkField, 9f)
            clipToOutline = true
            contentDescription = "Gambar AI Vision terlampir"
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            rightMargin = dp(8)
        })

        row.addView(TextView(context).apply {
            text = snapshot.query.ifBlank { "Gambar AI Vision terlampir ke pencarian" }
            textSize = 13.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(lightText)
        }, LinearLayout.LayoutParams(0, -1, 1f))

        row.addView(TextView(context).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(mutedText)
            contentDescription = "Lepas gambar dari pencarian"
            setOnClickListener {
                VisionBrowserAttachment.clear()
                row.visibility = View.GONE
            }
        }, LinearLayout.LayoutParams(dp(36), -1))
        return row
    }

    /**
     * Bing/Google image pages already expose an image file input. Feed the camera/gallery JPEG
     * directly into that input so the browser receives the original visual evidence, not only the
     * AI-generated text prompt. If a provider changes its DOM this gracefully falls back to the
     * normal text image search while the native attachment chip remains visible.
     */
    private fun injectPendingVisionAttachment(view: WebView, rawUrl: String) {
        val snapshot = VisionBrowserAttachment.snapshot() ?: return
        val host = runCatching { Uri.parse(rawUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val isBing = host == "bing.com" || host.endsWith(".bing.com")
        val isGoogle = host == "google.com" || host.endsWith(".google.com") || host == "lens.google.com"
        if (!isBing && !isGoogle) return

        if (visionAttachmentInjectedAt == snapshot.createdAtMs) {
            injectPendingVisionPrompt(view, snapshot)
            return
        }
        if (visionAttachmentRetryCount >= 14) return

        val base64 = android.util.Base64.encodeToString(snapshot.jpegBytes, android.util.Base64.NO_WRAP)
        val base64Json = JSONObject.quote(base64)
        val script = """
            (function() {
              try {
                var inputs = Array.prototype.slice.call(document.querySelectorAll('input[type=file]'));
                var input = inputs.find(function(el) {
                  var accept = (el.getAttribute('accept') || '').toLowerCase();
                  return !accept || accept.indexOf('image') >= 0 || accept.indexOf('*/*') >= 0;
                });
                if (!input) return 'waiting';
                var binary = atob($base64Json);
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                var file = new File([bytes], 'ai_ads_vision.jpg', {type:'image/jpeg'});
                var transfer = new DataTransfer();
                transfer.items.add(file);
                try { input.files = transfer.files; }
                catch (_) { Object.defineProperty(input, 'files', { configurable:true, value:transfer.files }); }
                input.dispatchEvent(new Event('input', {bubbles:true}));
                input.dispatchEvent(new Event('change', {bubbles:true}));
                return 'attached';
              } catch (e) {
                return 'error:' + String(e && e.message || e);
              }
            })();
        """.trimIndent()

        view.evaluateJavascript(script) { raw ->
            if (raw?.contains("attached") == true) {
                visionAttachmentInjectedAt = snapshot.createdAtMs
                visionAttachmentRetryCount = 0
                postDelayed({ if (webView === view) injectPendingVisionPrompt(view, snapshot) }, 1200L)
            } else {
                visionAttachmentRetryCount += 1
                postDelayed({ if (webView === view) injectPendingVisionAttachment(view, view.url.orEmpty()) }, 260L)
            }
        }
    }

    private fun injectPendingVisionPrompt(view: WebView, snapshot: VisionBrowserAttachment.Snapshot) {
        if (snapshot.query.isBlank() || visionPromptSubmittedAt == snapshot.createdAtMs) return
        if (visionPromptRetryCount >= 14) return
        val queryJson = JSONObject.quote(snapshot.query)
        val script = """
            (function() {
              try {
                var query = $queryJson;
                var nodes = Array.prototype.slice.call(document.querySelectorAll('input[type=search], input[type=text], input:not([type]), textarea'));
                function visible(el) {
                  var r = el.getBoundingClientRect();
                  var s = window.getComputedStyle(el);
                  return r.width > 80 && r.height > 18 && s.display !== 'none' && s.visibility !== 'hidden';
                }
                var field = nodes.find(function(el) {
                  if (!visible(el)) return false;
                  var hint = ((el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                  return /add to search|add to your search|tambahkan ke pencarian|tambahkan ke penelusuran|tambahkan ke pencarian anda/.test(hint);
                });
                if (!field) return 'waiting';
                var tag = (field.tagName || '').toUpperCase();
                var proto = tag === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) desc.set.call(field, query); else field.value = query;
                field.focus();
                try { field.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:query})); }
                catch (_) { field.dispatchEvent(new Event('input', {bubbles:true})); }
                field.dispatchEvent(new Event('change', {bubbles:true}));
                setTimeout(function() {
                  try {
                    if (field.form && typeof field.form.requestSubmit === 'function') field.form.requestSubmit();
                    else {
                      var opt = {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true};
                      field.dispatchEvent(new KeyboardEvent('keydown', opt));
                      field.dispatchEvent(new KeyboardEvent('keyup', opt));
                    }
                  } catch (_) {}
                }, 120);
                return 'submitted';
              } catch (e) {
                return 'error:' + String(e && e.message || e);
              }
            })();
        """.trimIndent()

        view.evaluateJavascript(script) { raw ->
            if (raw?.contains("submitted") == true) {
                visionPromptSubmittedAt = snapshot.createdAtMs
                visionPromptRetryCount = 0
            } else {
                visionPromptRetryCount += 1
                postDelayed({ if (webView === view) injectPendingVisionPrompt(view, snapshot) }, 360L)
            }
        }
    }

'''
        source = source.replace(method_marker, methods + method_marker, 1)

    return source


text = patch_key_view(text)
text = patch_neon_key_border(text)
text = patch_cursor_keys(text)
text = patch_vision_attachment_service(text)
KEYBOARD_SERVICE.write_text(text)

browser_text = BRAVE_BROWSER.read_text()
browser_text = patch_brave_visual_attachment(browser_text)
BRAVE_BROWSER.write_text(browser_text)

print("Keyboard neon touch + camera/gallery browser image attachment patch applied")
