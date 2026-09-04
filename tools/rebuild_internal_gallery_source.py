from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
AI_CLIENT = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"

service = SERVICE.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global service
    if new in service:
        return
    if old not in service:
        raise RuntimeError(f"{label} marker not found")
    service = service.replace(old, new, 1)


replace_once(
    "    private var scannerGalleryPreviewBitmap: Bitmap? = null\n",
    "    private var scannerGalleryPreviewBitmap: Bitmap? = null\n    private var internalGalleryPanel: InternalGalleryPanel? = null\n",
    "internal gallery field",
)

replace_once(
    "        handler.removeCallbacksAndMessages(null)\n        stopEmbeddedScanner()\n",
    "        handler.removeCallbacksAndMessages(null)\n        internalGalleryPanel?.release()\n        internalGalleryPanel = null\n        stopEmbeddedScanner()\n",
    "service destroy cleanup",
)

replace_once(
    "        searchInput = null\n        clearScannerGalleryImage()\n        stopEmbeddedScanner()\n",
    "        searchInput = null\n        internalGalleryPanel?.release()\n        internalGalleryPanel = null\n        clearScannerGalleryImage()\n        stopEmbeddedScanner()\n",
    "close search cleanup",
)

replace_once(
    "    private fun showEmbeddedCameraPanel(resetCandidate: Boolean) {\n        stopEmbeddedScanner()\n",
    "    private fun showEmbeddedCameraPanel(resetCandidate: Boolean) {\n        internalGalleryPanel?.release()\n        internalGalleryPanel = null\n        stopEmbeddedScanner()\n",
    "camera panel cleanup",
)

replace_once(
    "    private fun showSearchWebPanel() {\n        destroySearchWebView()\n",
    "    private fun showSearchWebPanel() {\n        internalGalleryPanel?.release()\n        internalGalleryPanel = null\n        destroySearchWebView()\n",
    "web panel cleanup",
)

service = service.replace(
    'premiumIconButton(R.drawable.ic_gallery_modern, "Pilih foto dari galeri") { launchGalleryPicker() }',
    'premiumIconButton(R.drawable.ic_gallery_modern, "Buka galeri di keyboard") { showInternalGalleryPanel() }',
)
if "showInternalGalleryPanel()" not in service:
    raise RuntimeError("gallery button action was not replaced")

old_launch = '''    private fun launchGalleryPicker() {
        scannerStatusText?.text = "Pilih gambar dari galeri…"
        runCatching {
            startActivity(
                Intent(this, GalleryPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onFailure {
            scannerStatusText?.text = "Galeri tidak dapat dibuka."
            Toast.makeText(this, "Galeri tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
        }
    }
'''

new_launch = '''    private fun showInternalGalleryPanel() {
        if (!::searchSurfaceContent.isInitialized) return

        stopEmbeddedScanner(keepRequested = true)
        destroySearchWebView()
        searchComposeActive = false
        searchWebComposeActive = false
        searchInput = null
        scannerActive = true

        internalGalleryPanel?.release()
        val panel = InternalGalleryPanel(this)
        internalGalleryPanel = panel

        panel.show(
            container = searchSurfaceContent,
            onSelected = selected@ { uri ->
                if (internalGalleryPanel !== panel) return@selected
                panel.release()
                internalGalleryPanel = null
                scannerGalleryUri = uri
                scannerVisualSearchPreferred = true
                scannerSelectedQuery = ""
                scannerSelectedUrl = ""
                showEmbeddedCameraPanel(resetCandidate = false)
            },
            onCamera = camera@ {
                if (internalGalleryPanel !== panel) return@camera
                panel.release()
                internalGalleryPanel = null
                clearScannerGalleryImage()
                scannerActive = true
                showEmbeddedCameraPanel(resetCandidate = false)
                scannerPreviewView?.post { startEmbeddedScanner() }
            },
            onPermissionRequired = permission@ {
                if (internalGalleryPanel !== panel) return@permission
                runCatching {
                    startActivity(
                        Intent(this, GalleryPermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                }.onFailure {
                    scannerStatusText?.text = "Izin foto tidak dapat dibuka."
                    Toast.makeText(this, "Buka aplikasi AI Ads Keyboard lalu izinkan akses Foto & Video.", Toast.LENGTH_LONG).show()
                }
            }
        )
        showSearchSurface()
    }
'''

