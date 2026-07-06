package app.skerry.shared.terminal

import app.skerry.shared.sync.SyncSettings
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultTerminalHistoryStoreTest {

    @Test
    fun `save then load round-trips newest first`() {
        val store = VaultTerminalHistoryStore(FakeVault())
        store.save("h1", listOf("git push", "ls", "cd /tmp"))
        assertEquals(listOf("git push", "ls", "cd /tmp"), store.load("h1"))
    }

    @Test
    fun `history is isolated per key`() {
        val vault = FakeVault()
        VaultTerminalHistoryStore(vault).save("h1", listOf("uptime"))
        VaultTerminalHistoryStore(vault).save("h2", listOf("whoami"))
        assertEquals(listOf("uptime"), VaultTerminalHistoryStore(vault).load("h1"))
        assertEquals(listOf("whoami"), VaultTerminalHistoryStore(vault).load("h2"))
    }

    @Test
    fun `save upserts the same record per key`() {
        val vault = FakeVault()
        val store = VaultTerminalHistoryStore(vault)
        store.save("h1", listOf("a"))
        store.save("h1", listOf("b", "a"))
        assertEquals(listOf("b", "a"), store.load("h1"))
        // Exactly one history record per key (upsert, not accumulation).
        assertEquals(1, vault.records().count { it.type == RecordType.TERMINAL_HISTORY && !it.deleted })
    }

    @Test
    fun `caps stored commands`() {
        val store = VaultTerminalHistoryStore(FakeVault(), cap = 2)
        store.save("h1", listOf("c3", "c2", "c1"))
        assertEquals(listOf("c3", "c2"), store.load("h1")) // tail dropped
    }

    @Test
    fun `unknown key loads empty`() {
        assertEquals(emptyList(), VaultTerminalHistoryStore(FakeVault()).load("nope"))
    }

    @Test
    fun `terminal history never syncs regardless of flags`() {
        assertFalse(SyncSettings(syncHosts = true, syncSnippets = true).shouldSync(RecordType.TERMINAL_HISTORY))
        assertTrue(SyncSettings().shouldSync(RecordType.SETTINGS)) // control: settings still syncs
    }
}
