from pathlib import Path
import shutil
import subprocess

OVERLAY = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")
THEME = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardTheme.kt")
SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
MANIFEST = Path("app/src/main/AndroidManifest.xml")
SOURCE_ICON = Path("app/src/main/res/drawable-nodpi/app_icon.webp")
COMPAT_ICON = Path("app/src/main/res/drawable/app_icon_compat.png")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch marker not found: {label}")
    return text.replace(old, new, 1)


# 1) Keep Photo from Device active while changing manual key colors.
text = OVERLAY.read_text()
old_line = "                    draft.themeMode = KeyboardTheme.MODE_CUSTOM\n"
new_line = "                    if (draft.themeMode != KeyboardTheme.MODE_PHOTO) draft.themeMode = KeyboardTheme.MODE_CUSTOM\n"
if new_line not in text:
    count = text.count(old_line)
    if count < 4:
        raise RuntimeError(f"Expected 4 photo-safe color mode markers, found {count}")
    text = text.replace(old_line, new_line, 4)
OVERLAY.write_text(text)


# 2) When a gallery photo is active, use the saved manual colors for the key UI
# instead of the old hard-coded photo-theme colors.
text = THEME.read_text()
old_photo = '''        if (usesPhoto) {
            return KeyboardThemePalette(
                background = Color.rgb(18, 18, 23),
                key = Color.argb(224, 45, 45, 54),
                specialKey = Color.argb(230, 35, 63, 75),
                pressedKey = Color.rgb(111, 83, 220),
                accent = Color.rgb(105, 76, 218),
                text = Color.WHITE,
                usesPhoto = true
            )
        }
'''
new_photo = '''        if (usesPhoto) {
            return KeyboardThemePalette(
                background = Color.rgb(18, 18, 23),
                key = customKey,
                specialKey = customKey,
                pressedKey = blend(customKey, customBorder, 0.24f),
                accent = customBorder,
                text = customLetter,
                usesPhoto = true,
                border = customBorder,
                numberText = customNumber
            )
        }
'''
text = replace_once(text, old_photo, new_photo, "photo palette uses manual colors")
THEME.write_text(text)


# 3) Recalibrate values above 100%. Keep 100% exactly as before, but make
# 101-150% grow gradually so 120% no longer causes overlapping/broken rows.
text = SERVICE.read_text()
old_scale = '        keyBoxScale = prefs.getInt("key_box_scale_percent", 100).coerceIn(65, 150) / 100f\n'
new_scale = '''        val keyScalePercent = prefs.getInt("key_box_scale_percent", 100).coerceIn(65, 150)
        keyBoxScale = if (keyScalePercent <= 100) {
            keyScalePercent / 100f
        } else {
            1f + ((keyScalePercent - 100) * 0.0025f)
        }
'''
text = replace_once(text, old_scale, new_scale, "soft key scale mapping")
old_comment = '''            // At the old 100% setting the regular caps now fill 90% of their cells instead of
            // only 78%. The new 110% maximum can reach 99%, while clamping prevents overlap.
'''
new_comment = '''            // Keep 100% as the visual baseline. Above 100%, growth is deliberately softened
            // so 120-150% remains usable without keys colliding or breaking rows.
'''
if old_comment in text:
    text = text.replace(old_comment, new_comment, 1)

# 4) Keep the space key slightly narrower at large scales. The neighboring
# comma/period keys keep their existing size; only the space key stops expanding
# beyond its cell so 150% still leaves a visible gap on both sides.
old_frame_scale = '''            scaleX = (keyBoxScale * if (referenceWideKey) 0.98f else 0.94f).coerceAtMost(1.18f)
            scaleY = (keyBoxScale * if (referenceLargeKey) 0.96f else 0.90f).coerceAtMost(1.22f)
'''
new_frame_scale = '''            val horizontalScale = when {
                spec.label == "spasi" && keyBoxScale > 1f -> {
                    // Preserve the normal space-key width and add almost no extra width above 100%.
                    // At 150% the space key stays inside its cell, leaving a gap to comma/period.
                    (0.98f + ((keyBoxScale - 1f) * 0.04f)).coerceAtMost(0.99f)
                }
                referenceWideKey -> (keyBoxScale * 0.98f).coerceAtMost(1.18f)
                else -> (keyBoxScale * 0.94f).coerceAtMost(1.18f)
            }
            scaleX = horizontalScale
            scaleY = (keyBoxScale * if (referenceLargeKey) 0.96f else 0.90f).coerceAtMost(1.22f)
'''
text = replace_once(text, old_frame_scale, new_frame_scale, "narrow space key at high scale")
SERVICE.write_text(text)


# 5) Package the existing app artwork as a plain PNG resource and point the
# manifest directly at it. Some Android file managers cannot extract APK icons
# reliably when the launcher icon only exists as a nodpi WebP resource.
COMPAT_ICON.parent.mkdir(parents=True, exist_ok=True)
if not SOURCE_ICON.exists():
    raise RuntimeError(f"Source app icon not found: {SOURCE_ICON}")

converted = False
try:
    from PIL import Image
    with Image.open(SOURCE_ICON) as image:
        image.convert("RGBA").save(COMPAT_ICON, "PNG", optimize=True)
    converted = True
except Exception:
    pass

if not converted and shutil.which("dwebp"):
    subprocess.run(["dwebp", str(SOURCE_ICON), "-o", str(COMPAT_ICON)], check=True)
    converted = True

if not converted and shutil.which("ffmpeg"):
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-i", str(SOURCE_ICON), str(COMPAT_ICON)],
        check=True,
    )
    converted = True

if not converted and shutil.which("convert"):
    subprocess.run(["convert", str(SOURCE_ICON), str(COMPAT_ICON)], check=True)
    converted = True

if not converted or not COMPAT_ICON.exists():
    raise RuntimeError("Could not convert launcher WebP to compatibility PNG")

manifest = MANIFEST.read_text()
# Replace each attribute independently so this remains idempotent even when the
# manifest is reformatted (single-line or multi-line attributes).
manifest = replace_once(
    manifest,
    'android:icon="@drawable/app_icon"',
    'android:icon="@drawable/app_icon_compat"',
    "compatibility PNG launcher icon",
)
manifest = replace_once(
    manifest,
    'android:roundIcon="@drawable/app_icon"',
    'android:roundIcon="@drawable/app_icon_compat"',
    "compatibility PNG round launcher icon",
)
MANIFEST.write_text(manifest)

print("Photo colors + safe 150% scale + spaced space key + APK icon compatibility patch applied")
