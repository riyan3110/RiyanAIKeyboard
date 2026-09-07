#!/usr/bin/env python3
from pathlib import Path

# Keep the old Gradle visible-version task compatible with the requested brand-only footer.
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

# The app does not generate BuildConfig in this project. Give the settings overlay an
# explicit visible test version so the installed APK can be distinguished from older builds.
settings = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")
settings_text = settings.read_text(encoding="utf-8")
dynamic = '            text = "v${BuildConfig.VERSION_NAME.substringBefore("-")}"'
fixed = '            text = "v0.21.22"'
if dynamic in settings_text:
    settings.write_text(settings_text.replace(dynamic, fixed, 1), encoding="utf-8")
    print("Pinned settings version label to v0.21.22")
elif fixed in settings_text:
    print("Settings version label already v0.21.22")
else:
    raise SystemExit("Settings version label anchor not found")
