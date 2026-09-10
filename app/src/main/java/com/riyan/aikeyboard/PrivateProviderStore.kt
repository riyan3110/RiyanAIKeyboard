package com.riyan.aikeyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

internal data class PrivateProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val models: List<String> = emptyList()
)

/** PRIVATE-only provider profiles for OpenAI-compatible endpoints. */
internal object PrivateProviderStore {
    private const val PROFILES_KEY = "private_provider_profiles_v1"
    private const val SELECTED_KEY = "private_provider_selected_v1"

    fun normalizeBaseUrl(raw: String): String {
        var clean = raw.trim().trimEnd('/')
        clean = clean.replace(Regex("/(?:chat/completions|responses|models)$", RegexOption.IGNORE_CASE), "")
        return clean.trimEnd('/')
    }

    fun providerId(baseUrl: String): String =
        "p_" + Integer.toHexString(normalizeBaseUrl(baseUrl).lowercase().hashCode())

    fun inferName(baseUrl: String): String {
        val clean = normalizeBaseUrl(baseUrl)
        val host = runCatching { URL(clean).host }.getOrNull().orEmpty()
            .removePrefix("www.")
        if (host.isBlank()) return "Provider"
        val stem = host.substringBefore('.').ifBlank { host }
        return stem.split('-', '_').joinToString(" ") { part ->
            part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
        }.ifBlank { host }
    }

