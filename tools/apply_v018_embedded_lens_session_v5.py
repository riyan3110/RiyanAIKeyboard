from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')


def replace_block(start_marker, end_marker, replacement, label):
    global s
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: block not found')
    s = s[:a] + replacement.rstrip() + '\n\n' + s[b:]


# State for a one-shot Lens upload that is performed by the same WebView which will display the
# result. Google Lens result URLs are session-bound, so keeping upload + result in one WebView
# avoids the empty/error Lens page produced when an HttpURLConnection/native app owns the upload.
state_marker = '    private var scannerVisualSearchPreferred = true\n'
state = '''    private var scannerVisualSearchPreferred = true
    private var pendingLensImageBase64 = ""
    private var pendingLensImageWidth = 0
    private var pendingLensImageHeight = 0
    private var pendingLensUploadRunning = false
'''
if 'private var pendingLensImageBase64' not in s:
    if state_marker not in s:
        raise RuntimeError('visual search state marker missing')
    s = s.replace(state_marker, state, 1)

# Ensure camera reset/close cannot accidentally submit an older photograph.
close_old = '''    private fun closeSearchSurface() {
        searchComposeActive = false'''
close_new = '''    private fun closeSearchSurface() {
        pendingLensImageBase64 = ""
        pendingLensImageWidth = 0
        pendingLensImageHeight = 0
        pendingLensUploadRunning = false
        searchComposeActive = false'''
if close_new not in s:
    if close_old not in s:
        raise RuntimeError('close search surface marker missing')
    s = s.replace(close_old, close_new, 1)

# Camera search: structured barcode/URL keeps its exact route. Any ordinary physical object is
# captured from the white target rectangle, scaled, JPEG encoded, and handed to the embedded
# Lens WebView. No readable text or ML label is required for the button to work.
perform = r'''    private fun performScannerSearch() {
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }

        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
            scannerSearchButton?.isEnabled = true
            return
        }

        scannerSearchButton?.isEnabled = false
        scannerSearchButton?.text = "…"
        scannerStatusText?.text = "Mengambil gambar objek dari area bidik…"

        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar belum siap · ketuk objek untuk fokus lalu Cari lagi"
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
                return@post
            }

            val crop = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForLens(crop, 900)
            thread {
                val out = ByteArrayOutputStream()
                val encoded = runCatching {
                    check(prepared.compress(Bitmap.CompressFormat.JPEG, 80, out))
                    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                }.getOrNull()
                val width = prepared.width
                val height = prepared.height

                if (prepared !== crop) prepared.recycle()
                if (crop !== frame) crop.recycle()
                frame.recycle()

                handler.post {
                    if (encoded.isNullOrBlank()) {
                        scannerStatusText?.text = "Gambar gagal disiapkan · tekan Cari untuk mencoba lagi"
                        scannerSearchButton?.text = "Cari"
                        scannerSearchButton?.isEnabled = true
                        return@post
                    }

                    pendingLensImageBase64 = encoded
                    pendingLensImageWidth = width
                    pendingLensImageHeight = height
                    pendingLensUploadRunning = false
                    searchQuery = scannerMainProductText.ifBlank { "Pencarian visual gambar" }
                    searchUrl = "https://lens.google.com/"

                    // Pre-set the anonymous consent cookie before loading Lens. The upload itself is
                    // then issued by this same WebView, so Google's session/cookies stay attached to
                    // the result page that is shown in the keyboard panel.
                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setCookie("https://lens.google.com/", "SOCS=CAESHAgBEhIaAB; Path=/; Domain=.google.com; Secure; SameSite=None")
                        flush()
                    }

                    scannerStatusText?.text = "Mengirim gambar ke Google untuk pencocokan visual…"
                    stopEmbeddedScanner()
                    showSearchWebPanel()
                }
            }
        }
    }'''
replace_block(
    '    private fun performScannerSearch() {',
    '    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {',
    perform,
    'embedded Lens scanner search',
)

