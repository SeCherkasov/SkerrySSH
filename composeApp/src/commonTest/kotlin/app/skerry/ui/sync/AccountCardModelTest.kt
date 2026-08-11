package app.skerry.ui.sync

import app.skerry.shared.sync.RemoteDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Projects sync status onto the account card (Settings -> Account / mobile More). */
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

    /**
     * A device that joined by pairing learns the account id from the server's answer, so the title on the
     * card is not necessarily this user's own typing. A legitimate id has to survive it untouched.
     */
    @Test
    fun account_title_is_filtered_but_a_real_id_is_untouched() {
        val hostile = accountCardModel(SyncStatus.Online("ma\u202Eya\u200B", 0, 0)).title
        assertFalse(hostile.contains('\u202E'), "the title kept a bidi override: $hostile")
        assertFalse(hostile.contains('\u200B'), "the title kept a zero-width space: $hostile")
        assertEquals("maya@skerry.dev", accountCardModel(SyncStatus.Online("maya@skerry.dev", 0, 0)).title)
        assertEquals("carol", accountCardModel(SyncStatus.Configured("https://work.test", "carol")).title)
    }

    /**
     * The linked-device list is the surface the account owner revokes from, and the name comes back from
     * the server. A bidi override in one row reverses the row beside it, and the owner revokes the wrong
     * device while the intruder keeps its token. A name that filters away to nothing still has to leave
     * one row distinguishable from the next.
     */
    @Test
    fun device_label_is_filtered_and_never_blank() {
        assertEquals("Maya's laptop", deviceLabel(device(name = "Maya's laptop"), unnamed))
        val hostile = deviceLabel(device(name = "kitchen\u202Eexe.pi\u200B"), unnamed)
        assertFalse(hostile.contains('\u202E'), "the device name kept a bidi override: $hostile")
        assertFalse(hostile.contains('\u200B'), "the device name kept a zero-width space: $hostile")

        // Nothing drawable left in the name, and two such devices must still not read as one row.
        val a = deviceLabel(device(id = "dev-aaaaaaaa-1", name = "\u200B\u200B"), unnamed)
        val b = deviceLabel(device(id = "dev-bbbbbbbb-2", name = ""), unnamed)
        assertTrue(a.isNotBlank(), "an unnamed device drew nothing at all")
        assertTrue(b.isNotBlank(), "an unnamed device drew nothing at all")
        assertTrue(a != b, "two unnamed devices read as the same row: $a")

        // The id is server text too and can filter away as well. A row that draws and announces an empty
        // string is one the owner cannot tell from the next, on the surface they revoke from.
        assertEquals(unnamed, deviceLabel(device(id = "\u202E\u200B", name = "\u200B"), unnamed))
    }

    private val unnamed = "Unnamed device"

    private fun device(id: String = "dev-1", name: String) =
        RemoteDevice(id = id, name = name, createdAt = 0, lastSeenAt = 0, revoked = false, current = false)

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
        // IPv6 in brackets: a naive substringBefore(':') would return "[" — take the bracket contents, no port.
        assertEquals("::1", serverHost("http://[::1]:8080/sync"))
        assertEquals("2001:db8::1", serverHost("https://[2001:db8::1]:8443"))
        assertEquals("::1", serverHost("http://[::1]"))
        assertEquals(null, serverHost("http://[]:8080")) // empty brackets aren't a host
    }
}
