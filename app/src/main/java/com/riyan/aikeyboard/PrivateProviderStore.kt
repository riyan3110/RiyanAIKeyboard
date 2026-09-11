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
    private const val MIGRATION_DONE_KEY = "private_provider_migration_done_v2"

    // These keys belong to the old hard-coded provider system. Once the PRIVATE dynamic
    // provider store has been initialized, they must never be used as a second source of truth.
    private val LEGACY_PROVIDER_KEYS = arrayOf(
        "provider",
        "api_key",
        "openrouter_api_key",
        "openrouter_model",
        "tabi_api_key",
        "tabi_base_url",
        "tabi_model",
        "9router_api_key",
        "9router_base_url",
        "9router_model",
        "bluesminds_api_key",
        "bluesminds_base_url",
        "bluesminds_model",
        "bai_api_key",
        "bai_base_url",
        "bai_model",
        "vyceai_api_key",
        "vyceai_base_url",
        "vyceai_model",
        "agentrouter_api_key",
        "agentrouter_base_url",
        "agentrouter_model",
        "seekai_api_key",
        "seekai_base_url",
        "seekai_model",
        "xkiro_api_key",
        "xkiro_base_url",
        "xkiro_model",
        "orcarouter_api_key",
        "orcarouter_base_url",
        "orcarouter_model",
        "aihorde_api_key",
        "aihorde_model"
    )

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
        val parts = host.split('.').filter(String::isNotBlank)
        val commonPrefixes = setOf("api", "gateway", "chat", "openai")
        val stem = when {
            parts.size >= 2 && parts.first().lowercase() in commonPrefixes -> parts[1]
            parts.isNotEmpty() -> parts.first()
            else -> host
        }.lowercase()
        return when (stem) {
            "openrouter" -> "OpenRouter"
            "bluesminds" -> "BluesMinds"
            "xkiro" -> "xKiro"
            "orcarouter" -> "OrcaRouter"
            "agentrouter" -> "AgentRouter"
            "seekai" -> "SeekAI"
            "vyceai" -> "VyceAI"
            "b" -> "B.AI"
            else -> stem.split('-', '_').joinToString(" ") { part ->
                part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            }.ifBlank { host }
        }
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
        val current = load(prefs)
        if (current.isNotEmpty()) {
            prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).commit()
            purgeLegacyProviderState(prefs)
            return current
        }
        // Migration is one-shot. An empty dynamic list after this flag is set means the user
        // intentionally deleted every provider; old credentials must never resurrect them.
        if (prefs.getBoolean(MIGRATION_DONE_KEY, false)) return emptyList()

        data class Legacy(
            val providerId: String,
            val name: String,
            val defaultBaseUrl: String,
            val keyPref: String,
            val basePref: String?,
            val modelPref: String,
            val defaultModel: String = ""
        )

        val selectedLegacy = prefs.getString("provider", "openrouter").orEmpty()
        val legacy = listOf(
            Legacy("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter_api_key", null, "openrouter_model", "openrouter/free"),
            Legacy("tabiai", "TabiAI", "https://tabitoken.com", "tabi_api_key", "tabi_base_url", "tabi_model"),
            Legacy("9router", "9Router", "http://43.159.50.231:20130/v1", "9router_api_key", "9router_base_url", "9router_model", "cc/claude-sonnet-4-20250514"),
            Legacy("bluesminds", "BluesMinds", "https://api.bluesminds.com/v1", "bluesminds_api_key", "bluesminds_base_url", "bluesminds_model", "deepseek-ai/deepseek-v4-flash"),
            Legacy("bai", "B.AI", "https://api.b.ai/v1", "bai_api_key", "bai_base_url", "bai_model", "gpt-5.2"),
            Legacy("vyceai", "VyceAI", "https://vyceai.com/v1", "vyceai_api_key", "vyceai_base_url", "vyceai_model", "gpt-5.6-luna"),
            Legacy("agentrouter", "AgentRouter", "https://co.agentrouter.org/v1", "agentrouter_api_key", "agentrouter_base_url", "agentrouter_model", "glm-5.3"),
            Legacy("seekai", "SeekAI", "https://seekai.cc/v1", "seekai_api_key", "seekai_base_url", "seekai_model", "gpt-5.6-sol"),
            Legacy("xkiro", "xKiro", "https://api.xkiro.com/v1", "xkiro_api_key", "xkiro_base_url", "xkiro_model", "openai/gpt-5.6-sol"),
            Legacy("orcarouter", "OrcaRouter", "https://api.orcarouter.ai/v1", "orcarouter_api_key", "orcarouter_base_url", "orcarouter_model", "orcarouter/free")
        )

        val imported = legacy.mapNotNull { old ->
            val key = prefs.getString(old.keyPref, "").orEmpty()
            val baseUrl = old.basePref?.let { prefs.getString(it, old.defaultBaseUrl).orEmpty() } ?: old.defaultBaseUrl
            val model = prefs.getString(old.modelPref, old.defaultModel).orEmpty()
            if (baseUrl.isBlank() || (key.isBlank() && old.providerId != selectedLegacy)) return@mapNotNull null
            PrivateProviderProfile(
                id = providerId(baseUrl),
                name = old.name,
                baseUrl = normalizeBaseUrl(baseUrl),
                apiKey = key,
                model = model,
                models = listOf(model).filter(String::isNotBlank)
            )
        }.distinctBy { it.id }

        if (imported.isNotEmpty()) {
            persist(prefs, imported)
            val selected = legacy.firstOrNull { it.providerId == selectedLegacy }
                ?.let { old ->
                    val base = old.basePref?.let { prefs.getString(it, old.defaultBaseUrl).orEmpty() } ?: old.defaultBaseUrl
                    imported.firstOrNull { it.baseUrl == normalizeBaseUrl(base) }
                }
                ?: imported.first()
            select(prefs, selected.id)
        }
        prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).commit()
        purgeLegacyProviderState(prefs)
        return imported
    }

    fun selected(prefs: SharedPreferences, profiles: List<PrivateProviderProfile> = load(prefs)): PrivateProviderProfile? {
        val id = prefs.getString(SELECTED_KEY, null)
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun select(prefs: SharedPreferences, id: String) {
        if (load(prefs).none { it.id == id }) return
        prefs.edit().putString(SELECTED_KEY, id).commit()
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
        prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).commit()
        purgeLegacyProviderState(prefs)
        return profile
    }

    fun delete(prefs: SharedPreferences, id: String): List<PrivateProviderProfile> {
        val remaining = load(prefs).filterNot { it.id == id }
        persist(prefs, remaining)
        val selected = prefs.getString(SELECTED_KEY, null)
        if (selected == id) {
            val editor = prefs.edit()
            remaining.firstOrNull()?.let { editor.putString(SELECTED_KEY, it.id) }
                ?: editor.remove(SELECTED_KEY)
            editor.commit()
        }
        prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).commit()
        purgeLegacyProviderState(prefs)
        return remaining
    }

    fun purgeLegacyProviderState(prefs: SharedPreferences) {
        val editor = prefs.edit()
        LEGACY_PROVIDER_KEYS.forEach { key -> editor.remove(key) }
        editor.commit()
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
        prefs.edit().putString(PROFILES_KEY, array.toString()).commit()
    }
}
