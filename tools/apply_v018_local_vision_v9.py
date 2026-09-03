from pathlib import Path

service = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
gradle = Path('app/build.gradle.kts')
s = service.read_text(encoding='utf-8')
g = gradle.read_text(encoding='utf-8')


def replace_block(start_marker: str, end_marker: str, replacement: str, label: str):
    global s
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: block not found')
    s = s[:a] + replacement.rstrip() + '\n\n' + s[b:]


# Bundled ML Kit object detector. This adds physical-object localization/tracking; image labeling,
# OCR and barcode remain separate signals and are fused below.
dep = '    implementation("com.google.mlkit:object-detection:17.0.2")\n'
if dep not in g:
    marker = '    implementation("com.google.mlkit:text-recognition:16.0.1")\n'
    if marker not in g:
        raise RuntimeError('Gradle ML Kit insertion marker not found')
    g = g.replace(marker, marker + dep, 1)

imports = '''import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
'''
if 'import com.google.mlkit.vision.objects.ObjectDetection\n' not in s:
    marker = 'import com.google.mlkit.vision.text.latin.TextRecognizerOptions\n'
    if marker not in s:
        raise RuntimeError('ML Kit import marker not found')
    s = s.replace(marker, marker + imports, 1)

if 'private data class ScannerObjectSignal' not in s:
    marker = '    private data class ScannerTextLine(val text: String, val bounds: Rect?)\n'
    if marker not in s:
        raise RuntimeError('ScannerTextLine marker not found')
    s = s.replace(
        marker,
        marker + '''    private data class ScannerObjectSignal(\n        val bounds: Rect,\n        val labels: List<Pair<String, Float>>\n    )\n''',
        1,
    )

if 'private var scannerLocalShapeHint' not in s:
    marker = '    private var scannerBraveVisualQuery = ""\n'
    if marker not in s:
        raise RuntimeError('Brave visual state marker not found')
    s = s.replace(marker, marker + '    private var scannerLocalShapeHint = ""\n', 1)

if 'private val scannerObjectDetector by lazy' not in s:
    marker = '    private val scannerImageLabeler by lazy {\n'
    idx = s.find(marker)
    if idx < 0:
        raise RuntimeError('image labeler marker not found')
    # Insert detector immediately before image labeler.
    detector = '''    private val scannerObjectDetector by lazy {\n        ObjectDetection.getClient(\n            ObjectDetectorOptions.Builder()\n                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)\n                .enableMultipleObjects()\n                .enableClassification()\n                .build()\n        )\n    }\n'''
    s = s[:idx] + detector + s[idx:]

# More sensitive labels are safe now because object localization + multi-frame stability filters
# them before a query is published.
s = s.replace('.setConfidenceThreshold(0.68f)', '.setConfidenceThreshold(0.40f)')
s = s.replace('.setConfidenceThreshold(0.50f)', '.setConfidenceThreshold(0.40f)')

if 'scannerObjectDetector.close()' not in s:
    marker = '        scannerImageLabeler.close()\n'
    if marker not in s:
        raise RuntimeError('onDestroy image labeler marker not found')
    s = s.replace(marker, marker + '        scannerObjectDetector.close()\n', 1)

# Reset local physical-shape state with the rest of the camera candidate state.
if 'scannerLocalShapeHint = ""\n            scannerLastCandidateAt' not in s:
    marker = '            scannerBraveVisualQuery = ""\n            scannerLastCandidateAt = 0L'
    if marker in s:
        s = s.replace(marker, '            scannerBraveVisualQuery = ""\n            scannerLocalShapeHint = ""\n            scannerLastCandidateAt = 0L', 1)

