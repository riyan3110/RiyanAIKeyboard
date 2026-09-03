from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')


def rep(old, new, label, count=1):
    global s
    if new in s:
        return
    if old not in s:
        raise RuntimeError(f'{label}: target not found')
    s = s.replace(old, new, count)


def insert_before(marker, text, label):
    global s
    if text.strip() in s:
        return
    if marker not in s:
        raise RuntimeError(f'{label}: marker not found')
    s = s.replace(marker, text.rstrip() + '\n\n' + marker, 1)

# Imports for actual pixel-based visual search upload.
rep('import android.graphics.Color\n', 'import android.graphics.Bitmap\nimport android.graphics.Color\n', 'Bitmap import')
rep('import java.util.concurrent.Executors\n', 'import java.io.ByteArrayOutputStream\nimport java.net.HttpURLConnection\nimport java.net.URL\nimport java.util.UUID\nimport java.util.concurrent.Executors\n', 'network imports')

# State: when the physical object is dominant, Cari must use the actual camera pixels instead of
# a generic image-label string.
rep(
'''    private var scannerSelectedUrl = ""
    private var scannerBestScore = 0''',
'''    private var scannerSelectedUrl = ""
    private var scannerVisualSearchPreferred = false
    private var scannerBestScore = 0''',
'visual state'
)
rep(
'''            scannerSelectedUrl = ""
            scannerLastCandidateAt = 0L''',
'''            scannerSelectedUrl = ""
            scannerVisualSearchPreferred = false
            scannerLastCandidateAt = 0L''',
'visual state reset'
)

# Barcode/structured content should not trigger a Lens upload.
rep(
'''        setScannerCandidate(query, directUrl, 1_000, label)
    }''',
'''        scannerVisualSearchPreferred = false
        setScannerCandidate(query, directUrl, 1_000, label)
    }''',
'barcode visual override'
)

# Camera Cari button now chooses true visual search for physical objects.
rep(
'''        scannerSearchButton = compactButton("Cari") { openSearchResults(scannerSelectedQuery, scannerSelectedUrl) }.apply {''',
'''        scannerSearchButton = compactButton("Cari") { performScannerSearch() }.apply {''',
'camera search action'
)

# The button immediately to the right of Cari is Back, not Camera.
rep(
'''        header.addView(compactButton("📷") { launchScanner() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })''',
'''        header.addView(compactButton("←") { navigateEmbeddedSearchBack() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })''',
'web back button'
)

# Decide physical dominance after OCR/label analysis. Short accidental OCR such as "50MP"/"SOMP"
# must not prevent visual matching of a case, shoe, tool, bottle, device, etc.
needle = '''        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            ranked.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks dan objek fisik di area bidik terdeteksi"
            ranked.isNotEmpty() -> if (structured) "Data di area bidik terdeteksi" else "Tulisan utama di area bidik terdeteksi"
            else -> "Objek fisik di area bidik terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)'''
replacement = '''        val strongestText = ranked.firstOrNull()?.first?.text.orEmpty()
        val strongestTextScore = ranked.firstOrNull()?.second ?: 0
        val physicalDominant = directUrl.isBlank() && usefulLabels.isNotEmpty() && (
            ranked.isEmpty() || strongestText.length <= 8 || strongestTextScore < 58
        )
        scannerVisualSearchPreferred = physicalDominant
        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            physicalDominant -> "Objek fisik dikenali · pencarian visual siap"
            ranked.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks dan objek fisik di area bidik terdeteksi"
            ranked.isNotEmpty() -> if (structured) "Data di area bidik terdeteksi" else "Tulisan utama di area bidik terdeteksi"
            else -> "Objek fisik di area bidik terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)'''
rep(needle, replacement, 'physical dominance')

helpers = r'''    private fun performScannerSearch() {
        if (!scannerVisualSearchPreferred) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }
        val preview = scannerPreviewView ?: run {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }
        scannerStatusText?.text = "Mengambil bentuk objek untuk pencarian visual…"
        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar belum siap · arahkan objek lalu coba lagi"
                return@post
            }
            val crop = cropScannerVisualTarget(frame)
            val out = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 90, out)
            val jpeg = out.toByteArray()
            val width = crop.width
            val height = crop.height
            if (crop !== frame) crop.recycle()
            frame.recycle()
            scannerStatusText?.text = "Mencari bentuk/model yang mirip…"
            thread {
                val resultUrl = uploadToLensVisualSearch(jpeg, width, height)
                handler.post {
                    if (resultUrl.isNullOrBlank()) {
                        scannerStatusText?.text = "Pencarian visual gagal · memakai hasil pengenalan objek"
                        openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
                    } else {
                        searchQuery = scannerSelectedQuery.ifBlank { "Pencarian visual" }
                        searchUrl = resultUrl
                        stopEmbeddedScanner()
                        showSearchWebPanel()
                    }
                }
            }
        }
    }

    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {
        val left = (frame.width * 0.07f).toInt().coerceIn(0, frame.width - 1)
        val top = (frame.height * 0.22f).toInt().coerceIn(0, frame.height - 1)
        val right = (frame.width * 0.93f).toInt().coerceIn(left + 1, frame.width)
        val bottom = (frame.height * 0.78f).toInt().coerceIn(top + 1, frame.height)
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }

    /**
     * Sends the actual cropped camera pixels to the same Lens upload endpoint used by Chromium's
     * image-search UI. This is a real visual-match search, not a text label lookup.
     */
    private fun uploadToLensVisualSearch(jpeg: ByteArray, width: Int, height: Int): String? = runCatching {
        val boundary = "----AIAdsKeyboard${UUID.randomUUID().toString().replace("-", "")}" 
        val endpoint = URL("https://lens.google.com/v3/upload?ep=ccm&s=&st=${System.currentTimeMillis()}")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.buffered().use { output ->
            fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"encoded_image\"; filename=\"camera.jpg\"\r\n")
            text("Content-Type: image/jpeg\r\n\r\n")
            output.write(jpeg)
            text("\r\n--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"processed_image_dimensions\"\r\n\r\n")
            text("$width,$height")
            text("\r\n--$boundary--\r\n")
        }
        val code = connection.responseCode
        val finalUrl = connection.url?.toString().orEmpty()
        runCatching { connection.inputStream?.close() }
        connection.disconnect()
        finalUrl.takeIf { code in 200..399 && it.startsWith("https://", ignoreCase = true) }
    }.getOrNull()

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
    }'''
insert_before('    private fun performSearchFromInput() {', helpers, 'visual search helpers')

p.write_text(s, encoding='utf-8')
print('Applied true pixel-based visual search and Back button fix.')
