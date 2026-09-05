from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_CLIENT = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"
KEYBOARD_SERVICE = ROOT / "app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt"
SETTINGS_OVERLAY = ROOT / "app/src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt"


def main() -> None:
    """The provider is now stored directly in source instead of patched at build time.

    Keep this task as a compatibility no-op because app/build.gradle.kts from older
    revisions still invokes it before compilation. Failing here would break every
    build once AiClient already contains BluesMinds plus the newer providers.
    """
    ai_client = AI_CLIENT.read_text(encoding="utf-8")
    keyboard_service = KEYBOARD_SERVICE.read_text(encoding="utf-8")
    settings_overlay = SETTINGS_OVERLAY.read_text(encoding="utf-8")

    required = {
        "AiClient provider": 'BLUESMINDS("bluesminds", "BluesMinds")' in ai_client,
        "AiClient settings": "val bluesMindsApiKey: String" in ai_client,
        "AiClient dispatch": "AiProvider.BLUESMINDS ->" in ai_client,
        "keyboard settings": 'bluesMindsApiKey = prefs.getString("bluesminds_api_key"' in keyboard_service,
        "settings overlay": "AiProvider.BLUESMINDS" in settings_overlay,
    }
    missing = [name for name, present in required.items() if not present]
    if missing:
        raise RuntimeError(
            "BluesMinds static integration is incomplete: " + ", ".join(missing)
        )

    print("BluesMinds already integrated in source; legacy build patch skipped")


if __name__ == "__main__":
    main()
