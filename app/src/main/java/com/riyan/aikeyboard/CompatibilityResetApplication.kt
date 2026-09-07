package com.riyan.aikeyboard

import android.app.Application
import android.content.Context
import java.io.File

/**
 * One-time compatibility reset for the special v0.21.24 build that intentionally
 * behaves like a fresh v0.21.22 install even when it is installed over v0.21.23.
 *
 * Android normally keeps the previous app's private data on update. That means
 * v0.21.23 preferences/WebView state can survive even when the APK code itself is
 * rolled back. This class removes that carried-over state once, before any Activity
 * or IME service is created, then leaves the app alone on subsequent launches.
 */
class CompatibilityResetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val marker = File(noBackupFilesDir, ".v02124_clean_v02122_done")
        if (marker.exists()) return

        runCatching {
            val sharedPrefsDir = File(applicationInfo.dataDir, "shared_prefs")
            sharedPrefsDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                ?.forEach { file ->
                    val name = file.name.removeSuffix(".xml")
                    getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }

            // Remove other private state that can make an older APK look like the newer build.
            listOf(
                filesDir,
                cacheDir,
                File(applicationInfo.dataDir, "app_webview"),
                File(applicationInfo.dataDir, "databases")
            ).forEach { dir ->
                runCatching {
                    if (dir.exists()) dir.listFiles()?.forEach { it.deleteRecursively() }
                }
            }

            marker.parentFile?.mkdirs()
            marker.writeText("v0.21.22-clean-state")
        }
    }
}
