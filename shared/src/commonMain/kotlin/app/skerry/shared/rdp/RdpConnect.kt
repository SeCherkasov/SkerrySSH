package app.skerry.shared.rdp

/**
 * Everything the session needs once the connection sequence has run: who we are in the MCS domain,
 * which share the server put us in, and what it can do.
 */
data class RdpSessionState(
    val userId: Int,
    val ioChannelId: Int,
    val channels: Map<String, Int>,
    val capabilities: ServerCapabilities,
)

/**
 * The RDP connection sequence (MS-RDPBCGR 1.3.1.1) from MCS connect to the font map: basic settings
 * exchange, channel connection, licensing, capability exchange and finalization.
 *
 * Pure protocol over an injected [RdpSource]/[RdpSink] — the socket, TLS and CredSSP have already
 * happened by the time this runs, which is what lets the whole sequence be driven by a test.
 */
class RdpConnectionSequence(
    private val source: RdpSource,
    private val sink: RdpSink,
    private val settings: RdpClientSettings,
    private val logon: RdpLogonInfo,
    /**
     * Licensing crypto (MD5/SHA-1/RSA), supplied by the transport. The default refuses every call,
     * which is right for the one caller that cannot license — the reactivation sequence inside a
     * session that already holds a licence.
     */
    licenseCrypto: RdpLicenseCrypto = UnavailableLicenseCrypto,
) {
    private val license = LicenseExchange(licenseCrypto, logon, settings.clientName)

    /**
     * Run the sequence and return the live session's state.
     *
     * @throws RdpAuthException the logon was refused
     * @throws RdpProtocolException the server departed from the sequence
     */
    suspend fun run(): RdpSessionState {
        val serverData = basicSettingsExchange()
        val userId = channelConnection(serverData)
        val channels = settings.channels.zip(serverData.channelIds.toTypedArray()).toMap()

        sink.write(
            Mcs.sendDataRequest(
                userId,
                serverData.ioChannelId,
                // The sound channel being in the request is what makes audio wanted; the flag inside
                // the Client Info PDU has to agree, or the server keeps the channel shut.
                ClientInfo.pdu(
                    logon,
                    audioPlayback = settings.channels.contains(RdpClientSettings.CHANNEL_AUDIO),
                    quality = settings.imageQuality,
                ),
            ),
        )
        awaitLicensing(userId, serverData.ioChannelId)

        val capabilities = awaitDemandActive(serverData.ioChannelId)
        sink.write(
            Mcs.sendDataRequest(
                userId,
                serverData.ioChannelId,
                ClientCapabilities.confirmActive(
                    shareId = capabilities.shareId,
                    userId = userId,
                    width = capabilities.desktopWidth,
                    height = capabilities.desktopHeight,
                    remoteFx = capabilities.supportedCodecs.contains(RdpCodecId.RemoteFx),
                ),
            ),
        )
        finalize(userId, serverData.ioChannelId, capabilities.shareId)
        return RdpSessionState(userId, serverData.ioChannelId, channels, capabilities)
    }

    /**
     * Re-run the capability exchange after a Deactivate All PDU, keeping the MCS session. This is
     * how a server delivers a resolution change: it tears the share down and demands capabilities
     * again, and a client that treated it as a disconnect would drop the user out of a session that
     * is still perfectly alive.
     */
    suspend fun reactivate(previous: RdpSessionState): RdpSessionState {
        val capabilities = awaitDemandActive(previous.ioChannelId)
        sink.write(
            Mcs.sendDataRequest(
                previous.userId,
                previous.ioChannelId,
                ClientCapabilities.confirmActive(
                    shareId = capabilities.shareId,
                    userId = previous.userId,
                    width = capabilities.desktopWidth,
                    height = capabilities.desktopHeight,
                    remoteFx = capabilities.supportedCodecs.contains(RdpCodecId.RemoteFx),
                ),
            ),
        )
        finalize(previous.userId, previous.ioChannelId, capabilities.shareId)
        return previous.copy(capabilities = capabilities)
    }

    private suspend fun basicSettingsExchange(): ServerUserData {
        val userData = Gcc.clientUserData(settings)
        // Connect-Initial is a bare BER structure; like every slow-path PDU it travels inside an
        // X.224 data TPDU.
        val connectInitial = McsConnect.connectInitial(Gcc.conferenceCreateRequest(userData))
        sink.write(X224.dataHeader(connectInitial.size) + connectInitial)
        return McsConnect.parseConnectResponse(X224.dataPayload(Tpkt.readPacket(source)))
    }

    /** Erect the domain, attach as a user, and join every channel the session will speak on. */
    private suspend fun channelConnection(serverData: ServerUserData): Int {
        sink.write(Mcs.erectDomainRequest())
        sink.write(Mcs.attachUserRequest())
        val userId = Mcs.parseAttachUserConfirm(X224.dataPayload(Tpkt.readPacket(source)))

        // The user channel goes first, then the I/O channel, then the virtual channels — the order
        // a server expects, and the order its confirms come back in.
        val toJoin = buildList {
            add(userId)
            add(serverData.ioChannelId)
            addAll(serverData.channelIds.toList())
        }
        for (channelId in toJoin) {
            sink.write(Mcs.channelJoinRequest(userId, channelId))
            val confirmed = Mcs.parseChannelJoinConfirm(X224.dataPayload(Tpkt.readPacket(source)))
            if (confirmed != channelId) {
                throw RdpProtocolException("server confirmed channel $confirmed instead of $channelId")
            }
        }
        return userId
    }

    /**
     * Consume the licensing phase (MS-RDPBCGR 2.2.1.12). A server with nothing to license answers
     * "valid client" straight away, which is the case for every workstation and for a terminal
     * server in its grace period. A server that does issue per-device licences instead answers the
     * request with a platform challenge, and [LicenseExchange] carries that through to the licence.
     */
    private suspend fun awaitLicensing(userId: Int, ioChannelId: Int) {
        while (true) {
            val (channelId, reader) = readSlowPathPdu() ?: continue
            if (channelId != ioChannelId) continue
            // Under TLS the basic security header is absent from every PDU except this phase's
            // (MS-RDPBCGR 2.2.8.1.1.2), so the only way to tell a licensing PDU from a server that
            // skipped licensing entirely is to read the four bytes and put them back when they turn
            // out to be a Share Control header.
            val flags = RdpSecurityHeader.readFlags(reader)
            if (flags and ServerRedirection.SEC_REDIRECTION_PKT != 0) {
                // A farm's broker answers the logon with this instead of licensing: the four bytes
                // just read were the packet's own Flags/Length pair, so the body follows directly.
                redirect(ServerRedirection.parseBody(reader))
                continue
            }
            if (flags and RdpSecurityHeader.SEC_LICENSE_PKT == 0) {
                reader.rewind(4)
                pendingPdu = reader
                return
            }
            when (val messageType = reader.u8()) {
                LicenseExchange.ERROR_ALERT -> {
                    reader.u8() // flags
                    reader.u16le() // wMsgSize
                    val errorCode = reader.u32le()
                    if (errorCode == LICENSE_STATUS_VALID_CLIENT || errorCode == LICENSE_ERR_NO_LICENSE_SERVER) {
                        return
                    }
                    throw RdpAuthException("the server refused the session license (error $errorCode)")
                }

                LicenseExchange.LICENSE_REQUEST -> {
                    reader.skip(3) // preamble: version flags and wMsgSize
                    sink.write(Mcs.sendDataRequest(userId, ioChannelId, license.newLicenseRequest(reader)))
                }

                LicenseExchange.PLATFORM_CHALLENGE -> {
                    reader.skip(3)
                    sink.write(
                        Mcs.sendDataRequest(userId, ioChannelId, license.platformChallengeResponse(reader)),
                    )
                }

                // The licence itself: nothing to store (a fresh one is issued next time), and the
                // server moves on to the capability exchange right after sending it.
                LicenseExchange.NEW_LICENSE, LicenseExchange.UPGRADE_LICENSE -> return

                else -> throw RdpProtocolException("unexpected licensing message $messageType")
            }
        }
    }

    private suspend fun awaitDemandActive(ioChannelId: Int): ServerCapabilities {
        while (true) {
            val carried = pendingPdu
            pendingPdu = null
            val reader = carried ?: readSlowPathPdu()?.takeIf { it.first == ioChannelId }?.second ?: continue
            val header = RdpShare.readControlHeader(reader)
            when (header.pduType) {
                RdpShare.PDUTYPE_DEMAND_ACTIVE -> return Capabilities.parseDemandActive(reader)
                // A server can still send data PDUs here (an error info PDU explaining a refused
                // logon is the common one); anything else is skipped until capabilities arrive.
                RdpShare.PDUTYPE_DATA -> checkForErrorInfo(reader)
                RdpShare.PDUTYPE_SERVER_REDIRECT -> {
                    reader.skip(2) // pad2Octets, then the packet itself (MS-RDPBCGR 2.2.13.3.1)
                    redirect(ServerRedirection.parse(reader))
                }

                else -> Unit
            }
        }
    }

    /**
     * Hand a redirection to the transport, which reconnects to the target it names. An
     * informational one (LB_NOREDIRECT) is not a redirection at all — the broker is only updating
     * the client's routing token — so the sequence carries on with the server it is already talking
     * to. [lastRedirection] keeps that token for whoever asks after the sequence finishes.
     */
    private fun redirect(redirection: RdpRedirection) {
        if (redirection.informationalOnly) {
            lastRedirection = redirection
            return
        }
        throw RdpRedirectException(redirection)
    }

    /** Report a refused logon in the server's own words instead of timing out on the next read. */
    private fun checkForErrorInfo(reader: RdpReader) {
        val data = RdpShare.readDataHeader(reader)
        if (data.pduType2 != RdpShare.PDUTYPE2_SET_ERROR_INFO) return
        val errorInfo = reader.u32le()
        if (errorInfo != 0) throw RdpAuthException(rdpErrorInfoText(errorInfo))
    }

    /**
     * Finalization (MS-RDPBCGR 1.3.1.1, steps 12-15): synchronize, take control of the share and
     * send an empty font list. The server answers with its own synchronize, control and font map;
     * the font map is what marks the session as live.
     */
    private suspend fun finalize(userId: Int, ioChannelId: Int, shareId: Int) {
        suspend fun send(pduType2: Int, body: ByteArray) {
            sink.write(Mcs.sendDataRequest(userId, ioChannelId, RdpShare.dataPdu(shareId, userId, pduType2, body)))
        }

        send(
            RdpShare.PDUTYPE2_SYNCHRONIZE,
            RdpWriter(4).u16le(SYNCMSGTYPE_SYNC).u16le(ioChannelId).toByteArray(),
        )
        send(RdpShare.PDUTYPE2_CONTROL, controlPdu(CTRLACTION_COOPERATE))
        send(RdpShare.PDUTYPE2_CONTROL, controlPdu(CTRLACTION_REQUEST_CONTROL))
        send(
            RdpShare.PDUTYPE2_FONT_LIST,
            RdpWriter(8).u16le(0).u16le(0).u16le(FONTLIST_FIRST or FONTLIST_LAST).u16le(FONT_ENTRY_SIZE)
                .toByteArray(),
        )

        // Wait for the font map: until it arrives the server is still finishing the share, and input
        // sent before it is discarded.
        while (true) {
            val (channelId, reader) = readSlowPathPdu() ?: continue
            if (channelId != ioChannelId) continue
            val header = RdpShare.readControlHeader(reader)
            if (header.pduType == RdpShare.PDUTYPE_SERVER_REDIRECT) {
                reader.skip(2) // pad2Octets
                redirect(ServerRedirection.parse(reader))
                continue
            }
            if (header.pduType != RdpShare.PDUTYPE_DATA) continue
            val data = RdpShare.readDataHeader(reader)
            when (data.pduType2) {
                RdpShare.PDUTYPE2_FONT_MAP -> return
                RdpShare.PDUTYPE2_SET_ERROR_INFO -> {
                    val errorInfo = reader.u32le()
                    if (errorInfo != 0) throw RdpAuthException(rdpErrorInfoText(errorInfo))
                }

                else -> Unit
            }
        }
    }

    private fun controlPdu(action: Int): ByteArray =
        RdpWriter(8).u16le(action).u16le(0).u32le(0).toByteArray() // action, grantId, controlId

    /**
     * Read one slow-path PDU, returning its channel and body. Fast-path packets can already be
     * arriving (a server starts drawing as soon as it has the capabilities), so they are skipped
     * here rather than mistaken for a malformed TPKT; the session loop reads them properly.
     */
    private suspend fun readSlowPathPdu(): Pair<Int, RdpReader>? {
        val packet = Tpkt.readPacket(source)
        if (Tpkt.isFastPath(packet[0].toInt() and 0xFF)) return null
        return when (val pdu = Mcs.parseDomainPdu(X224.dataPayload(packet))) {
            is McsDomainPdu.Data -> pdu.channelId to pdu.payload
            is McsDomainPdu.Disconnect -> throw RdpProtocolException("server closed the MCS domain (reason ${pdu.reason})")
            is McsDomainPdu.Other -> null
        }
    }

    /** A PDU read during one phase that turned out to belong to the next one. */
    private var pendingPdu: RdpReader? = null

    /** The last informational redirection this sequence saw, if any (see [redirect]). */
    var lastRedirection: RdpRedirection? = null
        private set

    private companion object {
        const val LICENSE_STATUS_VALID_CLIENT = 0x00000007
        const val LICENSE_ERR_NO_LICENSE_SERVER = 0x00000006

        const val SYNCMSGTYPE_SYNC = 1
        const val CTRLACTION_REQUEST_CONTROL = 0x0001
        const val CTRLACTION_COOPERATE = 0x0004
        const val FONTLIST_FIRST = 0x0001
        const val FONTLIST_LAST = 0x0002
        const val FONT_ENTRY_SIZE = 0x0032
    }
}

/**
 * The Set Error Info PDU codes a user can act on (MS-RDPBCGR 2.2.5.1.1). Everything else keeps its
 * hex value — better an unfamiliar code than a wrong explanation.
 */
fun rdpErrorInfoText(code: Int): String = when (code) {
    0x00000001 -> "the session was ended by an administrator"
    0x00000002 -> "the session was disconnected by an administrator"
    0x00000003 -> "the session ended because it was idle"
    0x00000004 -> "the session ended because it reached its time limit"
    0x00000005 -> "another user logged on and took the session"
    0x00000009 -> "the server ran out of memory"
    0x0000000A -> "the server denied the connection"
    0x0000000C -> "the account does not have permission to log on remotely"
    0x00000010 -> "the license expired"
    0x00000012 -> "the server is out of connection licenses"
    else -> "the server ended the session (0x${code.toUInt().toString(16)})"
}
