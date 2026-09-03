from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')

# Imports
if 'import android.graphics.Bitmap\n' not in s:
    s = s.replace('import android.graphics.Color\n', 'import android.graphics.Bitmap\nimport android.graphics.Color\n', 1)
if 'import java.io.ByteArrayOutputStream\n' not in s:
    s = s.replace('import java.util.concurrent.Executors\n', 'import java.io.ByteArrayOutputStream\nimport java.net.HttpURLConnection\nimport java.net.URL\nimport java.util.UUID\nimport java.util.concurrent.Executors\n', 1)

# Visual search is available even when OCR/ML labeling has no useful name for the object.
if 'private var scannerVisualSearchPreferred' not in s:
    marker = '    private var scannerSelectedUrl = ""\n'
    if marker not in s:
        raise RuntimeError('scannerSelectedUrl marker missing')
    s = s.replace(marker, marker + '    private var scannerVisualSearchPreferred = true\n', 1)

# Reset camera state to physical visual matching by default.
reset_old = '''            scannerSelectedQuery = ""
            scannerSelectedUrl = ""
            scannerLastCandidateAt = 0L'''
reset_new = '''            scannerSelectedQuery = ""
            scannerSelectedUrl = ""
            scannerVisualSearchPreferred = true
            scannerLastCandidateAt = 0L'''
if reset_new not in s:
    if reset_old not in s:
        raise RuntimeError('scanner reset marker missing')
    s = s.replace(reset_old, reset_new, 1)

# Barcode/URL is structured and should keep its direct behavior.
barcode_old = '        setScannerCandidate(query, directUrl, 1_000, label)\n    }'
barcode_new = '        scannerVisualSearchPreferred = false\n        setScannerCandidate(query, directUrl, 1_000, label)\n    }'
if barcode_new not in s:
    if barcode_old not in s:
        raise RuntimeError('barcode candidate marker missing')
    s = s.replace(barcode_old, barcode_new, 1)

# Cari in camera is always usable, even for an unknown object with zero readable text.
search_old = '''        scannerSearchButton = compactButton("Cari") { openSearchResults(scannerSelectedQuery, scannerSelectedUrl) }.apply {
            isEnabled = scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank()
        }'''
search_new = '''        scannerSearchButton = compactButton("Cari") { performScannerSearch() }.apply {
            isEnabled = true
        }'''
if search_new not in s:
    if search_old not in s:
        raise RuntimeError('camera Cari marker missing')
    s = s.replace(search_old, search_new, 1)

# Replace the camera button immediately beside Cari with Back.
button_old = '''        header.addView(compactButton("📷") { launchScanner() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })'''
button_new = '''        header.addView(compactButton("←") { navigateEmbeddedSearchBack() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })'''
if button_new not in s:
    if button_old not in s:
        raise RuntimeError('web camera button marker missing')
    s = s.replace(button_old, button_new, 1)

# If OCR/labels know nothing, preserve the ability to do a real visual search from pixels.
empty_old = '''        if (queryParts.isEmpty() && directUrl.isBlank()) return
        val query = directUrl.ifBlank { queryParts.joinToString(" ").take(180) }'''
empty_new = '''        if (queryParts.isEmpty() && directUrl.isBlank()) {
            scannerVisualSearchPreferred = true
            handler.post {
                scannerStatusText?.text = "Objek fisik siap · tekan Cari untuk pencarian visual"
                scannerResultText?.text = "Cari bentuk/model objek yang sama"
                scannerSearchButton?.isEnabled = true
            }
            return
        }
        val query = directUrl.ifBlank { queryParts.joinToString(" ").take(180) }'''
if empty_new not in s:
    if empty_old not in s:
        raise RuntimeError('empty visual result marker missing')
    s = s.replace(empty_old, empty_new, 1)

# Short OCR such as 50MP/SOMP must not override the physical object itself.
source_old = '''        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            ranked.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks dan objek fisik di area bidik terdeteksi"
            ranked.isNotEmpty() -> if (structured) "Data di area bidik terdeteksi" else "Tulisan utama di area bidik terdeteksi"
            else -> "Objek fisik di area bidik terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)'''
source_new = '''        val strongestText = ranked.firstOrNull()?.first?.text.orEmpty()
        val strongestTextScore = ranked.firstOrNull()?.second ?: 0
        val physicalDominant = directUrl.isBlank() && (
            ranked.isEmpty() || strongestText.length <= 8 || strongestTextScore < 58
        )
        scannerVisualSearchPreferred = physicalDominant
        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            physicalDominant -> "Objek fisik siap · pencarian visual berdasarkan bentuk/model"
            ranked.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks dan objek fisik di area bidik terdeteksi"
            ranked.isNotEmpty() -> if (structured) "Data di area bidik terdeteksi" else "Tulisan utama di area bidik terdeteksi"
            else -> "Objek fisik di area bidik terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)'''
if source_new not in s:
    if source_old not in s:
        raise RuntimeError('visual dominance marker missing')
    s = s.replace(source_old, source_new, 1)

helpers = r'''    private fun performScannerSearch() {
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }
        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
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
            crop.compress(Bitmap.CompressFormat.JPEG, 92, out)
            val jpeg = out.toByteArray()
            val width = crop.width
            val height = crop.height
            if (crop !== frame) crop.recycle()
            frame.recycle()
            scannerStatusText?.text = "Mencari bentuk/model yang paling mirip…"
            thread {
                val resultUrl = uploadToLensVisualSearch(jpeg, width, height)
                handler.post {
                    if (resultUrl.isNullOrBlank()) {
                        scannerStatusText?.text = "Pencarian visual gagal tersambung · coba Cari lagi"
                    } else {
                        searchQuery = scannerSelectedQuery.ifBlank { "Pencarian visual objek" }
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

    /** Upload actual cropped camera pixels for web visual matching, not only an OCR/ML label. */
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

if '    private fun performScannerSearch() {' not in s:
    marker = '    private fun performSearchFromInput() {'
    if marker not in s:
        raise RuntimeError('helper insertion marker missing')
    s = s.replace(marker, helpers + '\n\n' + marker, 1)

p.write_text(s, encoding='utf-8')
print('Applied true pixel visual search for any physical object plus Back button.')
