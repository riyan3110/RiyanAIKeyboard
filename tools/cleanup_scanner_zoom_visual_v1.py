from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
source = SERVICE.read_text()

# The generator was intentionally rerunnable, but the nullable-JPEG follow-up changed one
# generated line, so one rerun could leave a second copy of the visual-upload helpers.
# Keep exactly the first helper pair.
helper = "    private fun uploadBitmapForVisualSearch(source: Bitmap, queryHint: String): String? {"
refine = "    private fun refineLensResultUrl(rawUrl: String, mainText: String): String {"
positions = []
start = 0
while True:
    idx = source.find(helper, start)
    if idx < 0:
        break
    positions.append(idx)
    start = idx + len(helper)
if len(positions) > 1:
    refine_idx = source.find(refine, positions[1])
    if refine_idx < 0:
        raise RuntimeError("refineLensResultUrl anchor not found after duplicate helper")
    source = source[:positions[1]] + source[refine_idx:]
elif len(positions) != 1:
    raise RuntimeError(f"expected one or two visual-upload helper copies, got {len(positions)}")

source = source.replace(
    "if (jpeg.isNullOrEmpty()) return null",
    "if (jpeg == null || jpeg.isEmpty()) return null",
)

old_selection = '''                panel.release()
                internalGalleryPanel = null
                scannerGalleryUri = uri
                scannerVisualSearchPreferred = true
'''
new_selection = '''                panel.release()
                internalGalleryPanel = null
                scannerGalleryZoom = 1f
                scannerGalleryFocusX = 0.5f
                scannerGalleryFocusY = 0.5f
                scannerGalleryUri = uri
                scannerVisualSearchPreferred = true
'''
if old_selection in source:
    source = source.replace(old_selection, new_selection, 1)
elif new_selection not in source:
    raise RuntimeError("gallery zoom-reset selection anchor not found")

# Sanity checks before writing the final source.
if source.count(helper) != 1:
    raise RuntimeError("visual-upload helper duplication remains")
if source.count("    private fun uploadVisualSearchMultipart(") != 1:
    raise RuntimeError("multipart helper duplication remains")
if "jpeg.isNullOrEmpty()" in source:
    raise RuntimeError("nullable JPEG compile bug remains")
if source.count("private fun installScannerCameraGestures(") != 1:
    raise RuntimeError("camera gesture helper count is invalid")
if source.count("private fun installScannerGalleryGestures(") != 1:
    raise RuntimeError("gallery gesture helper count is invalid")

SERVICE.write_text(source)
print("Scanner source cleaned: one visual uploader, valid JPEG guard, per-photo zoom reset.")
