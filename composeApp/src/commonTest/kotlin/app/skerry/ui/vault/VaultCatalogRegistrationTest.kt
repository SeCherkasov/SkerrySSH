package app.skerry.ui.vault

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultCatalogRegistrationTest {
    @Test
    fun `a disposed UI projection cannot publish and a new owner can register`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = object : Vault by FakeVault(exists = true) { override val isUnlocked = true }
        val reload = VaultCatalogReloader(vault, emptyList(), dispatcher, dispatcher)
        var published = 0
        var unregister: () -> Unit = {}
        unregister = reload.register(listOf(VaultCatalogReload(setOf(RecordType.SETTINGS)) {
            unregister() // composition leaves while decryption is in flight
            val publish: () -> Unit = { published++ }
            publish
        }))
        assertTrue(reload.reload(setOf(RecordType.SETTINGS)))
        assertEquals(0, published)

        val next = reload.register(listOf(VaultCatalogReload(setOf(RecordType.SETTINGS)) {
            val publish: () -> Unit = { published++ }
            publish
        }))
        assertTrue(reload.reload(setOf(RecordType.HOST)))
        assertEquals(0, published)
        assertTrue(reload.reload(setOf(RecordType.SETTINGS)))
        assertEquals(1, published)
        next()
        next() // disposal is idempotent
        reload.reload(setOf(RecordType.SETTINGS))
        assertEquals(1, published)
    }
}
