package app.skerry.ui.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проекция состояния sync на карточку аккаунта (Settings → Account / mobile More). */
class AccountCardModelTest {

    @Test
    fun disabled_and_null_show_local_vault() {
        for (status in listOf(null, SyncStatus.Disabled)) {
            val m = accountCardModel(status)
            assertEquals("Local vault", m.title)
            assertEquals("Encrypted on this device", m.subtitle)
            assertEquals("S", m.initials)
            assertTrue(m.localOnly)
            assertFalse(m.connected)
            assertFalse(m.linked)
        }
    }

    @Test
    fun online_shows_account_and_server_host() {
        val m = accountCardModel(
            SyncStatus.Online(accountId = "maya@skerry.dev", lastPushed = 3, lastPulled = 1),
            serverUrl = "https://sync.example.com:8443/path",
        )
        assertEquals("maya@skerry.dev", m.title)
        assertEquals("Synced · sync.example.com", m.subtitle)
        assertEquals("MA", m.initials)
        assertTrue(m.connected)
        assertFalse(m.localOnly)
    }

    @Test
    fun online_without_server_url_falls_back_to_plain_synced() {
        val m = accountCardModel(SyncStatus.Online("bob", 0, 0), serverUrl = null)
        assertEquals("Synced", m.subtitle)
        assertEquals("BO", m.initials)
    }

    @Test
    fun configured_is_linked_but_locked() {
        val m = accountCardModel(SyncStatus.Configured(serverUrl = "https://box.lan", accountId = "carol"))
        assertEquals("carol", m.title)
        assertEquals("Linked · locked", m.subtitle)
        assertTrue(m.linked)
        assertFalse(m.connected)
        assertFalse(m.localOnly)
    }

    @Test
    fun busy_and_failed_render_as_local_vault_variants() {
        assertEquals("Syncing…", accountCardModel(SyncStatus.Busy).subtitle)
        assertTrue(accountCardModel(SyncStatus.Busy).localOnly)
        assertEquals("Sync error", accountCardModel(SyncStatus.Failed(SyncFailureReason.SyncFailed, "boom")).subtitle)
        assertTrue(accountCardModel(SyncStatus.Failed(SyncFailureReason.SyncFailed, "boom")).localOnly)
    }

    @Test
    fun initials_handle_digits_and_empty_local_part() {
        assertEquals("S", accountInitials("@server"))
        assertEquals("S", accountInitials(""))
        assertEquals("A1", accountInitials("a1b2"))
    }

    @Test
    fun server_host_strips_scheme_port_and_path() {
        assertEquals("sync.example.com", serverHost("https://sync.example.com:8443/x"))
        assertEquals("localhost", serverHost("http://localhost:8443"))
        assertEquals("box.lan", serverHost("  box.lan  "))
        assertEquals(null, serverHost(null))
        assertEquals(null, serverHost("   "))
    }

    @Test
    fun server_host_handles_ipv6_literals() {
        // IPv6 в скобках: наивный substringBefore(':') вернул бы «[» — берём содержимое скобок без порта.
        assertEquals("::1", serverHost("http://[::1]:8080/sync"))
        assertEquals("2001:db8::1", serverHost("https://[2001:db8::1]:8443"))
        assertEquals("::1", serverHost("http://[::1]"))
        assertEquals(null, serverHost("http://[]:8080")) // пустые скобки — не хост
    }
}
