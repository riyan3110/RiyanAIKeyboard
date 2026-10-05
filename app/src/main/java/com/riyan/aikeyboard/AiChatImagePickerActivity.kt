package com.riyan.aikeyboard

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** PEMICU LAMPIRAN GAMBAR untuk obrolan AI: pilih gambar, simpan hasilnya, tutup. */
class AiChatImagePickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            pendingResult = uri
            finish()
        }
        if (savedInstanceState == null) launcher.launch("image/*")
    }

    companion object {
        private var pendingResult: Uri? = null

        fun take(): Uri? {
            val uri = pendingResult
            pendingResult = null
            return uri
        }
    }
}
