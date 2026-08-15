package app.skerry.ui.keepalive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeepAliveNotificationRegistryTest {

    private val base = 0x5E78

    @Test
    fun register_allocates_sequential_ids() {
        val r = KeepAliveNotificationRegistry(base)
        assertEquals(base, r.register("s1"))
        assertEquals(base + 1, r.register("s2"))
        assertEquals(base + 2, r.register("s3"))
        assertEquals(3, r.size)
    }

    @Test
    fun register_twice_keeps_the_first_id() {
        val r = KeepAliveNotificationRegistry(base)
        assertEquals(base, r.register("s1"))
        assertEquals(base, r.register("s1")) // re-register (reconnect) must not spawn a second notification
        assertEquals(1, r.size)
    }

    @Test
    fun remove_returns_id_and_shrinks() {
        val r = KeepAliveNotificationRegistry(base)
        r.register("s1")
        r.register("s2")
        assertEquals(base, r.remove("s1"))
        assertEquals(1, r.size)
        assertFalse(r.isEmpty)
    }

    @Test
    fun remove_unknown_id_is_idempotent() {
        val r = KeepAliveNotificationRegistry(base)
        r.register("s1")
        assertNull(r.remove("s2"))
        assertEquals(base, r.remove("s1"))
        assertNull(r.remove("s1")) // second removal of a known id is also a no-op
        assertTrue(r.isEmpty)
    }

    @Test
    fun last_remove_empties_registry() {
        val r = KeepAliveNotificationRegistry(base)
        r.register("s1")
        r.remove("s1")
        assertTrue(r.isEmpty)
    }

    @Test
    fun ids_come_back_in_registration_order() {
        val r = KeepAliveNotificationRegistry(base)
        r.register("s2")
        r.register("s1")
        r.register("s3")
        assertEquals(listOf("s2", "s1", "s3"), r.idsInOrder())
    }

    @Test
    fun ids_are_reused_after_all_sessions_close() {
        // The service dies with the last session; a fresh registry starts from the base again,
        // so a brand-new session gets the same id the old one had — the old notification is
        // already cancelled, so no collision.
        val r1 = KeepAliveNotificationRegistry(base)
        assertEquals(base, r1.register("s1"))
        r1.remove("s1")
        val r2 = KeepAliveNotificationRegistry(base)
        assertEquals(base, r2.register("s1"))
    }
}
