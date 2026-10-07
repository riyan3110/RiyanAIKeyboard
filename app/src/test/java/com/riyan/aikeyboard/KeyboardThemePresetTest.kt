package com.riyan.aikeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class KeyboardThemePresetTest {
    @Test fun presetsIgnoreOldPhotoAndManualColorsAndUseMatchingHue() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("preset-colors-test", Context.MODE_PRIVATE)
        val expected = mapOf(
            KeyboardTheme.MODE_BLUE to Color.rgb(52,166,255),
            KeyboardTheme.MODE_PURPLE to Color.rgb(197,73,255),
            KeyboardTheme.MODE_GREEN to Color.rgb(48,215,132),
            KeyboardTheme.MODE_ROSE to Color.rgb(220,30,65))
        for ((mode, accent) in expected) {
            prefs.edit().clear().putString("keyboard_theme_mode",mode)
                .putString("keyboard_theme_image_uri","file:///old-photo.jpg")
                .putString("keyboard_custom_border_color","#FF8800").commit()
            val palette = KeyboardTheme.palette(prefs)
            assertFalse(palette.usesPhoto)
            assertEquals(accent,palette.accent)
            assertEquals(accent,palette.border)
            assertTrue(KeyboardTheme.background(context,prefs,palette) is GradientDrawable)
        }
    }
    @Test fun blackAndTransparentBackgroundsMatchTheirNames() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("preset-colors-test", Context.MODE_PRIVATE)
        for ((mode,color) in listOf(KeyboardTheme.MODE_AMOLED to Color.BLACK, KeyboardTheme.MODE_TRANSPARENT to Color.TRANSPARENT)) {
            prefs.edit().clear().putString("keyboard_theme_mode",mode).commit()
            val drawable = KeyboardTheme.background(context,prefs,KeyboardTheme.palette(prefs)) as ColorDrawable
            assertEquals(color,drawable.color)
        }
    }
}
