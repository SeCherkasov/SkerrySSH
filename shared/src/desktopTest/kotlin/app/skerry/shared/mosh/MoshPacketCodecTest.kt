package app.skerry.shared.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStdlibApi::class)
class MoshCipherTest {

    // RFC 7253 appendix A, AES-128-OCB, 96-bit nonce, 128-bit tag, no associated data.
    private val rfcKey = "000102030405060708090A0B0C0D0E0F".hexToByteArray()

    @Test
    fun `empty plaintext matches the rfc 7253 test vector`() {
        val cipher = MoshCipher(rfcKey)
        val sealed = cipher.seal("BBAA99887766554433221100".hexToByteArray(), ByteArray(0))
        assertEquals("785407BFFFC8AD9EDCC5520AC9111EE6", sealed.toHexString(HexFormat.UpperCase))
    }

    @Test
    fun `eight byte plaintext matches the rfc 7253 test vector`() {
        val cipher = MoshCipher(rfcKey)
        val sealed = cipher.seal(
            "BBAA99887766554433221103".hexToByteArray(),
            "0001020304050607".hexToByteArray(),
        )
        assertEquals(
            "45DD69F8F5AAE72414054CD1F35D82760B2CD00D2F99BFA9",
            sealed.toHexString(HexFormat.UpperCase),
        )
    }

    @Test
    fun `open rejects tampered ciphertext`() {
        val cipher = MoshCipher(rfcKey)
        val nonce = "BBAA99887766554433221102".hexToByteArray()
        val sealed = cipher.seal(nonce, byteArrayOf(1, 2, 3))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertNull(cipher.open(nonce, sealed))
    }
}

class MoshPacketCodecTest {

    private val key = MoshKey.parse("AAAAAAAAAAAAAAAAAAAAAA")!!

    @Test
    fun `packet round-trips through seal and open`() {
        val codec = MoshPacketCodec(key)
        val packet = MoshPacket(
            seq = 7u,
            toServer = true,
            timestamp = 0x1234,
            timestampReply = MOSH_TS_NONE,
            payload = "fragment".encodeToByteArray(),
        )
        val opened = codec.open(codec.seal(packet))!!
        assertEquals(7uL, opened.seq)
        assertTrue(opened.toServer)
        assertEquals(0x1234, opened.timestamp)
        assertEquals(MOSH_TS_NONE, opened.timestampReply)
        assertContentEquals("fragment".encodeToByteArray(), opened.payload)
    }

    @Test
    fun `direction bit lives in the top bit of the wire nonce`() {
        val codec = MoshPacketCodec(key)
        val toServer = codec.seal(MoshPacket(5u, toServer = true, 0, 0, ByteArray(0)))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5), toServer.copyOfRange(0, 8))
        val toClient = codec.seal(MoshPacket(5u, toServer = false, 0, 0, ByteArray(0)))
        assertEquals(0x80.toByte(), toClient[0])
        val opened = codec.open(toClient)!!
        assertFalse(opened.toServer)
        assertEquals(5uL, opened.seq)
    }

    @Test
    fun `open rejects a datagram encrypted with a different key`() {
        val codec = MoshPacketCodec(key)
        val other = MoshPacketCodec(MoshKey.parse("zM7RhBUAAcTLKwZTHYzGaw")!!)
        val sealed = other.seal(MoshPacket(1u, toServer = false, 0, 0, byteArrayOf(9)))
        assertNull(codec.open(sealed))
    }

    @Test
    fun `open rejects datagrams too short to carry a tag`() {
        val codec = MoshPacketCodec(key)
        assertNull(codec.open(ByteArray(7)))
        assertNull(codec.open(ByteArray(20)))
    }
}

class MoshCompressionTest {

    @Test
    fun `deflate round-trips through inflate`() {
        val data = ByteArray(4096) { (it % 251).toByte() }
        assertContentEquals(data, moshInflate(moshDeflate(data)))
    }

    @Test
    fun `inflate enforces the output cap`() {
        val bomb = moshDeflate(ByteArray(1 shl 20))
        assertNull(moshInflate(bomb, maxSize = 1024))
    }

    @Test
    fun `inflate returns null on garbage`() {
        assertNull(moshInflate(byteArrayOf(1, 2, 3, 4)))
    }
}
