from pathlib import Path
import re

SERVICE = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
AI = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, got {count}')
    return text.replace(old, new, 1)

service = SERVICE.read_text()

service = replace_once(
    service,
'''            // Send the full camera frame to multimodal AI so app-side cropping cannot remove context.
            val prepared = scaleBitmapForAiVision(frame, 1024)
            // Do not bias AI with local OCR/product guesses; let the image itself drive recognition.
            val localHint = ""
''',
'''            // Search exactly what the user is aiming at. PreviewView already reflects CameraX zoom,
            // then we crop to the scanner target so background outside the aimed area cannot dominate.
            val targetFrame = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForAiVision(targetFrame, 1536)
            val localHint = buildString {
                if (scannerCameraZoomRatio > 1.05f) {
                    append("Pengguna sedang memperbesar area target sekitar %.1fx. ".format(scannerCameraZoomRatio))
                }
                append("Prioritaskan subjek utama pada area ini dan detail kecil pembeda yang benar-benar terlihat; abaikan latar yang tidak relevan.")
                scannerMainProductText.trim().takeIf { it.isNotBlank() }?.let {
                    append(" Teks lokal yang mungkin relevan: ")
                    append(it.take(120))
                }
            }
''',
    'camera target preparation'
)

service = replace_once(
    service,
'''                    check(prepared.compress(Bitmap.CompressFormat.JPEG, 80, output))
''',
'''                    check(prepared.compress(Bitmap.CompressFormat.JPEG, 92, output))
''',
    'camera jpeg quality'
)

service = replace_once(
    service,
'''                if (prepared !== frame) prepared.recycle()
                frame.recycle()
''',
'''                if (prepared !== targetFrame && !prepared.isRecycled) prepared.recycle()
                if (targetFrame !== frame && !targetFrame.isRecycled) targetFrame.recycle()
                if (!frame.isRecycled) frame.recycle()
''',
    'camera recycle block'
)

service = replace_once(
    service,
'''            val prepared = scaleBitmapForAiVision(frame, 1024)
''',
'''            val prepared = scaleBitmapForAiVision(frame, 1536)
''',
    'gallery detail dimension'
)

service = replace_once(
    service,
'''                check(prepared.compress(Bitmap.CompressFormat.JPEG, 82, output))
''',
'''                check(prepared.compress(Bitmap.CompressFormat.JPEG, 92, output))
''',
    'gallery jpeg quality'
)

old_crop = '''    private fun cropGalleryForCurrentZoom(source: Bitmap): Bitmap {
        val zoom = scannerGalleryZoom.coerceIn(1f, 6f)
        if (zoom <= 1.02f) return source
        val cropWidth = (source.width / zoom).toInt().coerceIn(1, source.width)
        val cropHeight = (source.height / zoom).toInt().coerceIn(1, source.height)
        val centerX = (source.width * scannerGalleryFocusX.coerceIn(0f, 1f)).toInt()
        val centerY = (source.height * scannerGalleryFocusY.coerceIn(0f, 1f)).toInt()
        val left = (centerX - cropWidth / 2).coerceIn(0, source.width - cropWidth)
        val top = (centerY - cropHeight / 2).coerceIn(0, source.height - cropHeight)
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }
'''
new_crop = '''    private fun cropGalleryForCurrentZoom(source: Bitmap): Bitmap {
        val view = scannerGalleryImageView
        val viewWidth = view?.width ?: 0
        val viewHeight = view?.height ?: 0
        val zoom = scannerGalleryZoom.coerceIn(1f, 6f)

        // ImageView uses CENTER_CROP, so direct normalized source coordinates are wrong whenever
        // image and panel aspect ratios differ. Convert the exact visible view rectangle back into
        // bitmap coordinates, then apply the user's pinch zoom around the same pivot.
        if (viewWidth <= 1 || viewHeight <= 1) {
            if (zoom <= 1.02f) return source
            val cropWidth = (source.width / zoom).toInt().coerceIn(1, source.width)
            val cropHeight = (source.height / zoom).toInt().coerceIn(1, source.height)
            val centerX = (source.width * scannerGalleryFocusX.coerceIn(0f, 1f)).toInt()
            val centerY = (source.height * scannerGalleryFocusY.coerceIn(0f, 1f)).toInt()
            val left = (centerX - cropWidth / 2).coerceIn(0, source.width - cropWidth)
            val top = (centerY - cropHeight / 2).coerceIn(0, source.height - cropHeight)
            return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        }

        val baseScale = maxOf(
            viewWidth.toFloat() / source.width.coerceAtLeast(1).toFloat(),
            viewHeight.toFloat() / source.height.coerceAtLeast(1).toFloat()
        )
        val renderedWidth = source.width * baseScale
        val renderedHeight = source.height * baseScale
        val offsetX = (renderedWidth - viewWidth) / 2f
        val offsetY = (renderedHeight - viewHeight) / 2f
        val pivotX = scannerGalleryFocusX.coerceIn(0f, 1f) * viewWidth
        val pivotY = scannerGalleryFocusY.coerceIn(0f, 1f) * viewHeight

        val leftView = pivotX - (pivotX / zoom)
        val topView = pivotY - (pivotY / zoom)
        val rightView = pivotX + ((viewWidth - pivotX) / zoom)
        val bottomView = pivotY + ((viewHeight - pivotY) / zoom)

        val left = ((leftView + offsetX) / baseScale).toInt().coerceIn(0, source.width - 1)
        val top = ((topView + offsetY) / baseScale).toInt().coerceIn(0, source.height - 1)
        val right = ((rightView + offsetX) / baseScale).toInt().coerceIn(left + 1, source.width)
        val bottom = ((bottomView + offsetY) / baseScale).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }
'''
service = replace_once(service, old_crop, new_crop, 'gallery visible crop mapping')

