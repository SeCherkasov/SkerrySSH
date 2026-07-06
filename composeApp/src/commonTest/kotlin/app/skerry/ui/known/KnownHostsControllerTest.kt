package app.skerry.ui.known

import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.shared.ssh.HostKeyMismatchStore
import app.skerry.shared.ssh.KnownHost
import app.skerry.shared.ssh.KnownHostsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnownHostsControllerTest {

    private val ed = "ssh-ed25519"

    @Test
    fun `exposes trusted keys as Verified when there is no mismatch`() {
        val store = fakeStore(KnownHost("prod", 22, ed, "SHA256:AAA", "2026-06-22T10:00:00Z"))
        val controller = KnownHostsController(store, fakeMismatches())

        assertEquals(1, controller.entries.size)
        assertEquals(KnownHostStatus.Verified, controller.entries.single().status)
        assertEquals("prod", controller.entries.single().host.host)
    }

    @Test
    fun `marks a key Changed when a mismatch is pending for it`() {
        val store = fakeStore(KnownHost("nas", 22, ed, "SHA256:OLD", "2026-06-01T10:00:00Z"))
        val mismatches = fakeMismatches(
            HostKeyMismatch("nas", 22, ed, "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z"),
        )
        val controller = KnownHostsController(store, mismatches)

        assertEquals(KnownHostStatus.Changed, controller.entries.single().status)
        assertEquals(1, controller.mismatches.size)
    }

    @Test
    fun `acceptNewKey replaces the trusted fingerprint and clears the mismatch`() {
        val store = fakeStore(KnownHost("nas", 22, ed, "SHA256:OLD", "2026-06-01T10:00:00Z"))
        val mismatch = HostKeyMismatch("nas", 22, ed, "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")
        val controller = KnownHostsController(store, fakeMismatches(mismatch), now = { "2026-06-22T12:00:00Z" })

        controller.acceptNewKey(mismatch)

        val entry = controller.entries.single()
        assertEquals("SHA256:NEW", entry.host.fingerprint)
        assertEquals("2026-06-22T12:00:00Z", entry.host.firstSeen)
        assertEquals(KnownHostStatus.Verified, entry.status)
        assertTrue(controller.mismatches.isEmpty())
    }

    @Test
    fun `reject keeps the recorded key and clears the mismatch`() {
        val store = fakeStore(KnownHost("nas", 22, ed, "SHA256:OLD", "2026-06-01T10:00:00Z"))
        val mismatch = HostKeyMismatch("nas", 22, ed, "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")
        val controller = KnownHostsController(store, fakeMismatches(mismatch))

        controller.reject(mismatch)

        val entry = controller.entries.single()
        assertEquals("SHA256:OLD", entry.host.fingerprint)
        assertEquals(KnownHostStatus.Verified, entry.status)
        assertTrue(controller.mismatches.isEmpty())
    }

    @Test
    fun `forget removes the key and any related mismatch`() {
        val store = fakeStore(
            KnownHost("nas", 22, ed, "SHA256:OLD", ""),
            KnownHost("db", 22, ed, "SHA256:DB", ""),
        )
        val mismatch = HostKeyMismatch("nas", 22, ed, "SHA256:OLD", "SHA256:NEW", "2026-06-22T11:00:00Z")
        val controller = KnownHostsController(store, fakeMismatches(mismatch))

        controller.forget(controller.entries.first { it.host.host == "nas" })

        assertEquals(listOf("db"), controller.entries.map { it.host.host })
        assertTrue(controller.mismatches.isEmpty())
    }

    @Test
    fun `refresh picks up keys added to the store after construction`() {
        // A reconnect writes a new key to the shared store from the TOFU flow; the view should pick
        // it up when the screen reopens and calls refresh(), not only after an app restart.
        val store = fakeStore(KnownHost("prod", 22, ed, "SHA256:AAA", ""))
        val controller = KnownHostsController(store, fakeMismatches())
        assertEquals(listOf("prod"), controller.entries.map { it.host.host })

        store.add(KnownHost("db", 22, ed, "SHA256:DB", ""))
        controller.refresh()

        assertEquals(listOf("prod", "db"), controller.entries.map { it.host.host })
    }

    @Test
    fun `shortFingerprint strips the prefix and elides the middle`() {
        assertEquals("8c3F1a2bQz…wQ1z", shortFingerprint("SHA256:8c3F1a2bQzABCDEFwQ1z"))
        assertEquals("short", shortFingerprint("SHA256:short"))
    }

    private fun fakeStore(vararg hosts: KnownHost): KnownHostsStore = object : KnownHostsStore {
        val entries = hosts.toMutableList()
        override fun all() = entries.toList()
        override fun add(host: KnownHost) { entries += host }
        override fun replace(host: KnownHost) {
            entries.removeAll { it.host == host.host && it.port == host.port && it.keyType == host.keyType }
            entries += host
        }
        override fun remove(host: String, port: Int, keyType: String) {
            entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        }
    }

    private fun fakeMismatches(vararg items: HostKeyMismatch): HostKeyMismatchStore = object : HostKeyMismatchStore {
        val entries = items.toMutableList()
        override fun all() = entries.toList()
        override fun record(mismatch: HostKeyMismatch) {
            entries.removeAll { it.host == mismatch.host && it.port == mismatch.port && it.keyType == mismatch.keyType }
            entries += mismatch
        }
        override fun clear(host: String, port: Int, keyType: String) {
            entries.removeAll { it.host == host && it.port == port && it.keyType == keyType }
        }
    }
}
