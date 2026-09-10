package com.riyan.aikeyboard

import android.content.Context
import org.junit.Assert.assertEquals
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

    @Test fun migratesExistingPrivateProviderSettingsWithoutErasingLegacyPrefs() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("private-provider-migration-test", Context.MODE_PRIVATE)
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
        assertEquals("old-secret", prefs.getString("bai_api_key", ""))
    }
}
