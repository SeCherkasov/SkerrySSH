package app.skerry.shared.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoshOutgoingTest {

    // startMs = 8 puts the internal "last send" anchor at t=0: sends are allowed from t=8 on.
    private fun outgoing(rtt: MoshRtt = MoshRtt()) = MoshOutgoing(rtt, startMs = MOSH_SEND_MINDELAY_MS)

    @Test
    fun `nothing to send right after start until heartbeat is due`() {
        val o = outgoing()
        assertNull(o.poll(nowMs = 100, serverAckNum = 0u, forceAck = false))
        val hb = o.poll(nowMs = MOSH_ACK_INTERVAL_MS + 1, serverAckNum = 0u, forceAck = false)
        assertNotNull(hb)
        assertEquals(0uL, hb.oldNum)
        assertEquals(0uL, hb.newNum)
        assertEquals(0, hb.diff.size)
    }

    @Test
    fun `keystrokes are sent as a diff from the acked state`() {
        val o = outgoing()
        o.addKeystroke(byteArrayOf(0x61))
        o.addKeystroke(byteArrayOf(0x62))
        val inst = o.poll(nowMs = 50, serverAckNum = 5u, forceAck = false)
        assertNotNull(inst)
        assertEquals(0uL, inst.oldNum)
        assertEquals(2uL, inst.newNum)
        assertEquals(5uL, inst.ackNum)
        assertContentEquals(
            encodeUserMessage(
                listOf(
                    MoshUserEvent.Keystroke(byteArrayOf(0x61)),
                    MoshUserEvent.Keystroke(byteArrayOf(0x62)),
                ),
            ),
            inst.diff,
        )
    }

    @Test
    fun `acked events are dropped from later diffs`() {
        val o = outgoing()
        o.addKeystroke(byteArrayOf(0x61))
        o.poll(nowMs = 50, serverAckNum = 0u, forceAck = false)
        o.onAck(1u)
        o.addResize(cols = 100, rows = 30)
        val inst = o.poll(nowMs = 120, serverAckNum = 0u, forceAck = false)
        assertNotNull(inst)
        assertEquals(1uL, inst.oldNum)
        assertEquals(2uL, inst.newNum)
        assertContentEquals(
            encodeUserMessage(listOf(MoshUserEvent.Resize(cols = 100, rows = 30))),
            inst.diff,
        )
    }

    @Test
    fun `unacked data is retransmitted only after the rto`() {
        val rtt = MoshRtt()
        rtt.onSample(100) // RTO = 100 + 4*50 = 300
        val o = outgoing(rtt)
        o.addKeystroke(byteArrayOf(0x61))
        assertNotNull(o.poll(nowMs = 10, serverAckNum = 0u, forceAck = false))
        assertNull(o.poll(nowMs = 200, serverAckNum = 0u, forceAck = false))
        val again = o.poll(nowMs = 320, serverAckNum = 0u, forceAck = false)
        assertNotNull(again)
        assertEquals(0uL, again.oldNum)
        assertEquals(1uL, again.newNum)
    }

    @Test
    fun `force ack sends an empty diff immediately`() {
        val o = outgoing()
        assertNull(o.poll(nowMs = 100, serverAckNum = 3u, forceAck = false))
        val inst = o.poll(nowMs = 100, serverAckNum = 3u, forceAck = true)
        assertNotNull(inst)
        assertEquals(3uL, inst.ackNum)
        assertEquals(0, inst.diff.size)
    }

    @Test
    fun `fresh input goes out immediately but the next send is rate-limited`() {
        val o = outgoing()
        o.addKeystroke(byteArrayOf(0x61))
        assertNotNull(o.poll(nowMs = 100, serverAckNum = 0u, forceAck = false))
        o.addKeystroke(byteArrayOf(0x62))
        assertNull(o.poll(nowMs = 104, serverAckNum = 0u, forceAck = false))
        val flushed = o.poll(nowMs = 100 + MOSH_SEND_MINDELAY_MS, serverAckNum = 0u, forceAck = false)
        assertNotNull(flushed)
        // Still unacked: the diff re-sends both pending events from the acked base.
        assertEquals(0uL, flushed.oldNum)
        assertEquals(2uL, flushed.newNum)
    }

    @Test
    fun `shutdown instruction carries the sentinel state number`() {
        val o = outgoing()
        o.startShutdown()
        val inst = o.poll(nowMs = 1, serverAckNum = 0u, forceAck = false)
        assertNotNull(inst)
        assertEquals(ULong.MAX_VALUE, inst.newNum)
        assertEquals(1, o.shutdownSends)
        // The next shutdown resend waits for the RTO.
        assertNull(o.poll(nowMs = 2, serverAckNum = 0u, forceAck = false))
    }

    @Test
    fun `next wake time reflects the earliest pending deadline`() {
        val o = outgoing()
        assertEquals(MOSH_ACK_INTERVAL_MS, o.nextWakeMs(nowMs = 0))
        o.addKeystroke(byteArrayOf(0x61))
        assertTrue(o.nextWakeMs(nowMs = 0) <= MOSH_SEND_MINDELAY_MS)
    }
}

class MoshIncomingTest {

    private val diff = encodeHostBytesDiff("hello".encodeToByteArray())

    private fun instruction(old: ULong, new: ULong, ack: ULong = 0u, diff: ByteArray = this.diff) =
        MoshInstruction(oldNum = old, newNum = new, ackNum = ack, throwawayNum = 0u, diff = diff)

    @Test
    fun `in-order state is applied and acknowledged`() {
        val inc = MoshIncoming()
        val updates = inc.onInstruction(instruction(old = 0u, new = 1u))
        assertNotNull(updates)
        val bytes = assertIs<MoshHostUpdate.Bytes>(updates.single())
        assertContentEquals("hello".encodeToByteArray(), bytes.data)
        assertEquals(1uL, inc.ackNum)
        assertTrue(inc.ackOutstanding)
    }

    @Test
    fun `duplicate of an already applied state is re-acked but not re-applied`() {
        val inc = MoshIncoming()
        inc.onInstruction(instruction(old = 0u, new = 1u))
        inc.clearAckOutstanding()
        val updates = inc.onInstruction(instruction(old = 0u, new = 1u))
        assertNotNull(updates)
        assertEquals(0, updates.size)
        assertTrue(inc.ackOutstanding)
    }

    @Test
    fun `diff against an unknown state is dropped`() {
        val inc = MoshIncoming()
        assertNull(inc.onInstruction(instruction(old = 5u, new = 6u)))
        assertEquals(0uL, inc.ackNum)
    }

    @Test
    fun `states advance across several instructions`() {
        val inc = MoshIncoming()
        inc.onInstruction(instruction(old = 0u, new = 3u))
        val updates = inc.onInstruction(instruction(old = 3u, new = 7u))
        assertNotNull(updates)
        assertEquals(7uL, inc.ackNum)
    }
}

/** Builds a HostMessage diff with a single HostBytes instruction (test helper). */
internal fun encodeHostBytesDiff(data: ByteArray): ByteArray {
    // HostMessage { Instruction { HostBytes { hoststring = data } } }
    fun varint(v: Int): ByteArray {
        var x = v
        val out = mutableListOf<Byte>()
        while (x >= 0x80) {
            out.add(((x and 0x7F) or 0x80).toByte())
            x = x ushr 7
        }
        out.add(x.toByte())
        return out.toByteArray()
    }

    val hostBytes = byteArrayOf(0x0A) + varint(data.size) + data
    val instruction = byteArrayOf(0x12) + varint(hostBytes.size) + hostBytes
    return byteArrayOf(0x0A) + varint(instruction.size) + instruction
}
