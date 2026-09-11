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
class PrivateProviderStoreTest {
    @Test fun normalizesBaseUrlsAndBuildsModelEndpoint() {
        assertEquals(
            "https://api.example.com/v1",
            PrivateProviderStore.normalizeBaseUrl("https://api.example.com/v1/chat/completions/")
        )
        assertEquals(
            listOf("https://api.example.com/v1/models"),
            PrivateProviderStore.modelEndpoints("https://api.example.com/v1")
        )
        assertEquals(
            listOf("https://example.com/models", "https://example.com/v1/models"),
            PrivateProviderStore.modelEndpoints("https://example.com")
        )
    }

    @Test fun parsesCommonProviderModelPayloadsWithoutDroppingModels() {
        assertEquals(
            listOf("alpha", "beta/model", "zeta"),
            PrivateProviderStore.parseModels(
                """{"data":[{"id":"zeta"},{"id":"beta/model"},{"id":"alpha"}]}"""
            )
        )
        assertEquals(
            listOf("model-a", "model-b"),
            PrivateProviderStore.parseModels("""{"models":["model-b","model-a"]}""")
        )
        assertEquals(
            listOf("one", "two"),
            PrivateProviderStore.parseModels("""{"result":{"data":[{"name":"two"},{"model":"one"}]}}""")
        )
    }

    @Test fun providerNameSkipsApiSubdomain() {
        assertEquals("xKiro", PrivateProviderStore.inferName("https://api.xkiro.com/v1"))
        assertEquals("B.AI", PrivateProviderStore.inferName("https://api.b.ai/v1"))
        assertEquals("OpenRouter", PrivateProviderStore.inferName("https://openrouter.ai/api/v1"))
    }

    @Test fun migrationMovesLegacyProvidersThenErasesLegacyCredentials() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("private-provider-migration-v2-test", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("provider", "bai")
            .putString("bai_api_key", "old-secret")
            .putString("bai_base_url", "https://api.b.ai/v1")
            .putString("bai_model", "MiMo-V2.5")
            .putString("agentrouter_api_key", "agent-key")
            .putString("agentrouter_base_url", "https://co.agentrouter.org/v1")
            .putString("agentrouter_model", "glm-test")
            .commit()

        val migrated = PrivateProviderStore.ensureMigrated(prefs)
        val selected = PrivateProviderStore.selected(prefs, migrated)

        assertTrue(migrated.any { it.name == "B.AI" && it.apiKey == "old-secret" && it.model == "MiMo-V2.5" })
        assertTrue(migrated.any { it.name == "AgentRouter" && it.apiKey == "agent-key" && it.model == "glm-test" })
        assertEquals("B.AI", selected?.name)
        assertFalse(prefs.contains("provider"))
        assertFalse(prefs.contains("bai_api_key"))
        assertFalse(prefs.contains("agentrouter_api_key"))
    }

    @Test fun deletingEveryProviderCannotResurrectLegacyProvider() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("private-provider-delete-root-test", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("provider", "xkiro")
            .putString("xkiro_api_key", "old-xkiro-key")
            .putString("xkiro_base_url", "https://api.xkiro.com/v1")
            .putString("xkiro_model", "old-model")
            .commit()

        val migrated = PrivateProviderStore.ensureMigrated(prefs)
        assertEquals(1, migrated.size)
        val removed = PrivateProviderStore.delete(prefs, migrated.single().id)

        assertTrue(removed.isEmpty())
        assertTrue(PrivateProviderStore.load(prefs).isEmpty())
        assertTrue(PrivateProviderStore.ensureMigrated(prefs).isEmpty())
        assertFalse(prefs.contains("xkiro_api_key"))
        assertFalse(prefs.contains("xkiro_base_url"))
        assertFalse(prefs.contains("xkiro_model"))
        assertFalse(prefs.contains("provider"))
    }

    @Test fun fallbackOffUsesOnlySelectedDynamicProvider() {
        val one = profile("one")
        val two = profile("two")
        val three = profile("three")
        assertEquals(
            listOf(two),
            PrivateProviderRuntime.candidates(listOf(one, two, three), two.id, fallbackEnabled = false)
        )
    }

    @Test fun fallbackOnUsesSelectedThenOnlyRemainingSavedProviders() {
        val one = profile("one")
        val two = profile("two")
        val three = profile("three")
        assertEquals(
            listOf(two, one, three),
            PrivateProviderRuntime.candidates(listOf(one, two, three), two.id, fallbackEnabled = true)
        )
    }

    @Test fun fallbackExecutionNeverTouchesProviderThatIsNotInSavedList() {
        val selected = profile("selected")
        val fallback = profile("fallback")
        val attempts = mutableListOf<String>()

        val result = PrivateProviderRuntime.execute(
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

    private fun profile(name: String) = PrivateProviderProfile(
        id = name,
        name = name,
        baseUrl = "https://$name.example/v1",
        apiKey = "key-$name",
        model = "model-$name",
        models = listOf("model-$name")
    )
}
