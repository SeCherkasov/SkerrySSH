package app.skerry.shared.vault

import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileVaultBatchTest {
    @Test
    fun `host batch and layout persist once and wake sync once`() = runTest {
        initializeVaultCrypto()
        val fs = FakeFileSystem()
        val crypto = IonspinVaultCrypto()
        var writes = 0
        val vault = FileVault("/vault".toPath(), crypto, "device", fs, harden = { writes++ }, now = { TS })
        vault.createWithDataKey(crypto.newDataKey())
        var signals = 0
        val observer = backgroundScope.launch { vault.localChanges.collect { signals++ } }
        runCurrent()
        writes = 0
        val store = VaultHostStore(vault)
        store.putAll(listOf(host("a"), host("b"), host("a").copy(label = "Updated")))
        runCurrent()
        assertEquals(1, writes)
        assertEquals(1, signals)
        assertEquals(listOf("a", "b"), store.all().map { it.id })
        assertEquals("Updated", store.all().first().label)
        assertEquals(2L, vault.records().first { it.id == "a" }.version)
        assertEquals(listOf("a", "b"), WorkspaceLayoutStore(vault).read().hostOrder)
        val key = vault.exportDataKey()!!
        vault.lock()
        assertEquals(UnlockResult.Success, vault.unlockWithDataKey(key))
        assertEquals(listOf("a", "b"), store.all().map { it.id })
        observer.cancel()
    }

    @Test
    fun `failed batch preserves disk index and local change signal`() = runTest {
        initializeVaultCrypto()
        val fs = FakeFileSystem()
        val crypto = IonspinVaultCrypto()
        var fail = false
        val path = "/vault".toPath()
        val vault = FileVault(path, crypto, "device", fs, harden = { check(!fail) }, now = { TS })
        vault.createWithDataKey(crypto.newDataKey())
        vault.put("a", RecordType.HOST, byteArrayOf(1))
        val disk = fs.read(path) { readUtf8() }
        var signals = 0
        backgroundScope.launch { vault.localChanges.collect { signals++ } }
        runCurrent()
        fail = true
        assertFailsWith<IllegalStateException> {
            vault.putAll(listOf(write("a", 2), write("b", 3)))
        }
        runCurrent()
        assertEquals(disk, fs.read(path) { readUtf8() })
        assertEquals(0, signals)
        assertContentEquals(byteArrayOf(1), vault.openPayload("a"))
        assertNull(vault.openPayload("b"))
        fail = false
        vault.putAll(listOf(write("b", 4)))
        assertContentEquals(byteArrayOf(4), vault.openPayload("b"))
    }

    @Test
    fun `batch refuses type substitution including an earlier write in the batch`() = runTest {
        initializeVaultCrypto()
        val fs = FakeFileSystem()
        val crypto = IonspinVaultCrypto()
        val vault = FileVault("/vault".toPath(), crypto, "device", fs, now = { TS })
        vault.createWithDataKey(crypto.newDataKey())
        assertFailsWith<IllegalArgumentException> {
            vault.putAll(listOf(write("a", 1), VaultWrite("a", RecordType.CREDENTIAL, byteArrayOf(2))))
        }
        assertEquals(emptyList(), vault.records())
        vault.put("pin", RecordType.TEAM_PEER, byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { vault.putAll(listOf(write("a", 1), write("pin", 2))) }
        assertNull(vault.openPayload("a"))
        assertEquals(RecordType.TEAM_PEER, vault.records().single().type)
    }

    @Test
    fun `batch leaves unreadable layout and its groups intact`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        store.put(host("existing"))
        val layout = WorkspaceLayoutStore(vault)
        layout.updateGroups(listOf("prod"), listOf("windows"))
        val before = layout.read()
        vault.unreadable += WorkspaceLayoutStore.LAYOUT_ID
        store.putAll(listOf(host("a"), host("b")))
        vault.unreadable.clear()
        assertEquals(before, layout.read())
        assertEquals(listOf("existing", "a", "b"), store.all().map { it.id })
    }

    @Test
    fun `empty batch does not rewrite the vault`() = runTest {
        initializeVaultCrypto()
        val fs = FakeFileSystem()
        val crypto = IonspinVaultCrypto()
        var writes = 0
        val vault = FileVault("/vault".toPath(), crypto, "device", fs, harden = { writes++ }, now = { TS })
        vault.createWithDataKey(crypto.newDataKey())
        writes = 0
        VaultHostStore(vault).putAll(emptyList())
        assertEquals(0, writes)
    }

    @Test
    fun `index tracks remote duplicates compaction clear and reopen`() = runTest {
        initializeVaultCrypto()
        val fs = FakeFileSystem()
        val crypto = IonspinVaultCrypto()
        val vault = FileVault("/vault".toPath(), crypto, "device", fs, now = { TS })
        val key = crypto.newDataKey()
        vault.createWithDataKey(key)
        fun remote(version: Long, payload: Int): VaultRecord = VaultRecord(
            "a", RecordType.HOST, version, TS, "peer", false,
            crypto.seal(key, byteArrayOf(payload.toByte()), recordAad("a", RecordType.HOST, version, "peer", false, TS)),
        )
        val first = remote(1, 1)
        val winner = remote(2, 2)
        val forged = winner.copy(version = 3)
        val result = vault.mergeRemote(listOf(first, winner, first, forged))
        assertEquals(listOf(first, winner), result.applied)
        assertEquals(listOf(forged), result.rejected)
        assertContentEquals(byteArrayOf(2), vault.openPayload("a"))
        vault.put("b", RecordType.HOST, byteArrayOf(3))
        vault.remove("a")
        vault.compact(listOf("a"))
        assertContentEquals(byteArrayOf(3), vault.openPayload("b"))
        vault.clearRecords(setOf(RecordType.HOST))
        assertNull(vault.openPayload("b"))
        vault.put("c", RecordType.HOST, byteArrayOf(4))
        val reopenKey = vault.exportDataKey()!!
        vault.lock()
        vault.unlockWithDataKey(reopenKey)
        assertContentEquals(byteArrayOf(4), vault.openPayload("c"))
        vault.reset()
        vault.createWithDataKey(crypto.newDataKey())
        assertNull(vault.openPayload("c"))
    }

    private fun host(id: String) = Host(id, id, "example.com", 22, "root")
    private fun write(id: String, value: Int) = VaultWrite(id, RecordType.HOST, byteArrayOf(value.toByte()))
    private companion object { const val TS = "2026-10-08T12:00:00Z" }
}
