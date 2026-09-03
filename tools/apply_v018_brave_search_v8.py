from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')


def replace_block(start_marker, end_marker, replacement, label):
    global s
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: block not found')
    s = s[:a] + replacement.rstrip() + '\n\n' + s[b:]


# Keep the best OCR + ML Kit object description separately. The previous visual-search patch
# intentionally replaced the visible candidate with a generic visual label; Brave needs the
# actual detected words/object labels because Brave Search does not accept reverse-image uploads.
state_marker = '    private var scannerMainProductText = ""\n'
if 'private var scannerBraveVisualQuery' not in s:
    if state_marker not in s:
        raise RuntimeError('scannerMainProductText state not found')
    s = s.replace(state_marker, state_marker + '    private var scannerBraveVisualQuery = ""\n', 1)

reset_marker = '''            scannerMainProductText = ""
            scannerLastCandidateAt = 0L'''
if reset_marker in s and 'scannerBraveVisualQuery = ""\n            scannerLastCandidateAt' not in s:
    s = s.replace(
        reset_marker,
        '''            scannerMainProductText = ""
            scannerBraveVisualQuery = ""
            scannerLastCandidateAt = 0L''',
        1,
    )

# Preserve the detailed query computed from prominent product text + useful ML labels before the
# v3 route changes the displayed candidate to a generic visual-search message.
needle = '''        // A real image match is the primary route for every non-structured physical object.
        // Strong, prominent brand/model text is only a refinement signal on top of the image.
        scannerVisualSearchPreferred = directUrl.isBlank()'''
replacement = '''        scannerBraveVisualQuery = if (directUrl.isBlank()) query.take(180) else ""

        // Brave mode: keep image-first camera analysis locally, but route web results through
        // Brave Images using the strongest detected product text/object labels.
        scannerVisualSearchPreferred = directUrl.isBlank()'''
if replacement not in s:
    if needle not in s:
        raise RuntimeError('visual query preservation marker not found')
    s = s.replace(needle, replacement, 1)

empty_old = '''            scannerVisualSearchPreferred = true
            scannerMainProductText = ""
            scannerSelectedQuery = "Pencarian visual objek"'''
empty_new = '''            scannerVisualSearchPreferred = true
            scannerMainProductText = ""
            scannerBraveVisualQuery = ""
            scannerSelectedQuery = "Pencarian visual objek"'''
if empty_new not in s:
    if empty_old not in s:
        raise RuntimeError('empty visual route marker not found')
    s = s.replace(empty_old, empty_new, 1)

perform = r'''    private fun performScannerSearch() {
        // Barcode / QR / direct URL stays exact.
        if (!scannerVisualSearchPreferred && (scannerSelectedQuery.isNotBlank() || scannerSelectedUrl.isNotBlank())) {
            openSearchResults(scannerSelectedQuery, scannerSelectedUrl)
            return
        }

        // Brave does not expose reverse-image upload in its public web UI. Build the strongest
        // possible visual-product query from the camera's local OCR + ML Kit physical-object labels
        // and open Brave Images directly. Generic/noisy OCR is deliberately excluded by v3.
        val parts = listOf(scannerMainProductText, scannerBraveVisualQuery, scannerSelectedQuery)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.equals("Pencarian visual objek", ignoreCase = true) ||
                    it.equals("Pencarian visual gambar", ignoreCase = true) ||
                    it.equals("Cari bentuk/model objek yang sama", ignoreCase = true)
            }
            .distinctBy { it.lowercase() }

        var query = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(220)
        if (query.isBlank()) {
            query = "produk benda fisik bentuk serupa"
        }

        scannerSearchButton?.isEnabled = false
        scannerSearchButton?.text = "…"
        scannerStatusText?.text = "Membuka hasil benda serupa di Brave Images…"

        searchQuery = query
        searchUrl = "https://search.brave.com/images?q=${Uri.encode(query)}"
        stopEmbeddedScanner()
        showSearchWebPanel()

        scannerSearchButton?.text = "Cari"
        scannerSearchButton?.isEnabled = true
    }'''
replace_block(
    '    private fun performScannerSearch() {',
    '    private fun cropScannerVisualTarget(frame: Bitmap): Bitmap {',
    perform,
    'Brave scanner search',
)

# Replace the old Lens uploader with a harmless Brave helper so no camera path can call Google.
helper = r'''    private fun openBraveImageResults(query: String) {
        val clean = query.replace(Regex("\\s+"), " ").trim().ifBlank { "produk benda fisik bentuk serupa" }
        searchQuery = clean
        searchUrl = "https://search.brave.com/images?q=${Uri.encode(clean)}"
        stopEmbeddedScanner()
        showSearchWebPanel()
    }'''
start = s.find('    /**\n     * Current Chromium-compatible Lens upload.')
if start >= 0:
    end = s.find('    private fun navigateEmbeddedSearchBack() {', start)
    if end < 0:
        raise RuntimeError('Lens upload end marker not found')
    s = s[:start] + helper.rstrip() + '\n\n' + s[end:]
else:
    old_start = s.find('    /** Upload actual cropped camera pixels for web visual matching, not only an OCR/ML label. */')
    if old_start >= 0:
        end = s.find('    private fun navigateEmbeddedSearchBack() {', old_start)
        if end < 0:
            raise RuntimeError('legacy Lens upload end marker not found')
        s = s[:old_start] + helper.rstrip() + '\n\n' + s[end:]

# Embedded web/search defaults: Brave only.
replacements = {
    'https://www.google.com/search?q=': 'https://search.brave.com/search?q=',
    'https://google.com/search?q=': 'https://search.brave.com/search?q=',
    'https://www.google.com/': 'https://search.brave.com/',
    'https://google.com/': 'https://search.brave.com/',
    'https://lens.google.com/': 'https://search.brave.com/',
}
for old, new in replacements.items():
    s = s.replace(old, new)

# Remove any same-WebView Lens upload hook left by an older generated variant.
s = s.replace('                    view?.let { maybeRunPendingLensUpload(it, url.orEmpty()) }\n', '')

# If the helper still exists from a previously generated source, remove it completely so no
# Google/Lens network request can happen at runtime.
start = s.find('    private fun maybeRunPendingLensUpload(')
if start >= 0:
    end = s.find('    private fun performSearchFromInput() {', start)
    if end < 0:
        raise RuntimeError('pending Lens helper end marker not found')
    s = s[:start] + s[end:]

p.write_text(s, encoding='utf-8')
print('Applied v0.18 Brave v8: Brave Search + Brave Images, no Google Lens runtime route.')
