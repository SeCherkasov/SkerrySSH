package app.skerry.ui.vault

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultCatalogReloaderTest {
    @Test
    fun `reload prepares only affected catalogs and publishes on the UI dispatcher`() = runTest {
        var currentDispatcher = ""
        val delegate = StandardTestDispatcher(testScheduler)
        fun marked(name: String) = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegate.dispatch(context) {
                    currentDispatcher = name
                    try { block.run() } finally { currentDispatcher = "" }
                }
            }
        }
        val background = marked("storage")
        val ui = marked("UI")
        val calls = mutableListOf<String>()
        val vault = MutableVault()
        fun catalog(type: RecordType) = VaultCatalogReload(setOf(type)) {
            calls += "prepare-$type"
            assertEquals("storage", currentDispatcher)
            val publish: () -> Unit = {
                assertEquals("UI", currentDispatcher)
                calls += "publish-$type"
            }
            publish
        }
        val reload = VaultCatalogReloader(vault, listOf(catalog(RecordType.HOST), catalog(RecordType.SNIPPET)), background, ui)
        val job = launch { reload.reload(setOf(RecordType.SNIPPET)) }
        assertEquals(emptyList(), calls)
        testScheduler.runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf("prepare-SNIPPET", "publish-SNIPPET"), calls)
    }

    @Test
    fun `lock during preparation discards decrypted projection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = MutableVault()
        var published = false
        val reload = VaultCatalogReloader(vault, listOf(VaultCatalogReload(setOf(RecordType.HOST)) {
            vault.lock()
            val publish: () -> Unit = { published = true }
            publish
        }), dispatcher, dispatcher)
        assertFalse(reload.reload(setOf(RecordType.HOST)))
        assertFalse(published)
    }

    @Test
    fun `a write during preparation retries instead of overwriting newer UI state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = MutableVault()
        var prepared = 0
        var published = 0L
        val reload = VaultCatalogReloader(vault, listOf(VaultCatalogReload(setOf(RecordType.HOST)) {
            val value = vault.revision
            if (prepared++ == 0) vault.revision++
            val publish: () -> Unit = { published = value }
            publish
        }), dispatcher, dispatcher)
        assertTrue(reload.reload(setOf(RecordType.HOST)))
        assertEquals(2, prepared)
        assertEquals(vault.revision, published)
    }

    @Test
    fun `a lock between the unlocked read and revision capture cannot complete unlock`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = object : Vault by FakeVault(exists = true) {
            override var revision = 1L
            private var locked = false
            override val isUnlocked: Boolean get() {
                if (locked) return false
                // The read observed an open session, but the UI locks before the next instruction.
                locked = true
                revision++
                return true
            }
        }
        var published = false
        val reload = VaultCatalogReloader(vault, listOf(VaultCatalogReload(setOf(RecordType.HOST)) {
            val publish: () -> Unit = { published = true }
            publish
        }), dispatcher, dispatcher)
        assertFalse(reload.reload(setOf(RecordType.HOST)))
        assertFalse(published)
    }

    @Test
    fun `cancellation releases reload guard and the next request succeeds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = MutableVault()
        var cancel = true
        var published = 0
        val reload = VaultCatalogReloader(vault, listOf(VaultCatalogReload(setOf(RecordType.HOST)) {
            if (cancel) throw CancellationException("cancelled")
            val publish: () -> Unit = { published++ }
            publish
        }), dispatcher, dispatcher)
        val job = launch { reload.reload(setOf(RecordType.HOST)) }
        testScheduler.runCurrent()
        assertTrue(job.isCancelled)
        cancel = false
        assertTrue(reload.reload(setOf(RecordType.HOST)))
        assertEquals(1, published)
    }

    private class MutableVault : Vault by FakeVault(exists = true) {
        override var revision = 1L
        override var isUnlocked = true
        override fun lock() { revision++; isUnlocked = false }
    }
}
