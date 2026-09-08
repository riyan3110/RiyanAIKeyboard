#!/usr/bin/env python3
from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
text = SERVICE.read_text(encoding="utf-8")
old = '                    setMargins(dp(1), dp(1), dp(1), dp(1))'
new = '                    setMargins(dpFloat(0.5f).toInt().coerceAtLeast(1), dpFloat(0.5f).toInt().coerceAtLeast(1), dpFloat(0.5f).toInt().coerceAtLeast(1), dpFloat(0.5f).toInt().coerceAtLeast(1))'
if new in text:
    print("PRIVATE tighter key gap already applied")
elif old in text:
    SERVICE.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("Applied PRIVATE tighter key gaps: ~1dp total gap between neighboring keys")
else:
    raise RuntimeError("PRIVATE tighter key gap marker not found")