analyze = r'''    private fun analyzeScannerTextAndObjects(input: InputImage, imageProxy: ImageProxy) {
        var remaining = 3
        var recognizedLines = emptyList<ScannerTextLine>()
        var imageLabels = emptyList<Pair<String, Float>>()
        var objects = emptyList<ScannerObjectSignal>()

        fun completeOne() {
            remaining -= 1
            if (remaining != 0) return
            val rotated = input.rotationDegrees % 180 != 0
            val frameWidth = if (rotated) input.height else input.width
            val frameHeight = if (rotated) input.width else input.height
            publishScannerVisualResult(recognizedLines, imageLabels, objects, frameWidth, frameHeight)
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
                imageLabels = task.result.orEmpty()
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim() to it.confidence }
                    .filter { it.first.isNotBlank() }
                    .distinctBy { it.first.lowercase() }
                    .take(8)
            }
            completeOne()
        }

        scannerObjectDetector.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val rotated = input.rotationDegrees % 180 != 0
                val frameWidth = if (rotated) input.height else input.width
                val frameHeight = if (rotated) input.width else input.height
                val target = scannerTargetRect(frameWidth, frameHeight)
                objects = task.result.orEmpty()
                    .filter { scannerLineInsideTarget(it.boundingBox, target) }
                    .map { detected ->
                        ScannerObjectSignal(
                            bounds = Rect(detected.boundingBox),
                            labels = detected.labels
                                .sortedByDescending { it.confidence }
                                .map { it.text.trim() to it.confidence }
                                .filter { it.first.isNotBlank() }
                                .take(4)
                        )
                    }
                    .sortedByDescending { scannerObjectRelevance(it.bounds, target) }
                    .take(3)
            }
            completeOne()
        }
    }'''
replace_block(
    '    private fun analyzeScannerTextAndObjects(input: InputImage, imageProxy: ImageProxy) {',
    '    private fun publishScannerBarcode(barcode: Barcode) {',
    analyze,
    'local vision analyzer',
)

