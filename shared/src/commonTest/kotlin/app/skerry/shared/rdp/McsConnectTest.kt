package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MCS connect PDUs and the GCC conference data they carry, checked against the annotated dumps of
 * MS-RDPBCGR 4.1.3 (client) and 4.1.4 (server).
 */
class McsConnectTest {

    private val settings = RdpClientSettings(
        desktopWidth = 1280,
        desktopHeight = 1024,
        clientName = "SKERRY",
        keyboardLayout = 0x409,
        selectedProtocol = RdpSecurityProtocol.HYBRID,
        channels = listOf("cliprdr", "drdynvc"),
    )

    @Test
    fun `connect initial carries the domain parameters a real client sends`() {
        val pdu = McsConnect.connectInitial(byteArrayOf(0x01, 0x02, 0x03))
        val dump = pdu.toHex()

        // Application tag, the two domain selectors and the upward flag, verbatim from the dump.
        assertTrue(dump.startsWith("7f 65"), dump)
        assertTrue(dump.contains("04 01 01 04 01 01 01 01 ff"), dump)
        // target / minimum / maximum DomainParameters, byte for byte as in section 4.1.3.
        assertTrue(
            dump.contains("30 19 02 01 22 02 01 02 02 01 00 02 01 01 02 01 00 02 01 01 02 02 ff ff 02 01 02"),
            dump,
        )
        assertTrue(
            dump.contains("30 19 02 01 01 02 01 01 02 01 01 02 01 01 02 01 00 02 01 01 02 02 04 20 02 01 02"),
            dump,
        )
        assertTrue(
            dump.contains(
                "30 1c 02 02 ff ff 02 02 fc 17 02 02 ff ff 02 01 01 02 01 00 02 01 01 02 02 ff ff 02 01 02",
            ),
            dump,
        )
        assertTrue(dump.endsWith("04 03 01 02 03"), dump) // userData as an octet string
    }

    @Test
    fun `the gcc conference create request matches the T124 framing of the dump`() {
        val request = Gcc.conferenceCreateRequest(byteArrayOf(0x11, 0x22))

        // connectPDU length is the real size of what follows (12 bytes of framing + the length
        // determinant + the data); mstsc's own encoder adds a fixed 14, which only agrees with the
        // truth once the user data needs a two-byte length — see the next test.
        assertContentEquals(hex("00 05 00 14 7c 00 01 0f 00 08 00 10 00 01 c0 00 44 75 63 61 02 11 22"), request)
    }

    @Test
    fun `long user data switches the gcc lengths to their two-byte form`() {
        val request = Gcc.conferenceCreateRequest(ByteArray(284))
        val dump = request.toHex()

        // 284 bytes of user data is the case the dump shows: `81 2a` for connectPDU, `81 1c` for the set.
        assertTrue(dump.startsWith("00 05 00 14 7c 00 01 81 2a 00 08 00 10 00 01 c0 00 44 75 63 61 81 1c"), dump)
    }

    @Test
    fun `the server connect response is parsed into channels and the io channel`() {
        val response = McsConnect.parseConnectResponse(X224.dataPayload(SERVER_CONNECT_RESPONSE))

        assertEquals(1003, response.ioChannelId)
        assertContentEquals(intArrayOf(1004, 1005, 1006), response.channelIds)
        assertEquals(0x00080004, response.serverVersion)
        assertEquals(RdpSecurityProtocol.RDP, response.clientRequestedProtocols)
    }

    @Test
    fun `a refused connect response is reported instead of being parsed further`() {
        // result = rt-user-rejected (1) with everything else intact.
        val refused = SERVER_CONNECT_RESPONSE.copyOf().also { it[14] = 0x01 }

        assertFailsWith<RdpProtocolException> { McsConnect.parseConnectResponse(X224.dataPayload(refused)) }
    }

    @Test
    fun `a channel array longer than the declared count is refused`() {
        // channelCount says 250 while the block only holds three ids: reading it out would run past
        // the block and into whatever the server appended.
        val tampered = SERVER_CONNECT_RESPONSE.copyOf()
        val countOffset = tampered.indexOfBlock(hex("03 0c 10 00")) + 6
        tampered[countOffset] = 0xFA.toByte()

        assertFailsWith<RdpProtocolException> { McsConnect.parseConnectResponse(X224.dataPayload(tampered)) }
    }

    @Test
    fun `client core data announces the resolution, the selected protocol and 32bpp`() {
        val userData = Gcc.clientUserData(settings)
        val core = userData.blockAt(0xC001)

        assertEquals(0x00080004, core.u32le()) // version
        assertEquals(1280, core.u16le()) // desktopWidth
        assertEquals(1024, core.u16le()) // desktopHeight
        assertEquals(0xCA01, core.u16le()) // colorDepth, the legacy 8bpp field every client sends
        assertEquals(0xAA03, core.u16le()) // SASSequence
        assertEquals(0x409, core.u32le()) // keyboardLayout
        core.skip(4) // clientBuild
        assertEquals("SKERRY", core.utf16le(32))
        core.skip(12) // keyboardType, keyboardSubType, keyboardFunctionKey
        core.skip(64) // imeFileName
        assertEquals(0xCA01, core.u16le()) // postBeta2ColorDepth
        core.skip(2 + 4) // clientProductId, serialNumber
        assertEquals(24, core.u16le()) // highColorDepth
        assertEquals(0x0F, core.u16le()) // supportedColorDepths: 24/16/15/32
        val earlyCapabilityFlags = core.u16le()
        assertTrue(earlyCapabilityFlags and 0x0002 != 0, "wants a 32bpp session")
        assertTrue(earlyCapabilityFlags and 0x0001 != 0, "understands the error info PDU")
        core.skip(64 + 1 + 1) // clientDigProductId, connectionType, pad
        assertEquals(RdpSecurityProtocol.HYBRID, core.u32le()) // serverSelectedProtocol, echoed back
        // An unscaled client says nothing: the optional tail is absent, so the block is the same
        // bytes an older Skerry sent and no server sees a longer one for a session it cannot use.
        assertEquals(0, core.remaining, "an unscaled client appended an optional tail")
    }

