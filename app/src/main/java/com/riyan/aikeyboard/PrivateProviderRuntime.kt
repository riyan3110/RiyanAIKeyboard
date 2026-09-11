package com.riyan.aikeyboard

internal data class PrivateProviderExecution<T>(
    val value: T,
    val profile: PrivateProviderProfile
)

/**
 * PRIVATE-only routing policy for dynamically saved providers.
 *
 * Fallback OFF: only the selected provider is ever attempted.
 * Fallback ON: selected provider first, then only the other providers still present
 * in PrivateProviderStore. Hard-coded/legacy providers are deliberately excluded.
 */
internal object PrivateProviderRuntime {
    fun candidates(
        profiles: List<PrivateProviderProfile>,
        selectedId: String?,
        fallbackEnabled: Boolean
    ): List<PrivateProviderProfile> {
        if (profiles.isEmpty()) return emptyList()
        val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        if (!fallbackEnabled) return listOf(selected)
        return buildList {
            add(selected)
            profiles.filterNot { it.id == selected.id }.forEach(::add)
        }
    }

    fun <T> execute(
        profiles: List<PrivateProviderProfile>,
        selectedId: String?,
        fallbackEnabled: Boolean,
        request: (PrivateProviderProfile) -> Result<T>
    ): Result<PrivateProviderExecution<T>> {
        val ordered = candidates(profiles, selectedId, fallbackEnabled)
        if (ordered.isEmpty()) {
            return Result.failure(
                IllegalStateException("Belum ada provider PRIVATE yang tersimpan. Tambahkan Base URL dan API Key terlebih dahulu.")
            )
        }

        var lastError: Throwable? = null
        for (profile in ordered) {
            val result = runCatching { request(profile) }
                .getOrElse { Result.failure(it) }
            result.getOrNull()?.let { value ->
                return Result.success(PrivateProviderExecution(value, profile))
            }
            val cause = result.exceptionOrNull()
            val message = cause?.message
                ?.replace("xKiro", profile.name, ignoreCase = true)
                ?.replace("XKIRO", profile.name, ignoreCase = true)
                ?.ifBlank { null }
                ?: "Provider gagal merespons."
            lastError = IllegalStateException("${profile.name}: $message", cause)
            if (!fallbackEnabled) break
        }

        return Result.failure(lastError ?: IllegalStateException("Provider gagal merespons."))
    }
}

internal data class PrivateProviderAiResponse(
    val text: String,
    val profile: PrivateProviderProfile
)