publish = r'''    private fun publishScannerVisualResult(
        detectedLines: List<ScannerTextLine>,
        labels: List<Pair<String, Float>>,
        objects: List<ScannerObjectSignal>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val target = scannerTargetRect(frameWidth, frameHeight)
        val primaryObject = objects.maxByOrNull { scannerObjectRelevance(it.bounds, target) }
        val objectBounds = primaryObject?.bounds

        val lines = detectedLines.asSequence()
            .map { ScannerTextLine(it.text.trim().replace(Regex("\\s+"), " "), it.bounds) }
            .filter { it.text.length in 2..72 && it.text.any(Char::isLetterOrDigit) }
            .filter { scannerLineInsideTarget(it.bounds, target) }
            .filter { line ->
                objectBounds == null || line.bounds == null ||
                    scannerRectOverlapRatio(line.bounds, objectBounds) >= 0.28f ||
                    scannerIsStrongMainProductText(line, target, frameHeight)
            }
            .distinctBy { it.text.lowercase() }
            .toList()

        val directUrl = lines.asSequence()
            .mapNotNull { OCR_URL_REGEX.find(it.text)?.value }
            .map { if (it.startsWith("www.", true)) "https://$it" else it }
            .firstOrNull().orEmpty()

        val ranked = lines
            .map { line ->
                val objectBoost = if (objectBounds != null && line.bounds != null &&
                    scannerRectOverlapRatio(line.bounds, objectBounds) >= 0.35f) 18 else 0
                line to (scannerProminentLineScore(line, target, frameHeight) + objectBoost)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)

        scannerMainProductText = ranked.asSequence()
            .map { it.first }
            .filter { scannerIsStrongMainProductText(it, target, frameHeight) }
            .map { it.text.trim().replace(Regex("\\s+"), " ") }
            .filterNot { scannerLooksLikeSpecNoise(it) }
            .distinctBy { it.lowercase() }
            .take(2)
            .joinToString(" ")
            .take(96)

        val objectLabels = objects.asSequence()
            .flatMap { it.labels.asSequence() }
            .filter { it.second >= 0.38f }
            .map { scannerLabelForSearch(it.first) to it.second }
            .filter { it.first.isNotBlank() }
            .filterNot { it.first.lowercase() in SCANNER_IGNORED_LABELS }
            .toList()

        val broadLabels = labels.asSequence()
            .filter { it.second >= 0.42f }
            .map { scannerLabelForSearch(it.first) to it.second }
            .filter { it.first.isNotBlank() }
            .filterNot { it.first.lowercase() in SCANNER_IGNORED_LABELS }
            .toList()

        val usefulLabels = (objectLabels + broadLabels)
            .sortedByDescending { it.second }
            .distinctBy { it.first.lowercase() }
            .take(5)

        scannerLocalShapeHint = objectBounds?.let { scannerShapeHint(it) }.orEmpty()
        scannerBraveVisualQuery = usefulLabels.joinToString(" ") { it.first }.take(140)
        scannerVisualSearchPreferred = directUrl.isBlank()

        if (directUrl.isNotBlank()) {
            setScannerCandidate(directUrl, directUrl, 1_000, "Tautan di dalam objek terdeteksi")
            return
        }

        val previewParts = listOf(scannerMainProductText, scannerBraveVisualQuery, scannerLocalShapeHint)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val previewQuery = previewParts.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(200)

        if (previewQuery.isBlank()) {
            scannerSelectedQuery = "objek fisik"
            scannerSelectedUrl = ""
            handler.post {
                scannerStatusText?.text = "Vision lokal melihat objek · arahkan lebih dekat lalu tekan Cari"
                scannerResultText?.text = "Objek fisik terdeteksi"
                scannerSearchButton?.isEnabled = true
            }
            return
        }

        val score = 45 + usefulLabels.sumOf { (it.second * 20f).toInt() } +
            ranked.sumOf { it.second.coerceAtMost(80) } + (if (objectBounds != null) 35 else 0)
        setScannerCandidate(
            previewQuery,
            "",
            score,
            if (objectBounds != null) "Vision lokal mengenali objek utama" else "Vision lokal mengenali ciri objek"
        )
    }

    private fun scannerObjectRelevance(bounds: Rect, target: Rect): Float {
        val overlap = scannerRectOverlapRatio(bounds, target)
        val dx = kotlin.math.abs(bounds.centerX() - target.centerX()).toFloat() / target.width().coerceAtLeast(1)
        val dy = kotlin.math.abs(bounds.centerY() - target.centerY()).toFloat() / target.height().coerceAtLeast(1)
        val center = 1f - (dx + dy).coerceIn(0f, 1f)
        val size = (bounds.width().toFloat() * bounds.height().toFloat()) /
            (target.width().coerceAtLeast(1).toFloat() * target.height().coerceAtLeast(1).toFloat())
        return overlap * 2.2f + center + size.coerceIn(0f, 1.2f)
    }

    private fun scannerRectOverlapRatio(first: Rect, second: Rect): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val overlap = (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
        val area = first.width().coerceAtLeast(1).toLong() * first.height().coerceAtLeast(1).toLong()
        return (overlap.toDouble() / area.toDouble()).toFloat()
    }

    private fun scannerShapeHint(bounds: Rect): String {
        val ratio = bounds.width().toFloat() / bounds.height().coerceAtLeast(1).toFloat()
        return when {
            ratio >= 1.65f -> "bentuk horizontal memanjang"
            ratio <= 0.62f -> "bentuk vertikal memanjang"
            ratio in 0.86f..1.16f -> "bentuk kompak hampir persegi"
            ratio > 1.16f -> "bentuk melebar"
            else -> "bentuk meninggi"
        }
    }

    private fun scannerLooksLikeSpecNoise(value: String): Boolean {
        val compact = value.trim().replace(Regex("\\s+"), " ")
        if (compact.length <= 2) return true
        if (SCANNER_SMALL_SPEC_REGEX.matches(compact)) return true
        val letters = compact.count(Char::isLetter)
        val digits = compact.count(Char::isDigit)
        return compact.length <= 7 && digits > 0 && letters <= 3
    }'''
