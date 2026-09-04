from pathlib import Path
from textwrap import dedent

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
GALLERY = Path("app/src/main/java/com/riyan/aikeyboard/GalleryPickerActivity.kt")
DRAWABLE = Path("app/src/main/res/drawable")

service = SERVICE.read_text()

def rep(old, new):
    global service
    if old not in service:
        raise SystemExit("Patch marker not found:\n" + old[:240])
    service = service.replace(old, new, 1)

rep(
    "import android.content.res.Configuration\n",
    "import android.content.res.ColorStateList\nimport android.content.res.Configuration\n"
)
rep(
    "import android.widget.ImageView\n",
    "import android.widget.ImageButton\nimport android.widget.ImageView\n"
)
rep(
    "    private lateinit var aiFullscreenButton: Button\n",
    "    private lateinit var aiFullscreenButton: ImageButton\n"
)

rep(
'''        aiFullscreenButton = aiPanelButton("⛶") { toggleAiFullscreen() }
        aiFullscreenButton.contentDescription = "Buka obrolan AI layar penuh"
        header.addView(aiFullscreenButton, LinearLayout.LayoutParams(dp(40), headerControlHeight).apply {
            leftMargin = dp(2)
        })
        header.addView(aiPanelButton("✕") { toggleAiPanel(false) }, LinearLayout.LayoutParams(dp(40), headerControlHeight).apply {
            leftMargin = dp(2)
        })''',
'''        aiFullscreenButton = premiumIconButton(
            R.drawable.ic_fullscreen_modern,
            "Buka obrolan AI layar penuh"
        ) { toggleAiFullscreen() }
        header.addView(aiFullscreenButton, LinearLayout.LayoutParams(dp(40), headerControlHeight).apply {
            leftMargin = dp(2)
        })
        header.addView(
            premiumIconButton(R.drawable.ic_close_modern, "Tutup obrolan AI") { toggleAiPanel(false) },
            LinearLayout.LayoutParams(dp(40), headerControlHeight).apply { leftMargin = dp(2) }
        )'''
)

rep(
'''            aiFullscreenButton.text = if (aiFullscreen) "↙" else "⛶"
            aiFullscreenButton.contentDescription = if (aiFullscreen) {
                "Kecilkan obrolan AI"
            } else {
                "Buka obrolan AI layar penuh"
            }''',
'''            aiFullscreenButton.setImageResource(
                if (aiFullscreen) R.drawable.ic_fullscreen_exit_modern else R.drawable.ic_fullscreen_modern
            )
            aiFullscreenButton.contentDescription = if (aiFullscreen) {
                "Kecilkan obrolan AI"
            } else {
                "Buka obrolan AI layar penuh"
            }'''
)

rep(
'''        utilityBar.addView(toolbarButton("📷", dp(43)) { launchScanner() })''',
'''        utilityBar.addView(
            toolbarIconButton(R.drawable.ic_camera_modern, "Kamera penelusuran", dp(43)) { launchScanner() }
        )'''
)
rep(
'''        utilityBar.addView(toolbarButton("⚙", dp(43)) { openSettings() })''',
'''        utilityBar.addView(
            toolbarIconButton(R.drawable.ic_settings_modern, "Pengaturan", dp(43)) { openSettings() }
        )'''
)

rep(
'''        header.addView(compactButton("🖼") { launchGalleryPicker() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())))
        header.addView(compactButton("⚡") { toggleScannerTorch() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })
        header.addView(compactButton("✕") { closeSearchSurface() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply {
            leftMargin = dp(2)
        })''',
'''        header.addView(
            premiumIconButton(R.drawable.ic_gallery_modern, "Pilih foto dari galeri") { launchGalleryPicker() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp()))
        )
        header.addView(
            premiumIconButton(R.drawable.ic_flash_modern, "Flash kamera") { toggleScannerTorch() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )
        header.addView(
            premiumIconButton(R.drawable.ic_close_modern, "Tutup kamera penelusuran") { closeSearchSurface() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )'''
)

rep(
'''        header.addView(compactButton("←") { navigateEmbeddedSearchBack() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })
        header.addView(compactButton("↗") { openExternalLink(searchUrl) }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })
        header.addView(compactButton("✕") { closeSearchSurface() }, LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) })''',
'''        header.addView(
            premiumIconButton(R.drawable.ic_back_modern, "Kembali") { navigateEmbeddedSearchBack() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )
        header.addView(
            premiumIconButton(R.drawable.ic_open_external_modern, "Buka di aplikasi atau browser") { openExternalLink(searchUrl) },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )
        header.addView(
            premiumIconButton(R.drawable.ic_close_modern, "Tutup penelusuran") { closeSearchSurface() },
            LinearLayout.LayoutParams(dp(38), dp(searchHeaderHeightDp())).apply { leftMargin = dp(2) }
        )'''
)

