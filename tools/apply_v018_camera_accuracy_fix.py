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


def between(start_marker, end_marker, new, label):
    global s
    if new.strip() in s:
        return
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: target not found')
    s = s[:a] + new.rstrip() + '\n\n' + s[b:]


rep('import android.graphics.Color\n', 'import android.graphics.Color\nimport android.graphics.Rect\n', 'Rect import')
rep(
    '    private data class ClipEntry(val text: String, val pinned: Boolean)\n',
    '    private data class ClipEntry(val text: String, val pinned: Boolean)\n    private data class ScannerTextLine(val text: String, val bounds: Rect?)\n',
    'scanner line data',
)
rep(
    '''    private var scannerLastCandidateAt = 0L
    private val scannerProcessingFrame = AtomicBoolean(false)''',
    '''    private var scannerLastCandidateAt = 0L
    private var scannerPendingQuery = ""
    private var scannerPendingHits = 0
    private var scannerPendingAt = 0L
    private val scannerProcessingFrame = AtomicBoolean(false)''',
    'scanner stability state',
)
rep('.setConfidenceThreshold(0.50f)', '.setConfidenceThreshold(0.68f)', 'label threshold')
rep(
    '''            scannerSelectedUrl = ""
            scannerLastCandidateAt = 0L
        }''',
    '''            scannerSelectedUrl = ""
            scannerLastCandidateAt = 0L
            scannerPendingQuery = ""
            scannerPendingHits = 0
            scannerPendingAt = 0L
        }''',
    'reset scanner stability',
)

rep(
    '''        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (barcode != null) {
                    publishScannerBarcode(barcode)
                    finishScannerFrame(imageProxy)
                } else {
                    analyzeScannerTextAndObjects(input, imageProxy)
                }
            }
            .addOnFailureListener { analyzeScannerTextAndObjects(input, imageProxy) }''',
    '''        barcodeScanner.process(input)
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
            .addOnFailureListener { analyzeScannerTextAndObjects(input, imageProxy) }''',
    'barcode ROI',
)

analyze = '''    private fun analyzeScannerTextAndObjects(input: InputImage, imageProxy: ImageProxy) {
        var remaining = 2
        var recognizedLines = emptyList<ScannerTextLine>()
        var labels = emptyList<Pair<String, Float>>()
        fun completeOne() {
            remaining -= 1
            if (remaining != 0) return
            val rotated = input.rotationDegrees % 180 != 0
            val frameWidth = if (rotated) input.height else input.width
            val frameHeight = if (rotated) input.width else input.height
            publishScannerVisualResult(recognizedLines, labels, frameWidth, frameHeight)
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
                labels = task.result.orEmpty()
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim() to it.confidence }
                    .filter { it.first.isNotBlank() }
                    .distinctBy { it.first.lowercase() }
                    .take(5)
            }
            completeOne()
        }
    }'''
between('    private fun analyzeScannerTextAndObjects(input: InputImage, imageProxy: ImageProxy) {', '    private fun publishScannerBarcode(barcode: Barcode) {', analyze, 'visual analysis')