    fun modelEndpoints(baseUrl: String): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Base URL harus diawali http:// atau https://"
        }
        return if (base.endsWith("/v1", ignoreCase = true)) {
            listOf("$base/models")
        } else {
            listOf("$base/models", "$base/v1/models").distinct()
        }
    }

    fun parseModels(payload: String): List<String> {
        val root = JSONTokener(payload).nextValue()
        val out = linkedSetOf<String>()

        fun add(value: Any?) {
            when (value) {
                is String -> value.trim().takeIf { it.isNotBlank() }?.let(out::add)
                is JSONObject -> sequenceOf("id", "name", "model")
                    .map(value::optString)
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.let(out::add)
            }
        }

        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) add(array.opt(i))
        }

        when (root) {
            is JSONArray -> addArray(root)
            is JSONObject -> {
                addArray(root.optJSONArray("data"))
                addArray(root.optJSONArray("models"))
                root.optJSONObject("result")?.let { result ->
                    addArray(result.optJSONArray("data"))
                    addArray(result.optJSONArray("models"))
                }
            }
        }
        return out.toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = runCatching {
        var lastError: Throwable? = null
        for (endpoint in modelEndpoints(baseUrl)) {
            val attempt = runCatching {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 7_000
                    connection.readTimeout = 12_000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "AI-Ads-Keyboard/PRIVATE")
                    if (apiKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    require(code in 200..299) {
                        "Endpoint model mengembalikan HTTP $code${body.take(180).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
                    }
                    parseModels(body).also { models ->
                        require(models.isNotEmpty()) { "Daftar model kosong di $endpoint" }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            attempt.getOrNull()?.let { return@runCatching it }
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Daftar model tidak tersedia.")
    }

    fun load(prefs: SharedPreferences): List<PrivateProviderProfile> {
        val raw = prefs.getString(PROFILES_KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val baseUrl = normalizeBaseUrl(item.optString("baseUrl"))
                    if (baseUrl.isBlank()) continue
                    val modelsArray = item.optJSONArray("models")
                    val models = buildList {
                        if (modelsArray != null) {
                            for (j in 0 until modelsArray.length()) {
                                modelsArray.optString(j).trim().takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }.distinct()
                    add(
                        PrivateProviderProfile(
                            id = item.optString("id").ifBlank { providerId(baseUrl) },
                            name = item.optString("name").ifBlank { inferName(baseUrl) },
                            baseUrl = baseUrl,
                            apiKey = item.optString("apiKey"),
                            model = item.optString("model"),
                            models = models
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun ensureMigrated(prefs: SharedPreferences): List<PrivateProviderProfile> {
        load(prefs).takeIf { it.isNotEmpty() }?.let { return it }

        data class Legacy(
            val providerId: String,
            val name: String,
            val baseUrl: String,
            val key: String,
            val model: String
        )

        val selectedLegacy = prefs.getString("provider", "openrouter").orEmpty()
        val legacy = listOf(
            Legacy(
                "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                prefs.getString("openrouter_api_key", prefs.getString("api_key", "")).orEmpty(),
                prefs.getString("openrouter_model", "openrouter/free").orEmpty()
            ),
            Legacy(
                "tabiai", "TabiAI", prefs.getString("tabi_base_url", "https://tabitoken.com").orEmpty(),
                prefs.getString("tabi_api_key", "").orEmpty(), prefs.getString("tabi_model", "").orEmpty()
            ),
            Legacy(
                "9router", "9Router", prefs.getString("9router_base_url", "http://43.159.50.231:20130/v1").orEmpty(),
                prefs.getString("9router_api_key", "").orEmpty(), prefs.getString("9router_model", "").orEmpty()
            ),
            Legacy(
                "bluesminds", "BluesMinds", prefs.getString("bluesminds_base_url", "https://api.bluesminds.com/v1").orEmpty(),
                prefs.getString("bluesminds_api_key", "").orEmpty(), prefs.getString("bluesminds_model", "").orEmpty()
            ),
            Legacy(
                "xkiro", "xKiro", prefs.getString("xkiro_base_url", "https://api.xkiro.com/v1").orEmpty(),
                prefs.getString("xkiro_api_key", "").orEmpty(), prefs.getString("xkiro_model", "").orEmpty()
            ),
            Legacy(
                "orcarouter", "OrcaRouter", prefs.getString("orcarouter_base_url", "https://api.orcarouter.ai/v1").orEmpty(),
                prefs.getString("orcarouter_api_key", "").orEmpty(), prefs.getString("orcarouter_model", "").orEmpty()
            )
        )

        val imported = legacy
            .filter { it.baseUrl.isNotBlank() && (it.key.isNotBlank() || it.providerId == selectedLegacy) }
            .map {
                PrivateProviderProfile(
                    id = providerId(it.baseUrl),
                    name = it.name,
                    baseUrl = normalizeBaseUrl(it.baseUrl),
                    apiKey = it.key,
                    model = it.model,
                    models = listOf(it.model).filter(String::isNotBlank)
                )
            }
            .distinctBy { it.id }

        if (imported.isNotEmpty()) {
            persist(prefs, imported)
            val selected = legacy.firstOrNull { it.providerId == selectedLegacy }
                ?.let { old -> imported.firstOrNull { it.baseUrl == normalizeBaseUrl(old.baseUrl) } }
                ?: imported.first()
            select(prefs, selected.id)
        }
        return imported
    }

    fun selected(prefs: SharedPreferences, profiles: List<PrivateProviderProfile> = load(prefs)): PrivateProviderProfile? {
        val id = prefs.getString(SELECTED_KEY, null)
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun select(prefs: SharedPreferences, id: String) {
        prefs.edit().putString(SELECTED_KEY, id).apply()
    }

    fun save(
        prefs: SharedPreferences,
        baseUrl: String,
        apiKey: String,
        model: String,
        models: List<String> = emptyList()
    ): PrivateProviderProfile {
        val base = normalizeBaseUrl(baseUrl)
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Base URL harus diawali http:// atau https://"
        }
        val id = providerId(base)
        val existing = load(prefs).firstOrNull { it.id == id }
        val profile = PrivateProviderProfile(
            id = id,
            name = existing?.name ?: inferName(base),
            baseUrl = base,
            apiKey = apiKey.trim(),
            model = model.trim(),
            models = (models + model).map(String::trim).filter(String::isNotBlank).distinct()
        )
        val all = load(prefs).filterNot { it.id == id } + profile
        persist(prefs, all)
        select(prefs, id)
        return profile
    }

    fun delete(prefs: SharedPreferences, id: String): List<PrivateProviderProfile> {
        val remaining = load(prefs).filterNot { it.id == id }
        persist(prefs, remaining)
        val selected = prefs.getString(SELECTED_KEY, null)
        if (selected == id) {
            prefs.edit().putString(SELECTED_KEY, remaining.firstOrNull()?.id).apply()
        }
        return remaining
    }

    private fun persist(prefs: SharedPreferences, profiles: List<PrivateProviderProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("apiKey", profile.apiKey)
                    .put("model", profile.model)
                    .put("models", JSONArray(profile.models))
            )
        }
        prefs.edit().putString(PROFILES_KEY, array.toString()).apply()
    }
}
