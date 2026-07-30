package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The connection sequence driven against a model server that answers each step the way MS-RDPBCGR
 * 1.3.1.1 prescribes. The sequence is a fixed dance — every step's answer decides the next one — so
 * what is worth testing is that a server going off script is reported rather than hung on.
 */
class RdpConnectTest {

    private val settings = RdpClientSettings(
        desktopWidth = 1920,
        desktopHeight = 1080,
        clientName = "SKERRY",
        selectedProtocol = RdpSecurityProtocol.HYBRID,
        channels = listOf(RdpClientSettings.CHANNEL_CLIPBOARD, RdpClientSettings.CHANNEL_DYNAMIC),
    )

    private val logon = RdpLogonInfo(domain = "CORP", username = "elton")

    @Test
    fun `a full sequence yields the session state the server dictated`() = runTest {
        val server = ModelServer()

        val state = RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()

        assertEquals(1007, state.userId)
        assertEquals(1003, state.ioChannelId)
        assertEquals(mapOf("cliprdr" to 1004, "drdynvc" to 1005), state.channels)
        // The desktop size comes from the server's own capabilities, not from what we asked for.
        assertEquals(1024, state.capabilities.desktopWidth)
        assertEquals(768, state.capabilities.desktopHeight)
        assertTrue(state.capabilities.surfaceCommandsSupported)
        assertTrue(state.capabilities.supportedCodecs.contains(RdpCodecId.RemoteFx))
        assertTrue(server.sawConfirmActive, "the client confirmed the capabilities")
        assertTrue(server.sawFontList, "the client finished the finalization sequence")
        assertEquals(SERVER_SHARE_ID, server.confirmedShareId)
    }

    @Test
    fun `a server that skips licensing still reaches the capability exchange`() = runTest {
        val server = ModelServer(skipLicensing = true)

        val state = RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()

        assertEquals(1024, state.capabilities.desktopWidth)
    }

    @Test
    fun `a licensing refusal is reported as an authentication failure`() = runTest {
        val server = ModelServer(licenseErrorCode = 0x00000002)

        assertFailsWith<RdpAuthException> {
            RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()
        }
    }

    @Test
    fun `a server asking for a license is answered, and the sequence continues`() = runTest {
        val server = ModelServer(demandLicense = true)

        val state = RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()

        assertTrue(server.sawLicenseRequest, "the client asked for a new license")
        assertEquals(1024, state.capabilities.desktopWidth)
    }

    @Test
    fun `an error info PDU during the capability exchange explains the refusal`() = runTest {
        // 0x0000000C = the account may not log on remotely, the answer a server gives a user who
        // authenticated fine but is not in Remote Desktop Users.
        val server = ModelServer(errorInfo = 0x0000000C)

        val failure = assertFailsWith<RdpAuthException> {
            RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()
        }

        assertEquals("the account does not have permission to log on remotely", failure.message)
    }

    @Test
    fun `a farm broker redirecting the logon stops the sequence with the target it named`() = runTest {
        val server = ModelServer(redirectTo = "rds01.corp.example.com")

        val redirect = assertFailsWith<RdpRedirectException> {
            RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()
        }

        assertEquals("rds01.corp.example.com", redirect.redirection.targetHost)
        assertEquals("tsv://MS Terminal Services Plugin.1.Employees", redirect.redirection.loadBalanceInfo)
    }

    @Test
    fun `an informational redirection does not interrupt the sequence`() = runTest {
        // LB_NOREDIRECT means "here is your routing token", not "go away": a client that reconnected
        // on it would drop a session that is about to start.
        val server = ModelServer(redirectTo = "rds01.corp.example.com", redirectIsInformational = true)

        val state = RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()

        assertEquals(1024, state.capabilities.desktopWidth)
    }

    @Test
    fun `the profile's image quality reaches the server in the Client Info PDU`() = runTest {
        // The picture is settled here and nowhere else: a quality that stops at the settings object
        // would leave the profile's choice with nothing to act on.
        val server = ModelServer()

        RdpConnectionSequence(
            server.source,
            server.sink,
            settings.copy(imageQuality = RdpImageQuality.High),
            logon,
            FakeLicenseCrypto,
        ).run()

        assertEquals(PERF_ENABLE_FONT_SMOOTHING or PERF_ENABLE_DESKTOP_COMPOSITION, server.clientInfoPerformanceFlags)
    }

    @Test
    fun `a channel the server confirms as a different id fails the connection`() = runTest {
        val server = ModelServer(misconfirmChannel = true)

        assertFailsWith<RdpProtocolException> {
            RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()
        }
    }

