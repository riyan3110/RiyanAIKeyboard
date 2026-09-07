from pathlib import Path

OVERLAY = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")
THEME = Path("app/src/main/java/com/riyan/aikeyboard/KeyboardTheme.kt")
SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")


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
SERVICE.write_text(text)

print("Photo-theme manual colors + safe 150% key-scale patch applied")
