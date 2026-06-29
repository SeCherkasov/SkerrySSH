package app.skerry.shared.sync

import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSettingsTest {

    @Test
    fun `default syncs everything`() {
        val s = SyncSettings()
        RecordType.entries.forEach { assertTrue(s.shouldSync(it), "default must sync $it") }
    }

    @Test
    fun `snippets toggle gates only snippet type`() {
        val s = SyncSettings(syncSnippets = false)
        assertFalse(s.shouldSync(RecordType.SNIPPET))
        assertTrue(s.shouldSync(RecordType.HOST))
        assertTrue(s.shouldSync(RecordType.SETTINGS), "settings record always syncs")
    }

    @Test
    fun `both off syncs only the settings record itself`() {
        val s = SyncSettings(syncHosts = false, syncSnippets = false)
        RecordType.entries.filter { it != RecordType.SETTINGS }
            .forEach { assertFalse(s.shouldSync(it), "$it must be gated when both off") }
        assertTrue(s.shouldSync(RecordType.SETTINGS), "settings record always syncs so the OFF state reaches other devices")
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
