package com.riyan.aikeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Receives text explicitly shared or selected by the user without Accessibility access. */
class TextImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }.orEmpty().trim()

        if (text.isNotBlank()) {
            getSharedPreferences("riyan_ai", MODE_PRIVATE).edit()
                .putString("shared_context", text.takeLast(6000))
                .putLong("shared_context_updated_at", System.currentTimeMillis())
                .apply()
            Toast.makeText(this, "Teks siap dibalas di AI Ads Keyboard.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Tidak ada teks yang dapat dibaca.", Toast.LENGTH_SHORT).show()
        }

        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        }
        finish()
    }
}
