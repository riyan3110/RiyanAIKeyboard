from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')


def rep(old, new, label):
    global s
    if new in s:
        return
    if old not in s:
        raise RuntimeError(f'{label}: target not found')
    s = s.replace(old, new, 1)


def replace_block(start_marker, end_marker, replacement, label):
    global s
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: block not found')
    s = s[:a] + replacement.rstrip() + '\n\n' + s[b:]


# Keep a separate, deliberately strict product-text signal. OCR noise must never become the
# identity of a physical product (for example 50MP -> SOMP on a phone camera module).
rep(
    '    private var scannerVisualSearchPreferred = true\n',
    '    private var scannerVisualSearchPreferred = true\n    private var scannerMainProductText = ""\n',
    'main product text state',
)
rep(
    '''            scannerVisualSearchPreferred = true
            scannerLastCandidateAt = 0L''',
    '''            scannerVisualSearchPreferred = true
            scannerMainProductText = ""
            scannerLastCandidateAt = 0L''',
    'main product text reset',
)

old_source = '''        val strongestText = ranked.firstOrNull()?.first?.text.orEmpty()
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
new_source = '''        scannerMainProductText = ranked.asSequence()
            .map { it.first }
            .filter { scannerIsStrongMainProductText(it, target, frameHeight) }
            .map { it.text.trim().replace(Regex("\\s+"), " ") }
            .distinctBy { it.lowercase() }
            .take(2)
            .joinToString(" ")
            .take(96)

        // A real image match is the primary route for every non-structured physical object.
        // Strong, prominent brand/model text is only a refinement signal on top of the image.
        scannerVisualSearchPreferred = directUrl.isBlank()
        val candidateQuery = if (scannerVisualSearchPreferred) {
            scannerMainProductText.ifBlank { "Pencarian visual objek" }
        } else {
            query
        }
        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            scannerMainProductText.isNotBlank() -> "Bentuk objek + teks utama produk siap dicocokkan"
            else -> "Bentuk objek siap dicocokkan secara visual"
        }
        setScannerCandidate(candidateQuery, directUrl, score.coerceAtLeast(36), source)'''
rep(old_source, new_source, 'visual source routing')

# If there is no OCR/label at all, keep visual search active and clear stale OCR from earlier frames.
old_empty = '''        if (queryParts.isEmpty() && directUrl.isBlank()) {
            scannerVisualSearchPreferred = true
            handler.post {
                scannerStatusText?.text = "Objek fisik siap · tekan Cari untuk pencarian visual"
                scannerResultText?.text = "Cari bentuk/model objek yang sama"
                scannerSearchButton?.isEnabled = true
            }
            return
        }'''
new_empty = '''        if (queryParts.isEmpty() && directUrl.isBlank()) {
            scannerVisualSearchPreferred = true
            scannerMainProductText = ""
            scannerSelectedQuery = "Pencarian visual objek"
            scannerSelectedUrl = ""
            handler.post {
                scannerStatusText?.text = "Bentuk objek siap dicocokkan secara visual"
                scannerResultText?.text = "Pencarian visual objek"
                scannerSearchButton?.isEnabled = true
            }
            return
        }'''
rep(old_empty, new_empty, 'empty visual route')

helpers = r'''    private fun scannerIsStrongMainProductText(line: ScannerTextLine, target: Rect, frameHeight: Int): Boolean {
        val text = line.text.trim().replace(Regex("\s+"), " ")
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

    private fun refineLensResultUrl(rawUrl: String, mainText: String): String {
        return runCatching {
            val uri = Uri.parse(rawUrl)
            val builder = uri.buildUpon()
            if (uri.getQueryParameter("hl").isNullOrBlank()) builder.appendQueryParameter("hl", "id")
            if (uri.host?.endsWith("google.com", ignoreCase = true) == true &&
                uri.path?.startsWith("/search") == true && uri.getQueryParameter("udm").isNullOrBlank()) {
                builder.appendQueryParameter("udm", "26")
            }
            if (mainText.isNotBlank() && uri.getQueryParameter("q").isNullOrBlank()) {
                builder.appendQueryParameter("q", mainText)
            }
            builder.build().toString()
        }.getOrDefault(rawUrl)
    }'''

if '    private fun scannerIsStrongMainProductText(' not in s:
    marker = '    private fun performScannerSearch() {'
    if marker not in s:
        raise RuntimeError('strong text helper marker missing')
    s = s.replace(marker, helpers + '\n\n' + marker, 1)

perform = r'''    private fun performScannerSearch() {
        // Barcode/QR/direct URL stays exact. Every ordinary physical object uses the actual pixels.
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }
        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
            return
        }
        scannerStatusText?.text = if (scannerMainProductText.isBlank()) {
            "Mengambil bentuk objek untuk dicocokkan…"
        } else {
            "Mencocokkan bentuk + teks utama: $scannerMainProductText"
        }
        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar belum siap · arahkan objek lalu coba lagi"
                return@post
            }
            val crop = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForLens(crop)
            val out = ByteArrayOutputStream()
            prepared.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val jpeg = out.toByteArray()
            val width = prepared.width
            val height = prepared.height
            if (prepared !== crop) prepared.recycle()
            if (crop !== frame) crop.recycle()
            frame.recycle()
            scannerStatusText?.text = "Mencari produk dengan bentuk/model paling mirip…"
            thread {
                val rawResultUrl = uploadToLensVisualSearch(jpeg, width, height)
                val resultUrl = rawResultUrl?.let { refineLensResultUrl(it, scannerMainProductText) }
                handler.post {
                    if (resultUrl.isNullOrBlank()) {
                        scannerStatusText?.text = "Pencarian visual belum mendapat hasil · tekan Cari untuk mencoba ulang"
                    } else {
                        searchQuery = scannerMainProductText.ifBlank { "Pencarian visual objek" }
                        searchUrl = resultUrl
                        stopEmbeddedScanner()
                        showSearchWebPanel()
                    }
                }
            }
        }
    }'''
replace_block('    private fun performScannerSearch() {', '    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {', perform, 'scanner search')

upload = r'''    /**
     * Current Chromium-compatible Lens upload. The image is the primary search input; prominent
     * product text is added only after the image session is created.
     */
    private fun uploadToLensVisualSearch(jpeg: ByteArray, width: Int, height: Int): String? = runCatching {
        val boundary = "----AIAdsKeyboard${UUID.randomUUID().toString().replace("-", "")}" 
        val endpoint = URL(
            "https://lens.google.com/v3/upload" +
                "?ep=cntpubb&hl=id&st=${System.currentTimeMillis()}&re=df&s=4&vph=$height&vpw=$width"
        )
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.6")
            setRequestProperty("Referer", "https://www.google.com/")
            setRequestProperty("Origin", "https://www.google.com")
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
            text("\r\n--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"sbisrc\"\r\n\r\n")
            text("Google Chrome")
            text("\r\n--$boundary--\r\n")
        }
        val code = connection.responseCode
        val finalUrl = connection.url?.toString().orEmpty()
        runCatching { connection.inputStream?.close() }
        connection.disconnect()
        val looksLikeLensResult = finalUrl.contains("udm=26", ignoreCase = true) ||
            finalUrl.contains("lens.google.com/search", ignoreCase = true) ||
            finalUrl.contains("google.com/search", ignoreCase = true)
        finalUrl.takeIf { code in 200..399 && looksLikeLensResult }
    }.getOrNull()'''
replace_block('    /** Upload actual cropped camera pixels for web visual matching, not only an OCR/ML label. */', '    private fun navigateEmbeddedSearchBack() {', upload, 'lens upload')

# Companion regex used only to reject isolated tiny hardware/spec text, never full model names.
if 'private val SCANNER_SMALL_SPEC_REGEX' not in s:
    marker = '        private val OCR_DATE_REGEX = Regex('
    idx = s.find(marker)
    if idx < 0:
        raise RuntimeError('OCR date regex marker missing')
    line_end = s.find('\n', idx)
    insert = '''\n        private val SCANNER_SMALL_SPEC_REGEX = Regex("""(?i)^\\s*(?:\\d{1,5}\\s*(?:mp|mah|gb|tb|hz|khz|mhz|ghz|w|kw|v|a|cm|mm|ml|l|g|kg|pcs?)|[so5][o0]\\s*mp)\\s*$""")'''
    s = s[:line_end] + insert + s[line_end:]

p.write_text(s, encoding='utf-8')
print('Applied v0.18 visual product matching v3: image-first + strong main product text.')