publish = '''    private fun publishScannerVisualResult(
        detectedLines: List<ScannerTextLine>,
        labels: List<Pair<String, Float>>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val target = scannerTargetRect(frameWidth, frameHeight)
        val lines = detectedLines.asSequence()
            .map { ScannerTextLine(it.text.trim().replace(Regex("\\s+"), " "), it.bounds) }
            .filter { it.text.length in 2..72 && it.text.any(Char::isLetterOrDigit) }
            .filter { scannerLineInsideTarget(it.bounds, target) }
            .distinctBy { it.text.lowercase() }
            .toList()
        val directUrl = lines.asSequence()
            .mapNotNull { OCR_URL_REGEX.find(it.text)?.value }
            .map { if (it.startsWith("www.", true)) "https://$it" else it }
            .firstOrNull().orEmpty()
        val ranked = lines
            .map { it to scannerProminentLineScore(it, target, frameHeight) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
        val usefulLabels = labels
            .filter { it.second >= 0.72f }
            .filterNot { it.first.trim().lowercase() in SCANNER_IGNORED_LABELS }
            .map { scannerLabelForSearch(it.first) to it.second }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first.lowercase() }
            .take(2)
        val textParts = ranked.map { it.first.text }.take(2)
        val labelParts = when {
            textParts.size >= 2 -> emptyList()
            textParts.size == 1 -> usefulLabels.filter { it.second >= 0.85f }.map { it.first }.take(1)
            else -> usefulLabels.map { it.first }.take(2)
        }
        val queryParts = (textParts + labelParts).distinctBy { it.lowercase() }
        if (queryParts.isEmpty() && directUrl.isBlank()) return
        val query = directUrl.ifBlank { queryParts.joinToString(" ").take(180) }
        val structured = lines.any {
            OCR_EMAIL_REGEX.containsMatchIn(it.text) || OCR_PHONE_REGEX.containsMatchIn(it.text) ||
                OCR_PRICE_REGEX.containsMatchIn(it.text) || OCR_DATE_REGEX.containsMatchIn(it.text)
        }
        val score = ranked.sumOf { it.second } + (usefulLabels.firstOrNull()?.second?.times(24)?.toInt() ?: 0) +
            (if (structured) 8 else 0) + (if (directUrl.isNotBlank()) 400 else 0)
        val source = when {
            directUrl.isNotBlank() -> "Tautan di dalam area bidik terdeteksi"
            ranked.isNotEmpty() && usefulLabels.isNotEmpty() -> "Teks dan objek fisik di area bidik terdeteksi"
            ranked.isNotEmpty() -> if (structured) "Data di area bidik terdeteksi" else "Tulisan utama di area bidik terdeteksi"
            else -> "Objek fisik di area bidik terdeteksi"
        }
        setScannerCandidate(query, directUrl, score, source)
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
        if (OCR_PRICE_REGEX.containsMatchIn(line.text) && !line.text.any { it.isLetter() && it.uppercaseChar() !in "RPIDRUSD" }) score -= 18
        val compact = line.text.filterNot(Char::isWhitespace)
        if (compact.length <= 7 && compact.any(Char::isLetter) && compact.any(Char::isDigit) && !OCR_PRICE_REGEX.containsMatchIn(line.text)) score -= 10
        return score
    }'''
between('    private fun publishScannerVisualResult(', '    private fun scannerTextLineScore(line: String): Int {', publish, 'ROI ranking')

candidate = '''    private fun setScannerCandidate(query: String, url: String, score: Int, source: String) {
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
        .toSet()'''
between('    private fun setScannerCandidate(query: String, url: String, score: Int, source: String) {', '    private fun finishScannerFrame(imageProxy: ImageProxy) {', candidate, 'stable candidate')

rep(
    '''        private const val SCANNER_FRAME_INTERVAL_MS = 260L
        private const val SCANNER_CANDIDATE_HOLD_MS = 1_250L
''',
    '''        private const val SCANNER_FRAME_INTERVAL_MS = 240L
        private const val SCANNER_CANDIDATE_HOLD_MS = 1_250L
        private const val SCANNER_STABILITY_WINDOW_MS = 1_400L
        private const val SCANNER_REQUIRED_STABLE_HITS = 2
''',
    'scanner constants',
)
if 'private val SCANNER_IGNORED_LABELS' not in s:
    marker = '        private val SCANNER_LABEL_TRANSLATIONS = mapOf(\n'
    if marker not in s:
        raise RuntimeError('label filter insertion target not found')
    s = s.replace(marker, '''        private val SCANNER_IGNORED_LABELS = setOf(
            "pattern", "font", "text", "line", "rectangle", "design", "graphics", "screenshot",
            "display", "screen", "material", "event", "art", "photography", "brand"
        )

''' + marker, 1)

p.write_text(s, encoding='utf-8')
print('Applied v0.18 camera target-area and stability accuracy fix.')
