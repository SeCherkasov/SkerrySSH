package app.skerry.server.share

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareRelayTest {

    private fun relay(
        maxSharesPerTeam: Int = 8,
        maxGuestsPerShare: Int = 16,
        replayBytes: Int = 64 * 1024,
        guestQueueFrames: Int = 256,
    ) = ShareRelay(
        maxSharesPerTeam = maxSharesPerTeam,
        maxGuestsPerShare = maxGuestsPerShare,
        replayBytes = replayBytes,
        guestQueueFrames = guestQueueFrames,
        now = { 1_700_000_000_000L },
    )

    private fun ShareRelay.host(teamId: String = "team-1", shareId: String = "s1", account: String = "alice") =
        assertIs<ShareOpen.Started>(open(teamId, shareId, account, meta = "bWV0YQ==")).session

    private fun ShareRelay.guest(teamId: String = "team-1", shareId: String = "s1", account: String = "mate") =
        assertIs<ShareJoin.Joined>(join(teamId, shareId, account)).session

    @Test
    fun `an open share is listed for its team only while it is live`() {
        val relay = relay()

        val session = relay.host()

        val listed = relay.list("team-1").single()
        assertEquals("s1", listed.shareId)
        assertEquals("alice", listed.hostAccountId)
        assertEquals("bWV0YQ==", listed.meta)
        assertEquals(1_700_000_000_000L, listed.startedAt)
        assertTrue(relay.list("team-2").isEmpty(), "a share must not leak into another team")

        session.end()
        assertTrue(relay.list("team-1").isEmpty(), "an ended share stays in the registry")
    }

    @Test
    fun `a share id already in use is refused instead of hijacking the live one`() {
        val relay = relay()
        relay.host()

        assertIs<ShareOpen.Taken>(relay.open("team-1", "s1", "mallory", meta = ""))
    }

    @Test
    fun `a team cannot open more shares than the cap`() {
        val relay = relay(maxSharesPerTeam = 2)
        relay.host(shareId = "s1")
        relay.host(shareId = "s2")

        assertIs<ShareOpen.TooMany>(relay.open("team-1", "s3", "alice", meta = ""))
        // The cap is per team: another team is unaffected.
        assertIs<ShareOpen.Started>(relay.open("team-2", "s3", "alice", meta = ""))
    }

    @Test
    fun `frames from the host reach every viewer`() = runTest {
        val relay = relay()
        val host = relay.host()
        val first = relay.guest(account = "a@x.io")
        val second = relay.guest(account = "b@x.io")

        host.broadcast("out-1".encodeToByteArray())

        assertContentEquals("out-1".encodeToByteArray(), withTimeout(2_000) { first.receive() })
        assertContentEquals("out-1".encodeToByteArray(), withTimeout(2_000) { second.receive() })
    }

    @Test
    fun `a late viewer is caught up from the replay buffer`() = runTest {
        val relay = relay()
        val host = relay.host()
        host.broadcast("before-1".encodeToByteArray())
        host.broadcast("before-2".encodeToByteArray())

        val late = relay.guest()
        host.broadcast("after".encodeToByteArray())

        // Replayed in the order the host produced them, then the live stream continues.
        assertContentEquals("before-1".encodeToByteArray(), withTimeout(2_000) { late.receive() })
        assertContentEquals("before-2".encodeToByteArray(), withTimeout(2_000) { late.receive() })
        assertContentEquals("after".encodeToByteArray(), withTimeout(2_000) { late.receive() })
    }

    @Test
    fun `the replay buffer is bounded and keeps the newest frames`() = runTest {
        val relay = relay(replayBytes = 10)
        val host = relay.host()
        host.broadcast(ByteArray(6) { 1 })
        host.broadcast(ByteArray(6) { 2 })

        val late = relay.guest()
        host.end()

        // The first frame no longer fits under the cap and was evicted; the newest one survives.
        assertContentEquals(ByteArray(6) { 2 }, withTimeout(2_000) { late.receive() })
        assertNull(withTimeout(2_000) { late.receive() }, "the stream ends with the share")
    }

    @Test
    fun `viewer input reaches the host`() = runTest {
        val relay = relay()
        val host = relay.host()
        val guest = relay.guest()

        assertTrue(guest.sendToHost("ls\n".encodeToByteArray()))

        val message = withTimeout(2_000) { host.receiveInput() }
        assertContentEquals("ls\n".encodeToByteArray(), message)
    }

    @Test
    fun `the host is told who is watching`() = runTest {
        val relay = relay()
        val host = relay.host()

        val guest = relay.guest(account = "mate@x.io")
        // Names, not a count: the host's UI shows which colleagues are on the session.
        assertEquals(listOf("mate@x.io"), withTimeout(2_000) { host.receiveViewers() })
        assertEquals(1, host.viewers)

        guest.leave()
        assertEquals(emptyList(), withTimeout(2_000) { host.receiveViewers() })
        assertEquals(0, host.viewers)
    }

    @Test
    fun `a viewer that stops reading is dropped instead of stalling the session`() = runTest {
        val relay = relay(guestQueueFrames = 4)
        val host = relay.host()
        val stalled = relay.guest()

        // A viewer on a slow link must not hold the host's shell back: broadcast never suspends, and
        // the viewer that can't keep up loses its stream instead.
        repeat(64) { host.broadcast(ByteArray(8) { it.toByte() }) }

        assertEquals(0, host.viewers, "the stalled viewer must be dropped, not kept blocking the host")
        assertTrue(relay.list("team-1").isNotEmpty(), "the share itself survives a dropped viewer")
        // What was already queued is still delivered; the stream then ends.
        var frame = withTimeout(2_000) { stalled.receive() }
        while (frame != null) frame = withTimeout(2_000) { stalled.receive() }
        assertTrue(!stalled.sendToHost("x".encodeToByteArray()), "a dropped viewer cannot type either")
    }

    @Test
    fun `ending a share closes every viewer stream`() = runTest {
        val relay = relay()
        val host = relay.host()
        val guest = relay.guest()

        host.end()

        assertNull(withTimeout(2_000) { guest.receive() })
        assertTrue(!guest.sendToHost("x".encodeToByteArray()), "input after the end is refused")
    }

    @Test
    fun `a share cannot take more viewers than the cap`() {
        val relay = relay(maxGuestsPerShare = 1)
        relay.host()
        relay.guest()

        assertIs<ShareJoin.Full>(relay.join("team-1", "s1", "other"))
    }

    @Test
    fun `joining an unknown share or another team's share is refused`() {
        val relay = relay()
        relay.host()

        assertIs<ShareJoin.NoShare>(relay.join("team-1", "nope", "mate"))
        assertIs<ShareJoin.NoShare>(relay.join("team-2", "s1", "mate"))
    }

    @Test
    fun `ending a share twice is harmless`() {
        val relay = relay()
        val host = relay.host()

        host.end()
        host.end()

        assertTrue(relay.list("team-1").isEmpty())
    }
}
