package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.trashRecordId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSettingsTest {

    @Test
    fun `default syncs everything except local terminal history`() {
        val s = SyncSettings()
        // TERMINAL_HISTORY is intentionally local (per-host, large, sensitive) and never syncs.
        RecordType.entries.filter { it != RecordType.TERMINAL_HISTORY }
            .forEach { assertTrue(s.shouldSync(it), "default must sync $it") }
        assertFalse(s.shouldSync(RecordType.TERMINAL_HISTORY), "terminal history never syncs")
    }

    @Test
    fun `snippets toggle gates only snippet type`() {
        val s = SyncSettings(syncSnippets = false)
        assertFalse(s.shouldSync(RecordType.SNIPPET))
        assertTrue(s.shouldSync(RecordType.HOST))
        assertTrue(s.shouldSync(RecordType.SETTINGS), "settings record always syncs")
    }

    @Test
    fun `both off syncs only the always-on record types`() {
        val s = SyncSettings(syncHosts = false, syncSnippets = false)
        // TEAM/TEAM_IDENTITY hold team keys and the identity pair; without them another device
        // can't open team vaults at all, so selective sync never gates them (like SETTINGS).
        val alwaysOn = setOf(RecordType.SETTINGS, RecordType.TEAM, RecordType.TEAM_IDENTITY)
        RecordType.entries.filter { it !in alwaysOn }
            .forEach { assertFalse(s.shouldSync(it), "$it must be gated when both off") }
        alwaysOn.forEach { assertTrue(s.shouldSync(it), "$it must always sync") }
    }

    @Test
    fun `hosts toggle gates workspace types but never settings`() {
        val s = SyncSettings(syncHosts = false)
        listOf(RecordType.HOST, RecordType.GROUP, RecordType.IDENTITY, RecordType.CREDENTIAL, RecordType.KNOWN_HOST, RecordType.TUNNEL)
            .forEach { assertFalse(s.shouldSync(it), "$it must be gated by syncHosts") }
        assertTrue(s.shouldSync(RecordType.SETTINGS), "settings record always syncs")
        assertTrue(s.shouldSync(RecordType.SNIPPET), "snippet independent of syncHosts")
    }

    @Test
    fun `a trash snapshot follows the toggle of the type it holds`() {
        val noSnippets = SyncSettings(syncSnippets = false)
        assertFalse(noSnippets.shouldSync(RecordType.TRASH, trashRecordId(RecordType.SNIPPET, "s-1")))
        assertTrue(noSnippets.shouldSync(RecordType.TRASH, trashRecordId(RecordType.HOST, "h-1")))

        val noHosts = SyncSettings(syncHosts = false)
        assertFalse(noHosts.shouldSync(RecordType.TRASH, trashRecordId(RecordType.HOST, "h-1")))
        assertTrue(noHosts.shouldSync(RecordType.TRASH, trashRecordId(RecordType.SNIPPET, "s-1")))
    }

    @Test
    fun `a trash id we cannot read syncs only when everything syncs`() {
        // Conservative: a snapshot whose origin type is unknown (older/newer format) leaves the
        // device only if no category is gated, so a disabled toggle can't leak through the trash.
        assertTrue(SyncSettings().shouldSync(RecordType.TRASH, "garbage"))
        assertFalse(SyncSettings(syncSnippets = false).shouldSync(RecordType.TRASH, "garbage"))
        assertFalse(SyncSettings(syncHosts = false).shouldSync(RecordType.TRASH, "garbage"))
    }
}