    @Test
    fun `a disconnect during the sequence is reported, not read past`() = runTest {
        val server = ModelServer(disconnectAfterAttachUser = true)

        assertFailsWith<RdpProtocolException> {
            RdpConnectionSequence(server.source, server.sink, settings, logon, FakeLicenseCrypto).run()
        }
    }

    /** Answers the connection sequence step by step, as a server does. */
    private inner class ModelServer(
        private val skipLicensing: Boolean = false,
        private val licenseErrorCode: Int = 0x00000007,
        private val demandLicense: Boolean = false,
        private val errorInfo: Int = 0,
        private val misconfirmChannel: Boolean = false,
        private val disconnectAfterAttachUser: Boolean = false,
        private val redirectTo: String? = null,
        private val redirectIsInformational: Boolean = false,
    ) {
        private val outgoing = ArrayDeque<Byte>()
        private var joinedChannels = 0
        var sawConfirmActive = false
            private set
        var sawFontList = false
            private set
        var confirmedShareId = 0
            private set
        var sawLicenseRequest = false
            private set

        /** What the client asked the session to look like (TS_EXTENDED_INFO_PACKET). */
        var clientInfoPerformanceFlags = 0
            private set

        val source = RdpSource { dst, offset, len ->
            repeat(len) { index ->
                dst[offset + index] = outgoing.removeFirstOrNull()
                    ?: throw RdpProtocolException("model server has nothing more to say")
            }
        }

        val sink = RdpSink { bytes -> onClientPacket(bytes) }

        private fun onClientPacket(packet: ByteArray) {
            val payload = X224.dataPayload(packet)
            // MCS connect PDUs are BER-tagged; everything else is a PER domain PDU.
            if (payload.peekU8() == 0x7F) {
                reply(connectResponse())
                return
            }
            when (val pdu = Mcs.parseDomainPdu(payload)) {
                is McsDomainPdu.Data -> onSessionData(pdu)
                else -> onDomainPdu(packet)
            }
        }

        private fun onDomainPdu(packet: ByteArray) {
            val choice = X224.dataPayload(packet).u8() shr 2
            when (choice) {
                MCS_ERECT_DOMAIN -> Unit // no answer
                MCS_ATTACH_USER_REQUEST -> {
                    reply(hex("03 00 00 0b 02 f0 80 2e 00 00 06")) // user id 1007
                    if (disconnectAfterAttachUser) reply(hex("03 00 00 09 02 f0 80 21 80"))
                }

                MCS_CHANNEL_JOIN_REQUEST -> {
                    val requested = CHANNELS[joinedChannels]
                    val confirmed = if (misconfirmChannel && joinedChannels == 2) requested + 1 else requested
                    joinedChannels++
                    reply(channelJoinConfirm(confirmed))
                }
            }
        }

        private fun onSessionData(pdu: McsDomainPdu.Data) {
            val body = pdu.payload
            val first = body.peekU8()
            // Both the Client Info PDU and the licensing ones carry a security header; the flags
            // tell them apart, exactly as they do for the client reading the other direction.
            if (first == 0x40 || first == 0x80) {
                body.skip(4)
                if (first == 0x40) clientInfoPerformanceFlags = readPerformanceFlags(body)
                if (first == 0x80) {
                    // The client's new-licence request: answer as a server with nothing to license.
                    sawLicenseRequest = true
                    reply(licensePdu(LICENSE_ERROR_TYPE))
                    reply(demandActive())
                    return
                }
                if (redirectTo != null) {
                    // A broker answers the Client Info PDU with a redirection instead of licensing.
                    reply(redirectionPdu(redirectTo))
                    if (redirectIsInformational) reply(licensePdu(LICENSE_ERROR_TYPE))
                    if (redirectIsInformational) reply(demandActive())
                    return
                }
                if (demandLicense) {
                    reply(licenseRequestPdu())
                    return
                }
                if (!skipLicensing) reply(licensePdu(LICENSE_ERROR_TYPE))
                if (errorInfo != 0) reply(errorInfoPdu()) else reply(demandActive())
                return
            }
            val header = RdpShare.readControlHeader(body)
            when (header.pduType) {
                RdpShare.PDUTYPE_CONFIRM_ACTIVE -> {
                    sawConfirmActive = true
                    confirmedShareId = body.u32le()
                }

                RdpShare.PDUTYPE_DATA -> {
                    val data = RdpShare.readDataHeader(body)
                    if (data.pduType2 == RdpShare.PDUTYPE2_FONT_LIST) {
                        sawFontList = true
                        reply(fontMap())
                    }
                }
            }
        }

        private fun reply(packet: ByteArray) {
            for (byte in packet) outgoing.addLast(byte)
        }

        private fun connectResponse(): ByteArray {
            val serverBlocks = RdpWriter(64).apply {
                u16le(0x0C01).u16le(12).u32le(0x00080004).u32le(RdpSecurityProtocol.HYBRID) // SC_CORE
                // SC_NET: header + MCSChannelId + channelCount + two ids (an even count needs no pad)
                u16le(0x0C03).u16le(12)
                u16le(1003) // MCSChannelId
                u16le(2) // channelCount
                u16le(1004).u16le(1005)
                u16le(0x0C02).u16le(12).u32le(0).u32le(0) // SC_SECURITY: no legacy encryption
            }.toByteArray()

            val gcc = RdpWriter(serverBlocks.size + 32).apply {
                u8(0)
                Per.objectIdentifier(this, intArrayOf(0, 0, 20, 124, 0, 1))
                Per.length(this, serverBlocks.size + 14)
                u8(0x14) // conferenceCreateResponse, userData present
                u16be(0x760A) // nodeID
                u8(1).u8(1) // tag length, tag
                u8(0) // result: success
                u8(1) // number of user data sets
                u8(0xC0) // h221NonStandard
                u8(0).bytes("McDn".encodeToByteArray())
                Per.length(this, serverBlocks.size)
                bytes(serverBlocks)
            }.toByteArray()

            val body = RdpWriter(gcc.size + 64).apply {
                bytes(byteArrayOf(0x0A, 0x01, 0x00)) // result: rt-successful
                bytes(Ber.integer(0)) // calledConnectId
                bytes(
                    Ber.sequence(
                        RdpWriter(32).apply {
                            bytes(Ber.integer(34)).bytes(Ber.integer(3)).bytes(Ber.integer(0))
                            bytes(Ber.integer(1)).bytes(Ber.integer(0)).bytes(Ber.integer(1))
                            bytes(Ber.integer(0xFFF8)).bytes(Ber.integer(2))
                        }.toByteArray(),
                    ),
                )
                bytes(Ber.octetString(gcc))
            }.toByteArray()

            val connectResponse = RdpWriter(body.size + 8).apply {
                Ber.applicationTag(this, 102, body.size)
                bytes(body)
            }.toByteArray()
            return X224.dataHeader(connectResponse.size) + connectResponse
        }

        private fun channelJoinConfirm(channelId: Int): ByteArray {
            val body = RdpWriter(8).apply {
                u8(15 shl 2 or 0x02) // ChannelJoinConfirm with a channelId present
                u8(0) // result
                Per.userId(this, 1007)
                u16be(channelId)
                u16be(channelId)
            }.toByteArray()
            return X224.dataHeader(body.size) + body
        }

        /**
         * A Server Licence Request with an X.509 chain around a stand-in server key. The other
         * certificate format a server may send, the proprietary one, has to carry a real Terminal
         * Services signature, which [FakeLicenseCrypto] cannot produce — `LicenseExchangeTest`
         * covers that form with real crypto.
         */
        private fun licenseRequestPdu(): ByteArray {
            val leaf = ByteArray(48) { (it + 1).toByte() } // any bytes: the fake crypto reads a key out of them
            val certificate = RdpWriter(leaf.size + 16).apply {
                u32le(2) // CERT_CHAIN_VERSION_2
                u32le(1) // one certificate in the chain: the server's own
                u32le(leaf.size).bytes(leaf)
            }.toByteArray()
            val body = RdpWriter(certificate.size + 96).apply {
                zeros(32) // ServerRandom
                u32le(0x00040000).u32le(2).bytes(byteArrayOf(1, 2)).u32le(2).bytes(byteArrayOf(3, 4)) // ProductInfo
                u16le(0x000D).u16le(4).u32le(1) // KeyExchangeList
                u16le(0x0003).u16le(certificate.size).bytes(certificate)
                u32le(0) // ScopeList: no scopes
            }.toByteArray()

            val pdu = RdpWriter(body.size + 8).apply {
                RdpSecurityHeader.write(this, RdpSecurityHeader.SEC_LICENSE_PKT)
                u8(LICENSE_REQUEST_TYPE)
                u8(3) // preamble flags
                u16le(body.size + 4)
                bytes(body)
            }.toByteArray()
            return Mcs.sendDataRequest(1002, 1003, pdu)
        }

        private fun licensePdu(messageType: Int): ByteArray {
            val body = RdpWriter(32).apply {
                RdpSecurityHeader.write(this, RdpSecurityHeader.SEC_LICENSE_PKT)
                u8(messageType)
                u8(3) // preamble flags
                u16le(16) // wMsgSize
                u32le(licenseErrorCode)
                u32le(0).u32le(0) // state transition + blob
            }.toByteArray()
            return Mcs.sendDataRequest(1002, 1003, body)
        }

        /** Standard-security form: the redirection travels where the security header would be. */
        private fun redirectionPdu(target: String): ByteArray {
            val token = "tsv://MS Terminal Services Plugin.1.Employees".encodeToByteArray()
            val body = RdpWriter(128).apply {
                u16le(ServerRedirection.SEC_REDIRECTION_PKT)
                u16le(0) // overall length, patched below
                u32le(REDIRECTED_SESSION_ID)
                var flags = ServerRedirection.LB_TARGET_FQDN or ServerRedirection.LB_LOAD_BALANCE_INFO
                if (redirectIsInformational) flags = flags or ServerRedirection.LB_NOREDIRECT
                u32le(flags)
                u32le(token.size)
                bytes(token)
                val fqdn = RdpWriter(target.length * 2 + 2).utf16le(target, nullTerminated = true).toByteArray()
                u32le(fqdn.size)
                bytes(fqdn)
                patchU16le(2, size)
            }.toByteArray()
            return Mcs.sendDataRequest(1002, 1003, body)
        }

        private fun demandActive(): ByteArray {
            val capabilities = RdpWriter(256).apply {
                u16le(CapabilitySetType.BITMAP).u16le(28)
                u16le(32) // preferredBitsPerPixel
                u16le(1).u16le(1).u16le(1)
                u16le(1024).u16le(768)
                u16le(0)
                u16le(1) // desktopResizeFlag
                u16le(1).u8(0).u8(0).u16le(1).u16le(0)
                u16le(CapabilitySetType.SURFACE_COMMANDS).u16le(12)
                u32le(Capabilities.SURFCMDS_SET_SURFACE_BITS or Capabilities.SURFCMDS_FRAME_MARKER)
                u32le(0)
                u16le(CapabilitySetType.BITMAP_CODECS).u16le(4 + 20)
                u8(1)
                bytes(Capabilities.GUID_REMOTEFX)
                u8(3) // codec id
                u16le(0) // no properties
            }.toByteArray()

            val body = RdpWriter(capabilities.size + 32).apply {
                u32le(SERVER_SHARE_ID)
                u16le(4) // lengthSourceDescriptor
                u16le(capabilities.size + 4)
                bytes("RDP ".encodeToByteArray())
                u16le(3) // numberCapabilities
                u16le(0) // pad
                bytes(capabilities)
            }.toByteArray()

            val pdu = RdpWriter(body.size + 8).apply {
                RdpShare.controlHeader(this, body.size + 6, RdpShare.PDUTYPE_DEMAND_ACTIVE, 1002)
                bytes(body)
            }.toByteArray()
            return Mcs.sendDataRequest(1002, 1003, pdu)
        }

        private fun errorInfoPdu(): ByteArray =
            Mcs.sendDataRequest(
                1002,
                1003,
                RdpShare.dataPdu(
                    SERVER_SHARE_ID,
                    1002,
                    RdpShare.PDUTYPE2_SET_ERROR_INFO,
                    RdpWriter(4).u32le(errorInfo).toByteArray(),
                ),
            )

        private fun fontMap(): ByteArray =
            Mcs.sendDataRequest(
                1002,
                1003,
                RdpShare.dataPdu(
                    SERVER_SHARE_ID,
                    1002,
                    RdpShare.PDUTYPE2_FONT_MAP,
                    RdpWriter(8).u16le(0).u16le(0).u16le(3).u16le(4).toByteArray(),
                ),
            )
    }

    private companion object {
        const val SERVER_SHARE_ID = 0x000103EA
        const val MCS_ERECT_DOMAIN = 1
        const val MCS_ATTACH_USER_REQUEST = 10
        const val MCS_CHANNEL_JOIN_REQUEST = 14
        const val LICENSE_ERROR_TYPE = 0xFF
        const val LICENSE_REQUEST_TYPE = 0x01
        const val REDIRECTED_SESSION_ID = 0x0000002A
        const val PERF_ENABLE_FONT_SMOOTHING = 0x00000080
        const val PERF_ENABLE_DESKTOP_COMPOSITION = 0x00000100

        /** The order the client joins: user channel, I/O channel, then the virtual channels. */
        val CHANNELS = intArrayOf(1007, 1003, 1004, 1005)
    }
}