if old_launch in service:
    service = service.replace(old_launch, new_launch, 1)
elif new_launch not in service:
    raise RuntimeError("external gallery launcher marker not found")

# Remove the old external-picker result bridge entirely. Internal gallery returns Uri directly.
service, count = re.subn(
    r'\n        if \(prefs\.getBoolean\(GALLERY_READY_KEY, false\)\) \{.*?\n        \}\n\n        if \(!prefs\.getBoolean\(SCAN_READY_KEY, false\)\) return',
    '\n\n        if (!prefs.getBoolean(SCAN_READY_KEY, false)) return',
    service,
    count=1,
    flags=re.S,
)
if count == 0 and "GALLERY_READY_KEY" in service:
    raise RuntimeError("legacy gallery result block could not be removed")

service = service.replace('        private const val GALLERY_READY_KEY = "camera_gallery_ready"\n', '')
service = service.replace('        private const val GALLERY_URI_KEY = "camera_gallery_uri"\n', '')

# Image results explicitly request Bing SafeSearch off. This affects web image search only.
service = service.replace(
    'https://www.bing.com/images/search?q=${Uri.encode(query)}',
    'https://www.bing.com/images/search?q=${Uri.encode(query)}&adlt=off',
)
service = service.replace(
    'https://www.bing.com/images/search?q=${Uri.encode(clean)}',
    'https://www.bing.com/images/search?q=${Uri.encode(clean)}&adlt=off',
)

SERVICE.write_text(service)

ai = AI_CLIENT.read_text()
pattern = re.compile(
    r'    private fun visionInstruction\(localTextHint: String\): String = buildString \{.*?\n    \}\n\n    private fun requestOpenRouterVision',
    re.S,
)
replacement = '''    private fun visionInstruction(localTextHint: String): String = buildString {
        append(
            "Lihat gambar secara utuh dan kenali subjek yang paling penting berdasarkan bentuk visual nyata. " +
                "Subjek bisa berupa produk, benda, makanan, kendaraan, hewan, tanaman, tempat, logo, kemasan, pakaian, perangkat, atau objek lain. " +
                "Jangan memaksa semua gambar dianggap produk dan jangan terpaku hanya pada teks. " +
                "Jika ada beberapa objek, pilih yang paling dominan dan gunakan objek lain hanya sebagai konteks. " +
                "Gunakan bentuk, konstruksi, material, warna, pola, fungsi, logo, dan teks yang benar-benar terlihat untuk membuat query yang spesifik. " +
                "Sebut merek atau model hanya jika terbaca jelas; jika tidak, gunakan deskripsi visual yang paling akurat. " +
                "Jawab tepat satu baris query pencarian yang natural, 3 sampai 20 kata, tanpa penjelasan dan tanpa awalan 'query:'."
        )
        val hint = localTextHint.trim().replace(Regex("\\s+"), " ").take(180)
        if (hint.isNotBlank()) {
            append("\\nPetunjuk OCR/label lokal (gunakan hanya bila membantu dan sesuai dengan gambar): ")
            append(hint)
        }
    }

    private fun requestOpenRouterVision'''
ai, count = pattern.subn(replacement, ai, count=1)
if count != 1:
    raise RuntimeError("vision instruction block could not be rebuilt")
AI_CLIENT.write_text(ai)

print("Rebuilt internal gallery flow, removed external picker bridge, disabled Bing image SafeSearch, and relaxed AI Vision query instruction")
