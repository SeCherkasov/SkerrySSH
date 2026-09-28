package app.skerry.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Disconnect button on a session notification. Below Android 12 the platform cannot demand an
 * unlock for a notification action, so the lock is checked here; and a notification that outlived
 * its session must go rather than stay behind with a button that does nothing.
 */
class NotificationDisconnectTest {

    private val closed = mutableListOf<String>()
    private val forgotten = mutableListOf<String>()

    private fun disconnect(locked: Boolean = false, live: Set<String> = setOf("sess-1")) =
        NotificationDisconnect(
            closeSession = { id -> (id in live).also { if (it) closed += id } },
            forget = { forgotten += it },
            deviceLocked = { locked },
        )

    @Test
    fun aLiveSessionIsClosedAndLeftToEndItsOwnNotification() {
        disconnect().handle("sess-1")

        assertEquals(listOf("sess-1"), closed)
        assertTrue(forgotten.isEmpty(), "the controller reports the end; no second remove")
    }

    @Test
    fun aNotificationWithoutASessionIsDropped() {
        disconnect().handle("sess-9")

        assertTrue(closed.isEmpty())
        assertEquals(listOf("sess-9"), forgotten)
    }

    @Test
    fun aLockedPhoneClosesNothing() {
        disconnect(locked = true).handle("sess-1")
        disconnect(locked = true).handle("sess-9")

        assertTrue(closed.isEmpty())
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun anIntentWithoutASessionIdIsIgnored() {
        disconnect().handle(null)

        assertTrue(closed.isEmpty())
        assertTrue(forgotten.isEmpty())
    }
}