replace_block(
    '    private fun publishScannerVisualResult(',
    '    private fun scannerTextLineScore(line: String): Int {',
    publish,
    'local vision fusion',
)

perform = r'''    private fun performScannerSearch() {
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }

        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
            return
        }
        scannerSearchButton?.isEnabled = false
        scannerSearchButton?.text = "…"
        scannerStatusText?.text = "Vision lokal membaca bentuk, warna, objek, dan teks utama…"

        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar kamera belum siap · coba lagi"
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
                return@post
            }

            val crop = cropScannerVisualTarget(frame)
            val pixelTraits = scannerPixelTraits(crop)
            if (crop !== frame) crop.recycle()
            frame.recycle()

            val parts = listOf(
                scannerMainProductText,
                scannerBraveVisualQuery,
                scannerLocalShapeHint,
                pixelTraits
            )
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
                .filterNot { it.equals("objek fisik", ignoreCase = true) }
                .distinctBy { it.lowercase() }

            var query = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(240)
            if (query.isBlank()) query = "produk benda fisik ${pixelTraits.ifBlank { "bentuk serupa" }}"

            scannerSelectedQuery = query
            scannerStatusText?.text = "Membuka hasil gambar berdasarkan vision lokal…"
            searchQuery = query
            searchUrl = "https://search.brave.com/images?q=${Uri.encode(query)}"
            stopEmbeddedScanner()
            showSearchWebPanel()
            scannerSearchButton?.text = "Cari"
            scannerSearchButton?.isEnabled = true
        }
    }

    private fun scannerPixelTraits(bitmap: Bitmap): String {
        if (bitmap.width < 8 || bitmap.height < 8) return ""
        val left = (bitmap.width * 0.18f).toInt()
        val right = (bitmap.width * 0.82f).toInt().coerceAtLeast(left + 1)
        val top = (bitmap.height * 0.16f).toInt()
        val bottom = (bitmap.height * 0.84f).toInt().coerceAtLeast(top + 1)
        val step = maxOf(2, minOf(bitmap.width, bitmap.height) / 36)
        val counts = linkedMapOf<String, Int>()
        val hsv = FloatArray(3)
        var total = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val name = scannerColorName(hsv[0], hsv[1], hsv[2])
                counts[name] = (counts[name] ?: 0) + 1
                total += 1
            }
        }
        if (total == 0) return ""
        val colors = counts.entries
            .sortedByDescending { it.value }
            .filter { it.value.toFloat() / total.toFloat() >= 0.16f }
            .map { it.key }
            .distinct()
            .take(2)
        return when (colors.size) {
            0 -> ""
            1 -> "warna ${colors[0]}"
            else -> "warna ${colors[0]} ${colors[1]}"
        }
    }

    private fun scannerColorName(hue: Float, saturation: Float, value: Float): String {
        if (value < 0.16f) return "hitam"
        if (saturation < 0.10f && value > 0.86f) return "putih"
        if (saturation < 0.16f) return "abu-abu"
        if (value < 0.35f && hue in 8f..48f) return "cokelat"
        return when {
            hue < 12f || hue >= 345f -> "merah"
            hue < 38f -> "oranye"
            hue < 67f -> "kuning"
            hue < 165f -> "hijau"
            hue < 195f -> "cyan"
            hue < 255f -> "biru"
            hue < 292f -> "ungu"
            hue < 345f -> "merah muda"
            else -> "merah"
        }
    }'''
replace_block(
    '    private fun performScannerSearch() {',
    '    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {',
    perform,
    'local vision Brave search',
)

# The old Brave v8 helper is harmless but no longer needed as the main camera route.
# Keep navigation/back behavior untouched.

gradle.write_text(g, encoding='utf-8')
service.write_text(s, encoding='utf-8')
print('Applied v0.18 local vision v9: object detector + OCR + labels + shape/color fusion -> Brave Images.')