refine_pattern = re.compile(
    r'''    private fun refineLensResultUrl\(rawUrl: String, mainText: String\): String \{\n.*?\n    \}\n\n    private fun performScannerSearch\(\) \{''',
    re.S,
)
refine_replacement = '''    private fun refineLensResultUrl(rawUrl: String, mainText: String): String {
        // A Lens upload result already contains the visual-search token that identifies the image.
        // Appending q/udm turns that URL into a normal text/image Google search and discards the
        // visual-search experience. Preserve the original result and only localize the language.
        return runCatching {
            val uri = Uri.parse(rawUrl)
            if (!uri.getQueryParameter("hl").isNullOrBlank()) return@runCatching rawUrl
            uri.buildUpon().appendQueryParameter("hl", "id").build().toString()
        }.getOrDefault(rawUrl)
    }

    private fun performScannerSearch() {'''
service, count = refine_pattern.subn(refine_replacement, service, count=1)
if count != 1:
    raise SystemExit(f'visual result URL preservation: expected one match, got {count}')

SERVICE.write_text(service)

ai = AI.read_text()
vision_pattern = re.compile(
    r'''    private fun visionInstruction\(localTextHint: String\): String = buildString \{\n.*?\n    \}\n\n    private fun requestOpenRouterVision''',
    re.S,
)
vision_replacement = '''    private fun visionInstruction(localTextHint: String): String = buildString {
        append(
            "Gambar yang diterima adalah area target yang sedang dilihat pengguna setelah crop/zoom. " +
                "Anggap area ini sebagai subjek pencarian utama dan jangan membiarkan latar di luar subjek mendominasi hasil. " +
                "Analisis sedetail mungkin ciri visual yang benar-benar terlihat: bentuk, warna, pola, ilustrasi, logo, tulisan kecil, simbol, bahan, tekstur, bagian produk, serta detail pembeda lain. " +
                "Untuk teks atau logo, salin hanya yang benar-benar terbaca; jangan mengarang huruf, merek, model, atau identitas. " +
                "Jika ada beberapa objek, prioritaskan objek yang paling besar/terpusat atau yang tampak sengaja diperbesar pengguna. " +
                "Jika ada manusia, analisis hanya ciri visual non-sensitif yang benar-benar tampak untuk pencarian kemiripan, tanpa mencoba menentukan identitas orang. " +
                "Hasil akhir harus berupa satu query pencarian visual yang spesifik, ringkas, dan memuat detail pembeda utama dari area zoom tersebut, bukan deskripsi umum seluruh latar."
        )
        val hint = localTextHint.trim().replace(Regex("\\s+"), " ").take(260)
        if (hint.isNotBlank()) {
            append("\\nKonteks lokal opsional; gunakan hanya jika cocok dengan gambar: ")
            append(hint)
        }
    }

    private fun requestOpenRouterVision'''
ai, count = vision_pattern.subn(vision_replacement, ai, count=1)
if count != 1:
    raise SystemExit(f'vision instruction replacement: expected one match, got {count}')
AI.write_text(ai)

print('scanner detail + Web Big visual URL fix applied')
