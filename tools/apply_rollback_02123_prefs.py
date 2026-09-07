from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt"

source = TARGET.read_text(encoding="utf-8")

old_init = '''        setBackgroundColor(darkBg)
        clipChildren = false
        clipToPadding = false
        buildBrowser()'''
new_init = '''        setBackgroundColor(darkBg)
        clipChildren = false
        clipToPadding = false
        rollbackGoogleDefaultsFrom02123IfNeeded()
        buildBrowser()'''

if "rollbackGoogleDefaultsFrom02123IfNeeded()" not in source:
    if old_init not in source:
        raise RuntimeError("Rollback init marker not found")
    source = source.replace(old_init, new_init, 1)

helper = '''
    private fun rollbackGoogleDefaultsFrom02123IfNeeded() {
        // v0.21.23 migrated Brave defaults to Google and stored that choice in SharedPreferences.
        // When installing this compatibility build over v0.21.23 Android keeps those preferences,
        // so reset only the values created by that migration. API keys, theme, typing settings,
        // memory, bookmarks/history, and all other user data remain untouched.
        if (!prefs.getBoolean("browser_google_default_migrated_v1", false)) return
        prefs.edit()
            .putString(KEY_SEARCH_ENGINE, ENGINE_BRAVE)
            .putString(KEY_HOMEPAGE, DEFAULT_HOME)
            .remove(KEY_TABS)
            .remove(KEY_CURRENT_TAB)
            .remove("browser_google_default_migrated_v1")
            .apply()
    }

'''

if "private fun rollbackGoogleDefaultsFrom02123IfNeeded()" not in source:
    marker = "    fun release() {\n"
    if marker not in source:
        raise RuntimeError("Rollback helper insertion marker not found")
    source = source.replace(marker, helper + marker, 1)

TARGET.write_text(source, encoding="utf-8")
print("Applied one-time v0.21.23 browser preference rollback for v0.21.22 compatibility build")
