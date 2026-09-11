package com.riyan.aikeyboard

internal data class PublicProviderExecution<T>(
    val value: T,
    val profile: PublicProviderProfile
)

/**
 * PUBLIC-only routing policy for dynamically saved providers.
 *
 * Fallback OFF: only the selected provider is attempted.
 * Fallback ON: selected provider first, then only other profiles still saved
 * in PublicProviderStore. Legacy hard-coded providers are never candidates.
 */
internal object PublicProviderRuntime {
    fun candidates(
        profiles: List<PublicProviderProfile>,
        selectedId: String?,
        fallbackEnabled: Boolean
    ): List<PublicProviderProfile> {
        if (profiles.isEmpty()) return emptyList()
        val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        if (!fallbackEnabled) return listOf(selected)
        return buildList {
            add(selected)
            profiles.filterNot { it.id == selected.id }.forEach(::add)
        }
    }

    fun <T> execute(
        profiles: List<PublicProviderProfile>,
        selectedId: String?,
        fallbackEnabled: Boolean,
        request: (PublicProviderProfile) -> Result<T>
    ): Result<PublicProviderExecution<T>> {
        val ordered = candidates(profiles, selectedId, fallbackEnabled)
        if (ordered.isEmpty()) {
            return Result.failure(
                IllegalStateException("Belum ada provider PUBLIC yang tersimpan. Tambahkan Base URL dan API Key terlebih dahulu.")
            )
        }

        var lastError: Throwable? = null
        for (profile in ordered) {
            val result = runCatching { request(profile) }
                .getOrElse { Result.failure(it) }
            result.getOrNull()?.let { value ->
                return Result.success(PublicProviderExecution(value, profile))
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

internal data class PublicProviderAiResponse(
    val text: String,
    val profile: PublicProviderProfile
)
