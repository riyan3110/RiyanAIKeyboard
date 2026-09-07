#!/usr/bin/env python3
from pathlib import Path

path = Path("app/build.gradle.kts")
text = path.read_text(encoding="utf-8")
old = '            source.contains(visible) -> Unit\n            else -> error("Visible keyboard version label patch did not match the source")'
new = '            source.contains("""text = "AI Ads Keyboard"""") -> Unit\n            source.contains(visible) -> Unit\n            else -> error("Visible keyboard version label patch did not match the source")'
if new in text:
    print("Brand-only visible label already accepted")
elif old in text:
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("Updated visible version patch to accept brand-only footer")
else:
    raise SystemExit("Visible version task anchor not found")