    @Test
    fun `client core data states the display scaling so the session is not drawn at 96 dpi`() {
        val core = Gcc.clientUserData(
            settings.copy(desktopWidth = 2880, desktopHeight = 1800, displayScale = 1.5f),
        ).blockAt(0xC001)
        val expected = RdpDisplayScale.of(2880, 1800, 1.5f)

        core.skip(4 + 2 + 2 + 2 + 2) // version, desktop width/height, colour depth, SAS sequence
        core.skip(4 + 4 + 32 + 12 + 64) // keyboard, build, client name, keyboard detail, IME
        core.skip(2 + 2 + 4 + 2 + 2 + 2) // colour and product fields, early capability flags
        core.skip(64 + 1 + 1 + 4) // clientDigProductId, connectionType, pad, serverSelectedProtocol
        assertEquals(expected.physicalWidthMm, core.u32le())
        assertEquals(expected.physicalHeightMm, core.u32le())
        assertEquals(0, core.u16le()) // desktopOrientation: landscape
        assertEquals(150, core.u32le()) // desktopScaleFactor
        assertEquals(RdpDisplayScale.DEVICE_140, core.u32le())
        assertEquals(0, core.remaining, "the block length did not grow with the tail it carries")
    }

    @Test
    fun `client network data lists the virtual channels with null-padded names`() {
        val net = Gcc.clientUserData(settings).blockAt(0xC003)

        assertEquals(2, net.u32le())
        assertEquals("cliprdr", net.ascii(8))
        net.skip(4)
        assertEquals("drdynvc", net.ascii(8))
    }

    @Test
    fun `client security data offers no legacy encryption when the transport is TLS`() {
        val security = Gcc.clientUserData(settings).blockAt(0xC002)

        // Standard RDP Security is negotiated away; advertising methods for it would be a lie the
        // server could take us up on.
        assertEquals(0, security.u32le()) // encryptionMethods
        assertEquals(0, security.u32le()) // extEncryptionMethods
    }

    private fun ByteArray.indexOfBlock(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return start
        }
        throw AssertionError("block not found")
    }

    /** A reader positioned at the body of the TS_UD block of [type] inside client user data. */
    private fun ByteArray.blockAt(type: Int): RdpReader {
        val reader = RdpReader(this)
        while (reader.remaining > 0) {
            val blockType = reader.u16le()
            val blockLength = reader.u16le()
            val body = reader.slice(blockLength - 4)
            if (blockType == type) return body
        }
        throw AssertionError("no block of type 0x${type.toString(16)}")
    }

    private companion object {
        /** MS-RDPBCGR 4.1.4, verbatim. */
        val SERVER_CONNECT_RESPONSE = hex(
            """
            03 00 01 51 02 f0 80 7f 66 82 01 45 0a 01 00 02
            01 00 30 1a 02 01 22 02 01 03 02 01 00 02 01 01
            02 01 00 02 01 01 02 03 00 ff f8 02 01 02 04 82
            01 1f 00 05 00 14 7c 00 01 2a 14 76 0a 01 01 00
            01 c0 00 4d 63 44 6e 81 08 01 0c 0c 00 04 00 08
            00 00 00 00 00 03 0c 10 00 eb 03 03 00 ec 03 ed
            03 ee 03 00 00 02 0c ec 00 02 00 00 00 02 00 00
            00 20 00 00 00 b8 00 00 00 10 11 77 20 30 61 0a
            12 e4 34 a1 1e f2 c3 9f 31 7d a4 5f 01 89 34 96
            e0 ff 11 08 69 7f 1a c3 d2 01 00 00 00 01 00 00
            00 01 00 00 00 06 00 5c 00 52 53 41 31 48 00 00
            00 00 02 00 00 3f 00 00 00 01 00 01 00 cb 81 fe
            ba 6d 61 c3 55 05 d5 5f 2e 87 f8 71 94 d6 f1 a5
            cb f1 5f 0c 3d f8 70 02 96 c4 fb 9b c8 3c 2d 55
            ae e8 ff 32 75 ea 68 79 e5 a2 01 fd 31 a0 b1 1f
            55 a6 1f c1 f6 d1 83 88 63 26 56 12 bc 00 00 00
            00 00 00 00 00 08 00 48 00 e9 e1 d6 28 46 8b 4e
            f5 0a df fd ee 21 99 ac b4 e1 8f 5f 81 57 82 ef
            9d 96 52 63 27 18 29 db b3 4a fd 9a da 42 ad b5
            69 21 89 0e 1d c0 4c 1a a8 aa 71 3e 0f 54 b9 9a
            e4 99 68 3f 6c d6 76 84 61 00 00 00 00 00 00 00
            00
            """,
        )
    }
}
