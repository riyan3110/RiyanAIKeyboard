package com.riyan.aikeyboard

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class PublicProviderStoreTest {
    @Test fun normalizesHttpsBaseUrlsAndBuildsModelEndpoint() {
        assertEquals(
            "https://api.example.com/v1",
            PublicProviderStore.normalizeBaseUrl("https://api.example.com/v1/chat/completions/")
        )
        assertEquals(
            listOf("https://api.example.com/v1/models"),
            PublicProviderStore.modelEndpoints("https://api.example.com/v1")
        )
    }

    @Test fun parsesCommonProviderModelPayloads() {
        assertEquals(
            listOf("alpha", "beta/model", "zeta"),
            PublicProviderStore.parseModels(
                """{"data":[{"id":"zeta"},{"id":"beta/model"},{"id":"alpha"}]}"""
            )
        )
    }

    @Test fun deletingEveryProviderCannotResurrectLegacyProvider() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("public-provider-delete-root-test", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("provider", "xkiro")
            .putString("xkiro_api_key", "old-xkiro-key")
            .putString("xkiro_base_url", "https://api.xkiro.com/v1")
            .putString("xkiro_model", "old-model")
            .commit()

        val migrated = PublicProviderStore.ensureMigrated(prefs)
        assertEquals(1, migrated.size)
        val removed = PublicProviderStore.delete(prefs, migrated.single().id)

        assertTrue(removed.isEmpty())
        assertTrue(PublicProviderStore.load(prefs).isEmpty())
        assertTrue(PublicProviderStore.ensureMigrated(prefs).isEmpty())
        assertFalse(prefs.contains("xkiro_api_key"))
        assertFalse(prefs.contains("xkiro_base_url"))
        assertFalse(prefs.contains("xkiro_model"))
        assertFalse(prefs.contains("provider"))
    }

    @Test fun deletingOneProviderKeepsTheOthers() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("public-provider-delete-one-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val one = PublicProviderStore.save(prefs, "https://one.example/v1", "key-one", "model-one", listOf("model-one"))
        val two = PublicProviderStore.save(prefs, "https://two.example/v1", "key-two", "model-two", listOf("model-two"))
        val three = PublicProviderStore.save(prefs, "https://three.example/v1", "key-three", "model-three", listOf("model-three"))

        val remaining = PublicProviderStore.delete(prefs, two.id)
        assertEquals(setOf(one.id, three.id), remaining.map { it.id }.toSet())
        assertFalse(remaining.any { it.id == two.id })
    }

    @Test fun fallbackOffUsesOnlySelectedProvider() {
        val one = profile("one")
        val two = profile("two")
        val three = profile("three")
        assertEquals(
            listOf(two),
            PublicProviderRuntime.candidates(listOf(one, two, three), two.id, fallbackEnabled = false)
        )
    }

    @Test fun fallbackOnUsesSelectedThenOnlyOtherSavedProviders() {
        val one = profile("one")
        val two = profile("two")
        val three = profile("three")
        assertEquals(
            listOf(two, one, three),
            PublicProviderRuntime.candidates(listOf(one, two, three), two.id, fallbackEnabled = true)
        )
    }

    @Test fun fallbackExecutionNeverTouchesDeletedOrUnknownProvider() {
        val selected = profile("selected")
        val fallback = profile("fallback")
        val attempts = mutableListOf<String>()

        val result = PublicProviderRuntime.execute(
            profiles = listOf(selected, fallback),
            selectedId = selected.id,
            fallbackEnabled = true
        ) { profile ->
            attempts += profile.name
            if (profile.id == selected.id) {
                Result.failure(IllegalStateException("xKiro mengembalikan respons tanpa teks."))
            } else {
                Result.success("ok")
            }
        }

        assertEquals(listOf("selected", "fallback"), attempts)
        assertEquals("ok", result.getOrNull()?.value)
        assertEquals("fallback", result.getOrNull()?.profile?.name)
    }

    private fun profile(name: String) = PublicProviderProfile(
        id = name,
        name = name,
        baseUrl = "https://$name.example/v1",
        apiKey = "key-$name",
        model = "model-$name",
        models = listOf("model-$name")
    )
}
