package com.riyan.aikeyboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PRIVATE-only SAF bridge for exporting/importing non-credential keyboard settings. */
class SettingsBackupTransferActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackupStore.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@registerForActivityResult finishQuietly()
        runCatching {
            val (json, summary) = SettingsBackupStore.export(this, prefs)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(json)
            } ?: error("Tidak bisa membuka lokasi penyimpanan backup.")
            summary
        }.onSuccess { summary ->
            val photo = if (summary.themePhotoIncluded) " · foto tema ikut" else ""
            Toast.makeText(
                this,
                "Backup tersimpan: ${summary.settingsCount} pengaturan · ${summary.pinnedClipboardCount} clipboard pin$photo",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Backup gagal dibuat.", Toast.LENGTH_LONG).show()
        }
        finishQuietly()
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult finishQuietly()
        runCatching {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("File backup tidak bisa dibaca.")
            SettingsBackupStore.import(this, prefs, raw)
        }.onSuccess { summary ->
            val photo = if (summary.themePhotoIncluded) " · foto tema dipulihkan" else ""
            Toast.makeText(
                this,
                "Backup dipulihkan: ${summary.settingsCount} pengaturan · ${summary.pinnedClipboardCount} clipboard pin$photo. API Key dan Base URL tetap tidak diubah.",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Backup gagal dipulihkan.", Toast.LENGTH_LONG).show()
        }
        finishQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_EXPORT -> exportLauncher.launch(defaultFileName())
            MODE_IMPORT -> importLauncher.launch(arrayOf(SettingsBackupStore.MIME_TYPE, "application/octet-stream", "text/plain"))
            else -> finishQuietly()
        }
    }

    private fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "AI-Ads-Keyboard-backup-$stamp${SettingsBackupStore.FILE_EXTENSION}"
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MODE = "backup_mode"
        const val MODE_EXPORT = "export"
        const val MODE_IMPORT = "import"
        private const val PREFS = "riyan_ai"
    }
}
