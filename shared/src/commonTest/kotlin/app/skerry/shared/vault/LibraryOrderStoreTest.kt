package app.skerry.shared.vault

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.VaultRunbookStore
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.VaultSnippetStore
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The two libraries share one order record, so each has to leave the other's list where it is. */
class LibraryOrderStoreTest {

    @Test
    fun `reordering one library keeps the order of the other`() {
        val vault = FakeVault()
        val runbooks = VaultRunbookStore(vault)
        runbooks.put(Runbook(id = "r1", label = "Drain"))
        runbooks.put(Runbook(id = "r2", label = "Deploy"))
        runbooks.reorder { it.reversed() }

        val snippets = VaultSnippetStore(vault)
        snippets.put(Snippet(id = "s1", label = "Disk", command = "df -h"))
        snippets.put(Snippet(id = "s2", label = "Load", command = "uptime"))
        snippets.reorder { it.reversed() }
        snippets.remove("s2")

        val order = LibraryOrderStore(vault).read()
        assertEquals(listOf("r2", "r1"), order.runbooks, "the snippet library rewrote the runbook order")
        assertEquals(listOf("s1"), order.snippets)
    }

    @Test
    fun `saving into either library writes no order record`() {
        val vault = FakeVault()
        VaultSnippetStore(vault).put(Snippet(id = "s1", label = "Disk", command = "df -h"))
        VaultRunbookStore(vault).put(Runbook(id = "r1", label = "Drain"))

        assertEquals(
            emptyList(),
            vault.records().filter { it.type == RecordType.LIBRARY_ORDER }.map { it.id },
            "an untouched library wrote an order record",
        )
    }

    /**
     * One record holds both lists, so LWW resolves them together: two devices reordering different
     * libraries offline do not merge, the newer record wins whole. The cost is meant to be the loser
     * reverting to the winner's view of its list — never a list that comes back short, empty, or
     * carrying the other library's ids.
     */
    @Test
    fun `a newer order from another device replaces both lists rather than merging them`() = runTest {
        initializeVaultCrypto()
        val crypto = IonspinVaultCrypto()
        val fs = FakeFileSystem()
        val a = FileVault("/a.json".toPath(), crypto, "device-a", fs) { TS }
            .apply { create("master".toCharArray()) }
        val b = FileVault("/b.json".toPath(), crypto, "device-b", fs) { TS }
            .apply { createWithDataKey(a.exportDataKey()!!) }

        val snippetsA = VaultSnippetStore(a)
        listOf("s1", "s2", "s3").forEach { snippetsA.put(Snippet(id = it, label = it, command = "true")) }
        val runbooksA = VaultRunbookStore(a)
        listOf("r1", "r2").forEach { runbooksA.put(Runbook(id = it, label = it)) }
        // Both devices start from the same order record, runbooks included — the winner has to carry
        // a runbook order of its own, or the assertion below cannot tell "the winner's list" from
        // "no list at all, so record order".
        runbooksA.reorder { it.reversed() }
        b.mergeRemote(a.records())

        // Device A reorders its snippets twice, so its record is at version 3 against B's 2 and the
        // winner is fixed by version — the deviceId tie-break would have picked B.
        snippetsA.reorder { it.reversed() }
        snippetsA.reorder { listOf(it[1], it[0], it[2]) }
        // Device B, offline, reorders the other library — the half LWW is about to drop.
        VaultRunbookStore(b).reorder { it.reversed() }

        b.mergeRemote(a.records())

        assertEquals(listOf("s2", "s3", "s1"), VaultSnippetStore(b).all().map { it.id })
        assertEquals(
            listOf("r2", "r1"),
            VaultRunbookStore(b).all().map { it.id },
            "the losing library must fall back to the winner's order, not lose its entries",
        )
    }

    private companion object {
        const val TS = "2026-06-29T00:00:00Z"
    }
}
