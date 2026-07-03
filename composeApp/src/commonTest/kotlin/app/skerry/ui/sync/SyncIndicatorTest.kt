package app.skerry.ui.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Индикатор sync ведёт по статусу сессии, доступность лишь различает online/offline. */
class SyncIndicatorTest {

    @Test
    fun online_reachable_is_sync_online_ok() {
        val i = syncIndicator(SyncStatus.Online("me", 1, 0), ServerReachable.REACHABLE)!!
        assertEquals("Sync online", i.label)
        assertEquals(SyncIndicatorLevel.OK, i.level)
        assertEquals("cloud_done", i.icon)
    }

    @Test
    fun online_unreachable_is_offline_error() {
        val i = syncIndicator(SyncStatus.Online("me", 1, 0), ServerReachable.UNREACHABLE)!!
        assertEquals("Sync offline", i.label)
        assertEquals(SyncIndicatorLevel.ERROR, i.level)
    }

    @Test
    fun configured_is_paused_not_online_even_when_server_reachable() {
        // Корень бага: устройство привязано, но сессии нет (отозвано/после рестарта) — не «online».
        val i = syncIndicator(SyncStatus.Configured("https://box.lan", "me"), ServerReachable.REACHABLE)!!
        assertEquals("Sync paused", i.label)
        assertEquals(SyncIndicatorLevel.WARN, i.level)
    }

    @Test
    fun failed_is_error() {
        val i = syncIndicator(SyncStatus.Failed(SyncFailureReason.SyncFailed, "boom"), ServerReachable.REACHABLE)!!
        assertEquals("Sync error", i.label)
        assertEquals(SyncIndicatorLevel.ERROR, i.level)
    }

    @Test
    fun busy_is_warn() {
        assertEquals("Syncing…", syncIndicator(SyncStatus.Busy, ServerReachable.REACHABLE)!!.label)
    }

    @Test
    fun hidden_when_not_configured_or_unknown() {
        assertNull(syncIndicator(null, ServerReachable.REACHABLE))
        assertNull(syncIndicator(SyncStatus.Disabled, ServerReachable.REACHABLE))
        assertNull(syncIndicator(SyncStatus.Online("me", 0, 0), ServerReachable.UNKNOWN))
    }
}
