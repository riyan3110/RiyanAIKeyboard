from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
CORE_PATCH = ROOT / "tools/apply_bluesminds_provider_patch_core.py"
KEYBOARD_SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"

# Preserve the existing BluesMinds patch exactly, then apply the visual key-label fix.
runpy.run_path(str(CORE_PATCH), run_name="__main__")

text = KEYBOARD_SERVICE.read_text()
old = '            if (spec.alternate != null) translationY = dpFloat(4f)'
new = '''            if (spec.label in setOf("q", "y", "p", "g", "j")) {
                translationY = dpFloat(-2f)
            } else if (spec.alternate != null && spec.label.none { it.isLetterOrDigit() }) translationY = dpFloat(4f)'''

if new not in text:
    if old not in text:
        raise RuntimeError("Key-label vertical centering marker not found")
    text = text.replace(old, new, 1)
    KEYBOARD_SERVICE.write_text(text)

print("Visual key-label centering patch applied")
