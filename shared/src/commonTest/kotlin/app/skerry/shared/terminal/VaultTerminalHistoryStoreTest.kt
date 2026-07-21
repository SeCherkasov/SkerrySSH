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
    fun `all returns every host's history for cross-host search`() {
        val vault = FakeVault()
        val store = VaultTerminalHistoryStore(vault)
        store.save("h1", listOf("uptime"), label = "root@alpha")
        store.save("h2", listOf("whoami"))

        val all = store.all()

        assertEquals(setOf("h1", "h2"), all.map { it.key }.toSet())
        assertEquals("root@alpha", all.single { it.key == "h1" }.label)
        assertEquals(null, all.single { it.key == "h2" }.label)
    }

    @Test
    fun `all is empty on a locked vault`() {
        val vault = FakeVault()
        VaultTerminalHistoryStore(vault).save("h1", listOf("uptime"))
        vault.locked = true

        assertEquals(emptyList(), VaultTerminalHistoryStore(vault).all())
    }

    @Test
    fun `saving without a label keeps the one already stored`() {
        val vault = FakeVault()
        val store = VaultTerminalHistoryStore(vault)
        store.save("h1", listOf("uptime"), label = "root@alpha")

        store.save("h1", listOf("ls", "uptime"))

        assertEquals("root@alpha", store.all().single().label)
    }

    @Test
    fun `terminal history never syncs regardless of flags`() {
        assertFalse(SyncSettings(syncHosts = true, syncSnippets = true).shouldSync(RecordType.TERMINAL_HISTORY))
        assertTrue(SyncSettings().shouldSync(RecordType.SETTINGS)) // control: settings still syncs
    }
}
