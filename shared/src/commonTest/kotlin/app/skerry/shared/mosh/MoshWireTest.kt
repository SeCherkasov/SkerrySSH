package app.skerry.shared.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class MoshWireTest {

    @Test
    fun `instruction encodes all numbered fields plus diff`() {
        val inst = MoshInstruction(
            oldNum = 0u,
            newNum = 1u,
            ackNum = 0u,
            throwawayNum = 0u,
            diff = bytes(0x68, 0x69),
        )
        // proto2: 1=version(2), 2=old, 3=new, 4=ack, 5=throwaway, 6=diff — all explicitly set.
        assertContentEquals(
            bytes(0x08, 0x02, 0x10, 0x00, 0x18, 0x01, 0x20, 0x00, 0x28, 0x00, 0x32, 0x02, 0x68, 0x69),
            inst.encode(),
        )
    }

    @Test
    fun `instruction round-trips with large sequence numbers`() {
        val inst = MoshInstruction(
            oldNum = 123456789uL,
            newNum = ULong.MAX_VALUE,
            ackNum = 42u,
            throwawayNum = 7u,
            diff = ByteArray(300) { it.toByte() },
        )
        val decoded = decodeMoshInstruction(inst.encode())
        assertEquals(inst.oldNum, decoded.oldNum)
        assertEquals(inst.newNum, decoded.newNum)
        assertEquals(inst.ackNum, decoded.ackNum)
        assertEquals(inst.throwawayNum, decoded.throwawayNum)
        assertContentEquals(inst.diff, decoded.diff)
        assertEquals(MOSH_PROTOCOL_VERSION, decoded.protocolVersion)
    }

    @Test
    fun `decoder skips unknown chaff field`() {
        // chaff (field 7, bytes) precedes known fields — must be skipped, not break parsing.
        val payload = bytes(0x3A, 0x03, 1, 2, 3, 0x08, 0x02, 0x18, 0x05)
        val decoded = decodeMoshInstruction(payload)
        assertEquals(5uL, decoded.newNum)
    }

    @Test
    fun `decoder rejects truncated input`() {
        assertFailsWith<MoshWireException> { decodeMoshInstruction(bytes(0x32, 0x0A, 0x01)) }
        assertFailsWith<MoshWireException> { decodeMoshInstruction(bytes(0x08)) }
    }

    @Test
    fun `decoder rejects a length that would overflow the bounds check`() {
        // diff field claiming Int.MAX_VALUE bytes: pos + len wraps negative if added naively.
        assertFailsWith<MoshWireException> {
            decodeMoshInstruction(bytes(0x32, 0xFF, 0xFF, 0xFF, 0xFF, 0x07))
        }
    }

    @Test
    fun `user message with one keystroke matches protobuf wire format`() {
        val encoded = encodeUserMessage(listOf(MoshUserEvent.Keystroke(bytes(0x6C, 0x73))))
        assertContentEquals(bytes(0x0A, 0x06, 0x12, 0x04, 0x0A, 0x02, 0x6C, 0x73), encoded)
    }

    @Test
    fun `user message with resize matches protobuf wire format`() {
        val encoded = encodeUserMessage(listOf(MoshUserEvent.Resize(cols = 120, rows = 40)))
        assertContentEquals(bytes(0x0A, 0x06, 0x1A, 0x04, 0x08, 0x78, 0x10, 0x28), encoded)
    }

    @Test
    fun `user message keeps event order`() {
        val encoded = encodeUserMessage(
            listOf(
                MoshUserEvent.Keystroke(bytes(0x61)),
                MoshUserEvent.Resize(cols = 80, rows = 24),
                MoshUserEvent.Keystroke(bytes(0x62)),
            ),
        )
        assertContentEquals(
            bytes(
                0x0A, 0x05, 0x12, 0x03, 0x0A, 0x01, 0x61,
                0x0A, 0x06, 0x1A, 0x04, 0x08, 0x50, 0x10, 0x18,
                0x0A, 0x05, 0x12, 0x03, 0x0A, 0x01, 0x62,
            ),
            encoded,
        )
    }

    @Test
    fun `host message decodes bytes resize and echo ack in order`() {
        // HostMessage { Instruction{hostbytes "hi"} Instruction{resize 100x30} Instruction{echoack 9} }
        val wire = bytes(
            0x0A, 0x06, 0x12, 0x04, 0x0A, 0x02, 0x68, 0x69,
            0x0A, 0x06, 0x1A, 0x04, 0x08, 0x64, 0x10, 0x1E,
            0x0A, 0x04, 0x22, 0x02, 0x08, 0x09,
        )
        val updates = decodeHostMessage(wire)
        assertEquals(3, updates.size)
        val b = assertIs<MoshHostUpdate.Bytes>(updates[0])
        assertContentEquals(bytes(0x68, 0x69), b.data)
        val r = assertIs<MoshHostUpdate.Resize>(updates[1])
        assertEquals(100, r.cols)
        assertEquals(30, r.rows)
        val e = assertIs<MoshHostUpdate.EchoAck>(updates[2])
        assertEquals(9uL, e.num)
    }

    @Test
    fun `host message tolerates unknown instruction extensions`() {
        // An instruction with an unknown extension field 15 is ignored entirely.
        val wire = bytes(
            0x0A, 0x04, 0x7A, 0x02, 0x01, 0x02,
            0x0A, 0x05, 0x12, 0x03, 0x0A, 0x01, 0x78,
        )
        val updates = decodeHostMessage(wire)
        assertEquals(1, updates.size)
        assertTrue(updates[0] is MoshHostUpdate.Bytes)
    }

    @Test
    fun `empty host diff decodes to no updates`() {
        assertEquals(0, decodeHostMessage(ByteArray(0)).size)
    }
}
