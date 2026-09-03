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

rep('import android.graphics.Color\n', 'import android.graphics.Bitmap\nimport android.graphics.Color\n', 'Bitmap import')
rep('import java.util.concurrent.Executors\n', 'import java.io.ByteArrayOutputStream\nimport java.net.HttpURLConnection\nimport java.net.URL\nimport java.util.UUID\nimport java.util.concurrent.Executors\n', 'network imports')

rep(
'''    private var scannerSelectedUrl = ""
    private var scannerBestScore = 0''',
'''    private var scannerSelectedUrl = ""
    private var scannerVisualSearchPreferred = true
    private var scannerBestScore = 0''',
'visual state'
)
rep(
'''            scannerSelectedUrl = ""
            scannerLastCandidateAt = 0L''',
'''            scannerSelectedUrl = ""
            scannerVisualSearchPreferred = true
            scannerLastCandidateAt = 0L''',
'visual state reset'
)

# Barcode/QR/URL has exact structured meaning, so keep normal structured search for it.
rep(
'''        setScannerCandidate(query, directUrl, 1_000, label)
    }''',
'''        scannerVisualSearchPreferred = false
        setScannerCandidate(query, directUrl, 1_000, label)
    }''',
'barcode visual override'
)

# Camera Cari is always enabled: a physical object does not need readable text or a known ML label.
rep(
'''        scannerSearchButton = compactButton("Cari") { openSearchResults(scannerSelectedQuery, scannerSelectedUrl) }.apply {
            isEnabled = scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank()
        }''',
'''        scannerSearchButton = compactButton("Cari") { performScannerSearch() }.apply {
            isEnabled = true
        }''',
'camera search action'
)

# The button immediately after Cari becomes embedded Back.
rep(
'''        header.addView(compactButton("📷") { launchScanner() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })''',
'''        header.addView(compactButton("←") { navigateEmbeddedSearchBack() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })''',
'web back button'
)

# If OCR and image-labeling do not know the object, that is exactly when pixel visual search is
# needed. Do not return with an empty result and disable the user.
rep(
'''        if (queryParts.isEmpty() && directUrl.isBlank()) return
        val query = directUrl.ifBlank { queryParts.joinToString(" ").take(180) }''',
'''        if (queryParts.isEmpty() && directUrl.isBlank()) {
            scannerVisualSearchPreferred = true
            handler.post {
                scannerStatusText?.text = "Objek fisik siap · tekan Cari untuk pencarian visual"
                scannerResultText?.text = "Cari bentuk/model objek yang sama"
                scannerSearchButton?.isEnabled = true
            }
            return
        }
        val query = directUrl.ifBlank { queryParts.joinToString(" ").take(180) }''',
'unknown object visual fallback'
)

needle = '''        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            ranked.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks dan objek fisik di area bidik terdeteksi"
            ranked.isNotEmpty() -> if (structured) "Data di area bidik terdeteksi" else "Tulisan utama di area bidik terdeteksi"
            else -> "Objek fisik di area bidik terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)'''
replacement = '''        val strongestText = ranked.firstOrNull()?.first?.text.orEmpty()
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
rep(needle, replacement, 'physical dominance')

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

    /** Uploads the actual cropped camera pixels for visual matching, not only OCR/labels. */
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
print('Applied true pixel visual search for any physical object plus Back button.')
