package app.skerry.ui.sync

import app.skerry.shared.sync.SyncOutcome
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.ui.vault.FakeVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncedCatalogRefreshTest {
    @Test
    fun `an ordinary cycle refreshes only applied record types`() = runTest {
        val calls = mutableListOf<Set<RecordType>>()
        val refresh = SyncedCatalogRefresh(MutableVault()) { calls += it }
        val outcome = refresh.sync { outcome(setOf(RecordType.SNIPPET, RecordType.LIBRARY_ORDER)) }
        refresh.refresh()
        assertEquals(listOf(outcome.changedTypes), calls)
        refresh.sync { outcome(emptySet()) }
        refresh.refresh()
        assertEquals(1, calls.size)
    }

    @Test
    fun `key adoption or reconciliation refreshes all catalogs even with an empty pull`() = runTest {
        val calls = mutableListOf<Set<RecordType>>()
        val refresh = SyncedCatalogRefresh(MutableVault()) { calls += it }
        refresh.invalidateAll()
        val result = refresh.sync { outcome(emptySet()) }
        assertEquals(RecordType.entries.toSet(), result.changedTypes)
        refresh.refresh()
        assertEquals(listOf(RecordType.entries.toSet()), calls)
    }

    @Test
    fun `a pull committed before a failed push is refreshed by the next empty cycle`() = runTest {
        val vault = MutableVault()
        val calls = mutableListOf<Set<RecordType>>()
        val refresh = SyncedCatalogRefresh(vault) { calls += it }
        assertFailsWith<IllegalStateException> {
            refresh.sync { vault.revision++; error("push failed") }
        }
        refresh.sync { outcome(emptySet()) }
        refresh.refresh()
        assertEquals(listOf(RecordType.entries.toSet()), calls)
    }

    @Test
    fun `a failed publication remains owed and cancellation propagates`() = runTest {
        var cancel = true
        val calls = mutableListOf<Set<RecordType>>()
        val refresh = SyncedCatalogRefresh(MutableVault()) {
            if (cancel) throw CancellationException("locked")
            calls += it
        }
        refresh.sync { outcome(setOf(RecordType.CREDENTIAL)) }
        assertFailsWith<CancellationException> { refresh.refresh() }
        cancel = false
        val next = refresh.sync { outcome(emptySet()) }
        assertEquals(setOf(RecordType.CREDENTIAL), next.changedTypes)
        refresh.refresh()
        assertEquals(listOf(setOf(RecordType.CREDENTIAL)), calls)
    }

    private fun outcome(types: Set<RecordType>) = SyncOutcome(types.size, 0, 0, changedTypes = types)
    private class MutableVault : Vault by FakeVault(exists = true) { override var revision = 0L }
}