# Inject a same-origin multipart upload after lens.google.com itself has loaded. The browser session
# that posts the pixels is therefore exactly the browser session that opens the returned Lens URL.
helper = r'''    private fun maybeRunPendingLensUpload(webView: WebView, pageUrl: String) {
        if (pendingLensUploadRunning || pendingLensImageBase64.isBlank()) return
        val host = runCatching { Uri.parse(pageUrl).host.orEmpty().lowercase() }.getOrDefault("")
        if (host != "lens.google.com") return

        val image = pendingLensImageBase64
        val width = pendingLensImageWidth
        val height = pendingLensImageHeight
        if (width <= 0 || height <= 0) return

        pendingLensUploadRunning = true
        // Clear the one-shot payload before JavaScript runs so a result-page onPageFinished cannot
        // submit the same photo again.
        pendingLensImageBase64 = ""
        pendingLensImageWidth = 0
        pendingLensImageHeight = 0

        val imageJson = JSONObject.quote(image)
        val script = """
            (function(){
              try {
                var b64=$imageJson;
                var raw=atob(b64);
                var bytes=new Uint8Array(raw.length);
                for(var i=0;i<raw.length;i++) bytes[i]=raw.charCodeAt(i);
                var blob=new Blob([bytes],{type:'image/jpeg'});
                var form=new FormData();
                form.append('encoded_image',blob,'camera.jpg');
                form.append('processed_image_dimensions','$width,$height');
                var endpoint='https://lens.google.com/v3/upload?ep=ccm&s=&hl=id&st='+Date.now();
                fetch(endpoint,{method:'POST',body:form,credentials:'include',redirect:'follow'})
                  .then(function(r){
                    var finalUrl=r.url||'';
                    if(finalUrl.indexOf('/search')>=0 || finalUrl.indexOf('/uploadbyurl')>=0){
                      window.location.replace(finalUrl); return null;
                    }
                    return r.text();
                  })
                  .then(function(html){
                    if(!html) return;
                    var decoded=html.replace(/&amp;/g,'&').replace(/\\u003d/g,'=').replace(/\\u0026/g,'&').replace(/\\\\\//g,'/');
                    var m=decoded.match(/https:\/\/lens\.google\.com\/(?:search|uploadbyurl)\?[^\"'\\s<>]+/i) ||
                          decoded.match(/https:\/\/(?:www\.)?google\.[a-z.]+\/search\?[^\"'\\s<>]+/i);
                    if(m&&m[0]) window.location.replace(m[0]);
                    else throw new Error('Lens result URL not found');
                  })
                  .catch(function(){
                    document.body.innerHTML='<div style="font-family:sans-serif;padding:24px"><b>Pencarian visual gagal tersambung.</b><br><br>Tekan tombol kembali lalu coba Cari lagi.</div>';
                  });
              } catch(e) {
                document.body.innerHTML='<div style="font-family:sans-serif;padding:24px"><b>Gambar gagal dikirim ke Google.</b><br><br>Tekan tombol kembali lalu coba lagi.</div>';
              }
            })();
        """.trimIndent()
        webView.postDelayed({
            if (searchWebView === webView) webView.evaluateJavascript(script, null)
        }, 180L)
    }'''

if '    private fun maybeRunPendingLensUpload(' not in s:
    marker = '    private fun performSearchFromInput() {'
    if marker not in s:
        raise RuntimeError('Lens helper insertion marker missing')
    s = s.replace(marker, helper + '\n\n' + marker, 1)

# Hook Lens upload into the existing WebView lifecycle while preserving the direct-web typing probe.
on_page_old = '''                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.postDelayed({ probeFocusedWebInput() }, WEB_FOCUS_PROBE_DELAY_MS)
                }'''
on_page_new = '''                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.postDelayed({ probeFocusedWebInput() }, WEB_FOCUS_PROBE_DELAY_MS)
                    view?.let { maybeRunPendingLensUpload(it, url.orEmpty()) }
                }'''
if on_page_new not in s:
    if on_page_old not in s:
        raise RuntimeError('WebView onPageFinished marker missing')
    s = s.replace(on_page_old, on_page_new, 1)

# Camera Cari must never depend on OCR/ML confidence. This also repairs older generated variants
# where a previous patch left the button disabled until a text candidate existed.
old_button = '''        scannerSearchButton = compactButton("Cari") { performScannerSearch() }.apply {
            isEnabled = true
        }'''
new_button = '''        scannerSearchButton = compactButton("Cari") {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performScannerSearch()
        }.apply {
            isEnabled = true
            isClickable = true
            isFocusable = false
        }'''
if new_button not in s:
    if old_button not in s:
        raise RuntimeError('camera Cari button marker missing')
    s = s.replace(old_button, new_button, 1)

p.write_text(s, encoding='utf-8')
print('Applied v0.18 embedded Lens session v5: clickable Cari, same-WebView image upload, embedded visual results.')
