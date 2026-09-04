package com.riyan.aikeyboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ThemePhotoPickerActivity : AppCompatActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            getSharedPreferences("riyan_ai", MODE_PRIVATE).edit()
                .putString("keyboard_theme_image_uri", uri.toString())
                .putString("keyboard_theme_mode", KeyboardTheme.MODE_PHOTO)
                .apply()
            Toast.makeText(this, "Foto tema dipilih.", Toast.LENGTH_SHORT).show()
        }
        finishQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch(arrayOf("image/*"))
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
