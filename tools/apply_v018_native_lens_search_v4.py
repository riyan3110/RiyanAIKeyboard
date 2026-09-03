from pathlib import Path

service_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
manifest_path = Path('app/src/main/AndroidManifest.xml')
paths_xml = Path('app/src/main/res/xml/lens_file_paths.xml')

s = service_path.read_text(encoding='utf-8')

# Imports required by the stable native Lens handoff.
imports = {
    'import android.content.ClipData\n': 'import android.content.ClipboardManager\n',
    'import java.io.File\n': 'import java.io.ByteArrayOutputStream\n',
    'import java.io.FileOutputStream\n': 'import java.io.File\n',
    'import androidx.core.content.FileProvider\n': 'import androidx.core.content.ContextCompat\n',
}
for new_import, marker in imports.items():
    if new_import not in s:
        if marker not in s:
            raise RuntimeError(f'import marker missing: {marker.strip()}')
        s = s.replace(marker, marker + new_import, 1)

# v3 created a WebView-based Lens upload. Replace only the search action with a native image handoff.
start = s.find('    private fun performScannerSearch() {')
end = s.find('    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {', start)
if start < 0 or end < 0:
    raise RuntimeError('performScannerSearch block not found after v3 patch')

replacement = r'''    private fun performScannerSearch() {
        // Structured results stay exact. Everything else is searched from the real camera pixels.
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }

        val preview = scannerPreviewView ?: run {
            scannerStatusText?.text = "Kamera belum siap · coba lagi"
            return
        }
        scannerStatusText?.text = "Mengambil objek asli dari area bidik…"
        preview.post {
            val frame = runCatching { preview.bitmap }.getOrNull()
            if (frame == null || frame.width < 80 || frame.height < 80) {
                scannerStatusText?.text = "Gambar belum siap · arahkan objek lalu coba lagi"
                return@post
            }

            val crop = cropScannerVisualTarget(frame)
            val prepared = scaleBitmapForLens(crop, 1440)
            scannerStatusText?.text = "Mencari bentuk fisik dan teks utama dari gambar asli…"

            val launched = runCatching { launchNativeLensSearch(prepared) }.getOrDefault(false)

            if (prepared !== crop) prepared.recycle()
            if (crop !== frame) crop.recycle()
            frame.recycle()

            if (!launched) {
                // Last-resort fallback only when Lens/Google app is genuinely unavailable.
                // Never send placeholder text such as "Pencarian visual objek" to Google.
                val fallback = scannerMainProductText.trim()
                    .takeIf { it.isNotBlank() && !it.equals("Pencarian visual objek", true) }
                    ?: scannerSelectedQuery.trim().takeIf {
                        it.isNotBlank() && !it.equals("Pencarian visual objek", true)
                    }.orEmpty()
                if (fallback.isNotBlank()) {
                    scannerStatusText?.text = "Lens tidak tersedia · memakai teks utama produk"
                    openSearchResults("$fallback produk model bentuk sama", "")
                } else {
                    scannerStatusText?.text = "Google Lens tidak tersedia di perangkat ini"
                    Toast.makeText(this, "Google Lens/Google app diperlukan untuk pencocokan objek dari gambar.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Send the actual cropped camera image to the native Google Lens engine.
     * This avoids fragile multipart uploads inside WebView and lets Lens itself combine
     * physical shape, texture, colour, logo, brand/model text, and other visual cues.
     */
    private fun launchNativeLensSearch(bitmap: Bitmap): Boolean {
        val lensDir = File(cacheDir, "lens").apply { mkdirs() }
        lensDir.listFiles()?.forEach { old ->
            if (System.currentTimeMillis() - old.lastModified() > 24L * 60L * 60L * 1000L) {
                runCatching { old.delete() }
            }
        }

        val imageFile = File(lensDir, "object_${System.currentTimeMillis()}.jpg")
        FileOutputStream(imageFile).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) { "JPEG encode failed" }
        }
        val imageUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        val timestamp = SystemClock.elapsedRealtimeNanos().toString()

        fun lensUri(contract: String): Uri = Uri.parse(contract).buildUpon()
            .appendQueryParameter("LensBitmapUriKey", imageUri.toString())
            .appendQueryParameter("AccountNameUriKey", "")
            .appendQueryParameter("IncognitoUriKey", "false")
            .appendQueryParameter("ActivityLaunchTimestampNanos", timestamp)
            .build()

        val googlePackage = "com.google.android.googlequicksearchbox"
        val contracts = listOf("googleapp://lens", "google://lens")
        for (contract in contracts) {
            val intent = Intent(Intent.ACTION_VIEW, lensUri(contract)).apply {
                setPackage(googlePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("AI Ads Keyboard object", imageUri)
            }
            grantUriPermission(googlePackage, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(intent)
                return true
            }
        }

        // Some devices expose Lens primarily as a share target rather than the contract URI.
        val sharePackages = listOf(googlePackage, "com.google.ar.lens")
        for (targetPackage in sharePackages) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                setPackage(targetPackage)
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = ClipData.newRawUri("AI Ads Keyboard object", imageUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            grantUriPermission(targetPackage, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (packageManager.resolveActivity(shareIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(shareIntent)
                return true
            }
        }
        return false
    }

'''
s = s[:start] + replacement + s[end:]
service_path.write_text(s, encoding='utf-8')

# Make the temporary camera crop readable only through FileProvider and make Lens packages visible
# to packageManager.resolveActivity on Android 11+.
m = manifest_path.read_text(encoding='utf-8')
if '<package android:name="com.google.android.googlequicksearchbox" />' not in m:
    m = m.replace(
        '    <queries>\n',
        '    <queries>\n        <package android:name="com.google.android.googlequicksearchbox" />\n        <package android:name="com.google.ar.lens" />\n',
        1,
    )

provider = '''        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/lens_file_paths" />
        </provider>
'''
if 'android:resource="@xml/lens_file_paths"' not in m:
    m = m.replace('    </application>\n', provider + '    </application>\n', 1)
manifest_path.write_text(m, encoding='utf-8')

paths_xml.parent.mkdir(parents=True, exist_ok=True)
paths_xml.write_text('''<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="lens_camera" path="lens/" />
</paths>
''', encoding='utf-8')

print('Applied v0.18 native Lens search: real image handoff, no WebView upload.')
