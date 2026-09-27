package app.skerry.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class KtorSyncClientConfigTest {

    @Test
    fun `the sync sockets ping so a half-dead socket fails instead of hanging`() {
        // Without pings a connection that died with no FIN/RST (Wi-Fi switch, suspend, NAT
        // timeout) never errors: changes() hangs on a dead socket while the status stays
        // Online and live-pull is silently gone. The pinger surfaces the death, and the
        // coordinator's watch loop reconnects.
        assertEquals(30_000L, KtorSyncClient.WS_LIMITS.pingIntervalMillis)
    }

    @Test
    fun `the sync sockets bound each frame and the queue of unread ones`() {
        assertEquals(64L * 1024, KtorSyncClient.WS_LIMITS.maxFrameBytes)
        assertEquals(64, KtorSyncClient.WS_LIMITS.incomingFrames)
    }
}
