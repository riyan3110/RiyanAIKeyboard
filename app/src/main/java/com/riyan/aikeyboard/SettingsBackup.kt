package com.riyan.aikeyboard

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

internal data class SettingsBackupSummary(
    val settingsCount: Int,
    val pinnedClipboardCount: Int,
    val themePhotoIncluded: Boolean
)

/**
 * PRIVATE-only portable settings backup.
 *
 * Provider credentials are intentionally never exported. This includes API keys, Base URLs,
 * the editable provider profile store, and the selected provider connection. Restore only
 * overlays safe preferences so credentials already present on the device are left untouched.
 */
internal object SettingsBackupStore {
    const val MIME_TYPE = "application/json"
    const val FILE_EXTENSION = ".aikbackup"

    private const val FORMAT = "AI_ADS_KEYBOARD_SETTINGS_BACKUP"
    private const val VERSION = 1
    private const val MAX_THEME_PHOTO_BYTES = 12 * 1024 * 1024
    private const val MAX_BACKUP_TEXT_BYTES = 24 * 1024 * 1024

    private val exactExcludedKeys = setOf(
        "provider",
        "private_provider_profiles_v1",
        "private_provider_selected_v1",
        "keyboard_theme_image_uri",
        "clipboard_items",
        "shared_context",
        "shared_context_updated_at"
    )

    fun export(context: Context, prefs: SharedPreferences): Pair<String, SettingsBackupSummary> {
        val preferenceJson = JSONObject()
        var count = 0
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (shouldExcludePreference(key)) return@forEach
            encodePreference(value)?.let { encoded ->
                preferenceJson.put(key, encoded)
                count += 1
            }
        }

        val pinned = readPinnedClipboard(prefs)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("preferences", preferenceJson)
            .put("pinnedClipboard", JSONArray(pinned))
            .put("credentialsIncluded", false)

        val photo = readThemePhoto(context, prefs)
        if (photo != null) {
            root.put(
                "themePhoto",
                JSONObject()
                    .put("mime", photo.first)
                    .put("data", Base64.encodeToString(photo.second, Base64.NO_WRAP))
            )
        }

        return root.toString(2) to SettingsBackupSummary(
            settingsCount = count,
            pinnedClipboardCount = pinned.size,
            themePhotoIncluded = photo != null
        )
    }

    fun import(context: Context, prefs: SharedPreferences, raw: String): SettingsBackupSummary {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_TEXT_BYTES) {
            "File backup terlalu besar."
        }
        val root = JSONObject(raw)
        require(root.optString("format") == FORMAT) {
            "File ini bukan backup AI Ads Keyboard."
        }
        require(root.optInt("version", 0) in 1..VERSION) {
            "Versi backup belum didukung aplikasi ini."
        }

        val editor = prefs.edit()
        var count = 0
        val preferenceJson = root.optJSONObject("preferences") ?: JSONObject()
        val keys = preferenceJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (shouldExcludePreference(key)) continue
            val encoded = preferenceJson.optJSONObject(key) ?: continue
            if (decodePreference(editor, key, encoded)) count += 1
        }

        val pinned = readStringArray(root.optJSONArray("pinnedClipboard"))
        restorePinnedClipboard(prefs, editor, pinned)

        var photoRestored = false
        root.optJSONObject("themePhoto")?.let { photoJson ->
            val encoded = photoJson.optString("data")
            if (encoded.isNotBlank()) {
                val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_THEME_PHOTO_BYTES) {
                    val mime = photoJson.optString("mime").ifBlank { "image/jpeg" }
                    val uri = saveRestoredThemePhoto(context, bytes, mime)
                    editor.putString("keyboard_theme_image_uri", uri.toString())
                    photoRestored = true
                }
            }
        }

        check(editor.commit()) { "Pengaturan gagal disimpan ke perangkat." }
        return SettingsBackupSummary(
            settingsCount = count,
            pinnedClipboardCount = pinned.size,
            themePhotoIncluded = photoRestored
        )
    }

    private fun shouldExcludePreference(key: String): Boolean {
        if (key in exactExcludedKeys) return true
        val normalized = key.lowercase(Locale.ROOT).replace('-', '_')
        if (normalized.contains("api_key") || normalized.contains("apikey")) return true
        if (normalized.contains("base_url") || normalized.contains("baseurl")) return true
        if (normalized.contains("access_token") || normalized.contains("auth_token")) return true
        if (normalized == "token" || normalized.endsWith("_token")) return true
        if (normalized.startsWith("camera_search_")) return true
        if (normalized.startsWith("theme_picker_")) return true
        if (normalized.startsWith("web_image_picker_")) return true
        return false
    }

    private fun encodePreference(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("type", "string").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Set<*> -> {
            val strings = value.filterIsInstance<String>()
            if (strings.size != value.size) null
            else JSONObject().put("type", "stringSet").put("value", JSONArray(strings.sorted()))
        }
        else -> null
    }

    private fun decodePreference(
        editor: SharedPreferences.Editor,
        key: String,
        encoded: JSONObject
    ): Boolean = runCatching {
        when (encoded.optString("type")) {
            "string" -> editor.putString(key, encoded.optString("value"))
            "int" -> editor.putInt(key, encoded.getInt("value"))
            "long" -> editor.putLong(key, encoded.getLong("value"))
            "float" -> editor.putFloat(key, encoded.getDouble("value").toFloat())
            "boolean" -> editor.putBoolean(key, encoded.getBoolean("value"))
            "stringSet" -> editor.putStringSet(key, readStringArray(encoded.optJSONArray("value")).toSet())
            else -> return false
        }
        true
    }.getOrDefault(false)

    private fun readPinnedClipboard(prefs: SharedPreferences): List<String> = runCatching {
        val array = JSONArray(prefs.getString("clipboard_items", "[]").orEmpty())
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (!item.optBoolean("pinned", false)) continue
                item.optString("text").takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().take(20)
    }.getOrDefault(emptyList())

    private fun restorePinnedClipboard(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        pinned: List<String>
    ) {
        val currentUnpinned = runCatching {
            val array = JSONArray(prefs.getString("clipboard_items", "[]").orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optBoolean("pinned", false)) continue
                    item.optString("text").takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

        val output = JSONArray()
        val seen = linkedSetOf<String>()
        pinned.filter(String::isNotBlank).take(20).forEach { text ->
            if (seen.add(text)) output.put(JSONObject().put("text", text).put("pinned", true))
        }
        currentUnpinned.take(12).forEach { text ->
            if (seen.add(text)) output.put(JSONObject().put("text", text).put("pinned", false))
        }
        editor.putString("clipboard_items", output.toString())
    }

    private fun readThemePhoto(context: Context, prefs: SharedPreferences): Pair<String, ByteArray>? {
        val value = prefs.getString("keyboard_theme_image_uri", "").orEmpty()
        if (value.isBlank()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_THEME_PHOTO_BYTES) }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "image/jpeg" }
        return mime to bytes
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun saveRestoredThemePhoto(context: Context, bytes: ByteArray, mime: String): Uri {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("restored_keyboard_theme.") }
            ?.forEach { runCatching { it.delete() } }
        val extension = when (mime.lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(context.filesDir, "restored_keyboard_theme.$extension")
        file.outputStream().use { it.write(bytes) }
        return Uri.fromFile(file)
    }

    private fun readStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
}
