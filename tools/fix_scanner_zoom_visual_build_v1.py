from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
source = SERVICE.read_text()

old_jpeg = "if (jpeg.isNullOrEmpty()) return null"
new_jpeg = "if (jpeg == null || jpeg.isEmpty()) return null"
if old_jpeg in source:
    source = source.replace(old_jpeg, new_jpeg, 1)
elif new_jpeg not in source:
    raise RuntimeError("nullable JPEG guard not found")

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
    raise RuntimeError("gallery selection reset anchor not found")

SERVICE.write_text(source)
print("Fixed nullable JPEG compile guard and reset zoom for each newly selected gallery image.")
