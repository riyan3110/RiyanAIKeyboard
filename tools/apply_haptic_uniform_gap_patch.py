#!/usr/bin/env python3
from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch marker not found: {label}")
    return text.replace(old, new, 1)


s = SERVICE.read_text(encoding="utf-8")

# Auto-capitalization can call renderKeyboard() synchronously. Previously the haptic
# call happened after spec.action(), so a key that caused Shift/capital state to
# re-render could feel noticeably late. Fire normal key feedback BEFORE the action.
s = replace_once(
    s,
    '''                        spec.action()
                        actionTriggered = true
                        // Keep tactile feedback in ultra-fast mode too, but run it immediately
                        // instead of adding one Runnable per key to the main-thread queue.
                        keyFeedback(view, longPress = false)''',
    '''                        // Give tactile feedback first so an auto-cap render can never delay vibration.
                        keyFeedback(view, longPress = false)
                        spec.action()
                        actionTriggered = true''',
    "instant key haptic before action",
)

s = replace_once(
    s,
    '''                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        spec.action()
                        keyFeedback(view, longPress = false)
                        actionTriggered = true
                    }''',
    '''                    if (!longTriggered && !actionTriggered && moved <= touchTolerancePx) {
                        // Feedback must happen before actions such as auto-capitalization re-render the keyboard.
                        keyFeedback(view, longPress = false)
                        spec.action()
                        actionTriggered = true
                    }''',
    "release key haptic before action",
)

# Tighten left/right key spacing and make the standard key-cell margin uniform.
# A 1dp margin on every side produces a 2dp gap between neighboring keys in both
# directions, so horizontal spacing is no longer wider than vertical spacing.
s = replace_once(
    s,
    '''                    setMargins(dp(2), if (isFirstKeyboardRow) dp(1) else dp(3), dp(2), dp(3))''',
    '''                    setMargins(dp(1), dp(1), dp(1), dp(1))''',
    "uniform standard key gaps",
)

SERVICE.write_text(s, encoding="utf-8")
print("Applied immediate haptic-before-render + uniform 2dp key gaps")
