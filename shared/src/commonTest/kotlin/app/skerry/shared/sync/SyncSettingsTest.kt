package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSettingsTest {

    @Test
    fun `default syncs everything except local terminal history`() {
        val s = SyncSettings()
        // TERMINAL_HISTORY — сознательно локальная (per-host, объёмная, чувствительная), не синкается.
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
        // TEAM/TEAM_IDENTITY — ключи команд и identity-пара: без них другое устройство не откроет
        // team-vault'ы вовсе, поэтому селективный синк их не гейтит (как и сам SETTINGS-рекорд).
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
}
