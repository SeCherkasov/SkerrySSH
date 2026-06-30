package app.skerry.shared.ai

import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsStoreTest {

    @Test
    fun `empty vault returns unconfigured default`() {
        val settings = AiSettingsStore(FakeVault()).load()

        assertFalse(settings.isConfigured)
        assertEquals("gpt-4o-mini", settings.model)
    }

    @Test
    fun `round trips saved settings`() {
        val vault = FakeVault()
        val store = AiSettingsStore(vault)

        store.save(AiSettings(apiKey = "sk-secret", model = "gpt-4o", baseUrl = "https://proxy.example/v1"))

        val loaded = store.load()
        assertEquals("sk-secret", loaded.apiKey)
        assertEquals("gpt-4o", loaded.model)
        assertEquals("https://proxy.example/v1", loaded.baseUrl)
        assertTrue(loaded.isConfigured)
    }

    @Test
    fun `corrupt payload falls back to default`() {
        val vault = FakeVault()
        vault.put(AiSettingsStore.SETTINGS_ID, RecordType.SETTINGS, "not json".encodeToByteArray())

        assertFalse(AiSettingsStore(vault).load().isConfigured)
    }

    @Test
    fun `isConfigured is false for blank key`() {
        assertFalse(AiSettings(apiKey = "   ").isConfigured)
        assertTrue(AiSettings(apiKey = "sk-x").isConfigured)
    }
}