rep(
'''    private fun toolbarButton(label: String, widthPx: Int, action: () -> Unit) = Button(this).apply {''',
'''    private fun premiumIconButton(
        iconRes: Int,
        description: String,
        action: () -> Unit
    ) = ImageButton(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(9), dp(7), dp(9), dp(7))
        background = roundedStrokedBackground(
            Color.rgb(49, 48, 58),
            11f,
            Color.rgb(82, 78, 99),
            1
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpFloat(2.2f)
        }
        setOnClickListener {
            keyFeedback(this, longPress = false)
            action()
        }
    }

    private fun toolbarIconButton(
        iconRes: Int,
        description: String,
        widthPx: Int,
        action: () -> Unit
    ) = premiumIconButton(iconRes, description, action).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(widthPx, dp(utilityHeightDp())).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    private fun toolbarButton(label: String, widthPx: Int, action: () -> Unit) = Button(this).apply {'''
)

SERVICE.write_text(service)

GALLERY.write_text(dedent(r'''
package com.riyan.aikeyboard

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity

/**
 * Opens Android's photo picker / default gallery directly instead of the document-file browser,
 * then hands the selected image URI back to the IME.
 */
class GalleryPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openPicker()
    }

    private fun openPicker() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(GALLERY_URI_KEY, uri.toString())
                    .putBoolean(GALLERY_READY_KEY, true)
                    .apply()
            }
        }
        finishWithoutAnimation()
    }

    override fun onBackPressed() {
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val PICK_IMAGE_REQUEST = 702
        private const val PREFS = "riyan_ai"
        private const val GALLERY_READY_KEY = "camera_gallery_ready"
        private const val GALLERY_URI_KEY = "camera_gallery_uri"
    }
}
''').lstrip())

DRAWABLE.mkdir(parents=True, exist_ok=True)

vectors = {
"ic_settings_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19.14,12.94a7.4,7.4 0,0 0,0.05 -0.94,7.4 7.4,0 0,0 -0.05,-0.94l2.03,-1.58a0.5,0.5 0,0 0,0.12 -0.64l-1.92,-3.32a0.5,0.5 0,0 0,-0.61 -0.22l-2.39,0.96a7.34,7.34 0,0 0,-1.63 -0.94L14.38,2.8A0.5,0.5 0,0 0,13.88 2h-3.84a0.5,0.5 0,0 0,-0.49 0.42L9.18,4.96a7.34,7.34 0,0 0,-1.63 0.94L5.16,4.94a0.5,0.5 0,0 0,-0.61 0.22L2.63,8.48a0.5,0.5 0,0 0,0.12 0.64l2.03,1.58a7.4,7.4 0,0 0,-0.05 0.94,7.4 7.4,0 0,0 0.05,0.94l-2.03,1.58a0.5,0.5 0,0 0,-0.12 0.64l1.92,3.32a0.5,0.5 0,0 0,0.61 0.22l2.39,-0.96a7.34,7.34 0,0 0,1.63 0.94l0.37,2.54a0.5,0.5 0,0 0,0.49 0.42h3.84a0.5,0.5 0,0 0,0.49 -0.42l0.37,-2.54a7.34,7.34 0,0 0,1.63 -0.94l2.39,0.96a0.5,0.5 0,0 0,0.61 -0.22l1.92,-3.32a0.5,0.5 0,0 0,-0.12 -0.64zM12,15.5A3.5,3.5 0,1 1,12,8a3.5,3.5 0,0 1,0 7.5z"/>
</vector>''',
"ic_camera_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M9,3l-1.6,2H5a3,3 0,0 0,-3 3v9a3,3 0,0 0,3 3h14a3,3 0,0 0,3 -3V8a3,3 0,0 0,-3 -3h-2.4L15,3zM12,8a5,5 0,1 1,0 10,5 5,0 0,1 0,-10zM12,10a3,3 0,1 0,0 6,3 3,0 0,0 0,-6z"/>
</vector>''',
"ic_flash_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M13,2L4.5,13H11l-1,9L19.5,10H13z"/>
</vector>''',
"ic_gallery_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M5,3h14a2,2 0,0 1,2 2v14a2,2 0,0 1,-2 2H5a2,2 0,0 1,-2 -2V5a2,2 0,0 1,2 -2zM8.5,7A1.5,1.5 0,1 0,8.5 10,1.5 1.5,0 0,0 8.5,7zM5,18h14l-4.6,-6.1 -3.3,4 -2.4,-2.9z"/>
</vector>''',
"ic_back_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.42,-1.41L7.83,13H20z"/>
</vector>''',
"ic_close_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M18.3,5.71L12,12l6.3,6.29 -1.41,1.42L10.59,13.41 4.29,19.71 2.88,18.29 9.17,12 2.88,5.71 4.29,4.29 10.59,10.59 16.89,4.29z"/>
</vector>''',
"ic_fullscreen_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M7,14H5v5h5v-2H7zM5,10h2V7h3V5H5zM17,17h-3v2h5v-5h-2zM14,5v2h3v3h2V5z"/>
</vector>''',
"ic_fullscreen_exit_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M5,16h3v3h2v-5H5zM8,8H5v2h5V5H8zM14,19h2v-3h3v-2h-5zM16,8V5h-2v5h5V8z"/>
</vector>''',
"ic_open_external_modern.xml": r'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M14,3v2h3.59L8.29,14.29l1.42,1.42L19,6.41V10h2V3zM19,19H5V5h6V3H5a2,2 0,0 0,-2 2v14a2,2 0,0 0,2 2h14a2,2 0,0 0,2 -2v-6h-2z"/>
</vector>'''
}

for name, body in vectors.items():
    (DRAWABLE / name).write_text(body + "\n")
