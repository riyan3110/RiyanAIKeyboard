from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')
old = '''        scannerSearchButton = compactButton("Cari") {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performScannerSearch()
        }.apply {'''
new = '''        scannerSearchButton = compactButton("Cari") {
            performScannerSearch()
        }.apply {'''
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise RuntimeError('embedded Lens Cari button target not found')
p.write_text(s, encoding='utf-8')
print('Fixed embedded Lens Cari compilation while preserving always-clickable search action.')
