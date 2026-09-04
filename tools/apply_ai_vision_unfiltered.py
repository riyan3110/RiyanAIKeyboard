from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
AI_PATH = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"
SERVICE_PATH = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"

ai = AI_PATH.read_text()
pattern = re.compile(
    r"    private fun visionInstruction\(localTextHint: String\): String = buildString \{.*?\n    \}\n\n    private fun requestOpenRouterVision",
    re.S,
)
replacement = '''    private fun visionInstruction(localTextHint: String): String = buildString {
        append(
            "Analisis gambar apa adanya berdasarkan seluruh isi visual yang benar-benar terlihat. " +
                "Jangan menambahkan filter kategori dari aplikasi dan jangan memaksa gambar menjadi jenis objek tertentu. " +
                "Kenali objek, produk, teks, logo, bahan, warna, bentuk, pola, fungsi, lingkungan, serta konteks visual lain yang relevan. " +
                "Jika ada beberapa objek penting, gunakan kombinasinya bila itu membuat identifikasi lebih tepat. " +
                "Jangan mengarang merek atau model yang tidak terlihat. " +
                "Hasil akhir harus berupa satu query pencarian gambar yang paling spesifik dan natural untuk menemukan gambar atau objek yang sama atau sangat mirip."
        )
        val hint = localTextHint.trim().replace(Regex("\\\\s+"), " ").take(220)
        if (hint.isNotBlank()) {
            append("\\nKonteks lokal opsional, abaikan bila tidak cocok dengan gambar: ")
            append(hint)
        }
    }

    private fun requestOpenRouterVision'''
ai2, count = pattern.subn(lambda _: replacement, ai, count=1)
if count != 1:
    raise RuntimeError("visionInstruction block not found")
AI_PATH.write_text(ai2)

service = SERVICE_PATH.read_text()
old_camera = '''            val crop = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForAiVision(crop, 896)
            val localHint = scannerMainProductText
                .replace(Regex("\\\\s+"), " ")
                .trim()
                .take(120)
'''
new_camera = '''            // Send the full camera frame to multimodal AI so app-side cropping cannot remove context.
            val prepared = scaleBitmapForAiVision(frame, 1024)
            // Do not bias AI with local OCR/product guesses; let the image itself drive recognition.
            val localHint = ""
'''
if old_camera not in service:
    raise RuntimeError("camera AI preparation block not found")
service = service.replace(old_camera, new_camera, 1)

old_cleanup = '''                if (prepared !== crop) prepared.recycle()
                if (crop !== frame) crop.recycle()
                frame.recycle()
'''
new_cleanup = '''                if (prepared !== frame) prepared.recycle()
                frame.recycle()
'''
if old_cleanup not in service:
    raise RuntimeError("camera cleanup block not found")
service = service.replace(old_cleanup, new_cleanup, 1)

service = service.replace(
    'AiClient.visionProduct(aiSettings(), encoded, "gambar dipilih dari galeri")',
    'AiClient.visionProduct(aiSettings(), encoded, "")',
    1,
)
# Increase the gallery image sent to AI Vision as well.
gallery_marker = 'val prepared = scaleBitmapForAiVision(frame, 896)'
if gallery_marker in service:
    service = service.replace(gallery_marker, 'val prepared = scaleBitmapForAiVision(frame, 1024)', 1)

SERVICE_PATH.write_text(service)
print("Removed app-side AI Vision category bias, full-frame crop, and local hint bias")
