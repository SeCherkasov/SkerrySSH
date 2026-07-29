package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer

/**
 * The live session's protocol loop: reads server PDUs (fast-path updates and slow-path share PDUs),
 * applies graphics to [framebuffer] and turns everything else into [RdpUpdate]s, while [send*]
 * methods write input back.
 *
 * Pure state machine over an injected source/sink, like `RfbCodec` — the socket lives in the
 * transport. One rule keeps the stream healthy: a frame-end marker is acknowledged before the next
 * read, because the server stops sending once its allowance of unacknowledged frames is used up.
 */
class RdpSessionCodec(
    private val source: RdpSource,
    private val sink: RdpSink,
    val framebuffer: RemoteFramebuffer,
    private var state: RdpSessionState,
    private val settings: RdpClientSettings,
    private val logon: RdpLogonInfo,
    remoteFx: RemoteFxDecoder? = null,
) {
    private val palette = SessionPalette()
    private val dropped = DroppedGraphics()
    private val fastPath = FastPathDecoder(framebuffer, palette, dropped)
    private val surfaces = SurfaceDecoder(RdpCodecs(remoteFx))

    /** Whether a repaint asked for after dropped graphics is still unanswered; see [repaintDropped]. */
    private var repaintPending = false

    /** The desktop size the server is currently serving. */
    val desktopWidth: Int get() = state.capabilities.desktopWidth
    val desktopHeight: Int get() = state.capabilities.desktopHeight

    /**
     * Read one packet and return the updates it produced (possibly none — synchronize PDUs and
     * session-info PDUs carry nothing the UI acts on).
     */
    suspend fun readMessage(): List<RdpUpdate> {
        val packet = Tpkt.readPacket(source)
        val updates = if (Tpkt.isFastPath(packet[0].toInt() and 0xFF)) {
            fastPath.decode(packet, surfaces)
        } else {
            slowPath(packet)
        }
        // Acknowledge completed frames before returning: the server paces itself on these.
        for (update in updates) {
            if (update is RdpUpdate.Frame && !update.begin) acknowledgeFrame(update.frameId)
            // Pixels landed, so a repaint that was asked for has been answered.
            if (update is RdpUpdate.Region && update.rects.isNotEmpty()) repaintPending = false
        }
        if (dropped.take()) repaintDropped()
        return updates
    }

    /**
     * Ask the server to send, as bitmaps, what it drew with an update this client had to skip.
     * One repaint is asked for at a time: a server that ignores the negotiation once tends to do it
     * every frame, and a full-screen repaint per dropped update is a traffic storm rather than a fix.
     */
    private suspend fun repaintDropped() {
        if (repaintPending) return
        repaintPending = true
        requestRefresh(listOf(RdpRect(0, 0, desktopWidth, desktopHeight)))
    }

    private suspend fun slowPath(packet: ByteArray): List<RdpUpdate> {
        val pdu = when (val domain = Mcs.parseDomainPdu(X224.dataPayload(packet))) {
            is McsDomainPdu.Data -> domain
            is McsDomainPdu.Disconnect -> return listOf(RdpUpdate.Closed(cleanExit = true))
            is McsDomainPdu.Other -> return emptyList()
        }
        if (pdu.channelId != state.ioChannelId) {
            // Virtual channel traffic (clipboard, dynamic channels) is routed by the caller.
            channelData(pdu.channelId, pdu.payload)
            return emptyList()
        }
        val header = RdpShare.readControlHeader(pdu.payload)
        return when (header.pduType) {
            RdpShare.PDUTYPE_DATA -> dataPdu(pdu.payload)
            RdpShare.PDUTYPE_DEACTIVATE_ALL -> reactivate()
            RdpShare.PDUTYPE_SERVER_REDIRECT ->
                listOf(RdpUpdate.Closed(cleanExit = true, reason = "the server redirected the session"))

            else -> emptyList()
        }
    }

    private fun dataPdu(reader: RdpReader): List<RdpUpdate> {
        val data = RdpShare.readDataHeader(reader)
        if (data.compressedType and COMPRESSION_USED != 0) {
            throw RdpProtocolException("compressed share data, which this client never negotiated")
        }
        return when (data.pduType2) {
            RdpShare.PDUTYPE2_UPDATE -> slowPathUpdate(reader)
            RdpShare.PDUTYPE2_POINTER -> pointerPdu(reader)
            RdpShare.PDUTYPE2_SET_ERROR_INFO -> {
                val info = reader.u32le()
                if (info == 0) emptyList() else listOf(RdpUpdate.Closed(true, rdpErrorInfoText(info)))
            }

            RdpShare.PDUTYPE2_PLAY_SOUND -> listOf(RdpUpdate.Bell)
            else -> emptyList()
        }
    }

    /** Slow-path graphics: the same payloads as fast-path, wrapped in a share data PDU. */
    private fun slowPathUpdate(reader: RdpReader): List<RdpUpdate> = when (reader.u16le()) {
        UPDATETYPE_BITMAP -> listOf(BitmapUpdate.apply(reader, framebuffer, palette.colors))
        UPDATETYPE_PALETTE -> {
            palette.colors = BitmapUpdate.readPalette(reader)
            emptyList()
        }

        // Skipped and repainted rather than fatal, for the reason spelled out in [FastPathDecoder].
        UPDATETYPE_ORDERS -> {
            dropped.record()
            emptyList()
        }

        else -> emptyList()
    }

    private fun pointerPdu(reader: RdpReader): List<RdpUpdate> {
        val messageType = reader.u16le()
        reader.skip(2) // pad2Octets
        return when (messageType) {
            PTR_MSGTYPE_SYSTEM -> listOf(RdpUpdate.PointerVisible(reader.u32le() != SYSPTR_NULL))
            PTR_MSGTYPE_POSITION -> listOf(RdpUpdate.PointerPosition(reader.u16le(), reader.u16le()))
            PTR_MSGTYPE_COLOR -> listOf(PointerUpdate.colorPointer(reader))
            PTR_MSGTYPE_POINTER -> listOf(PointerUpdate.newPointer(reader))
            PTR_MSGTYPE_LARGE_POINTER -> listOf(PointerUpdate.largePointer(reader))
            else -> emptyList()
        }
    }

    /**
     * The server tore the share down and will demand capabilities again — how a resolution change
     * is delivered. The sequence is re-run in place, so the session survives it with a new size
     * instead of dropping the user back to the host list.
     */
    private suspend fun reactivate(): List<RdpUpdate> {
        val sequence = RdpConnectionSequence(source, sink, settings, logon)
        state = sequence.reactivate(state)
        framebuffer.resize(state.capabilities.desktopWidth, state.capabilities.desktopHeight)
        return listOf(RdpUpdate.Resize(state.capabilities.desktopWidth, state.capabilities.desktopHeight))
    }

    /**
     * Record a desktop size that changed without a reactivation — the graphics pipeline and the
     * display control channel resize the session in place, and nothing else updates the capabilities
     * this class answers [desktopWidth]/[desktopHeight] and builds its PDUs from.
     */
    fun desktopResized(width: Int, height: Int) {
        state = state.copy(capabilities = state.capabilities.copy(desktopWidth = width, desktopHeight = height))
    }

    /**
     * Virtual channel payloads, handed to whoever registered for that channel. Suspending because a
     * channel usually answers on the spot — the clipboard replies to a format list before the next
     * PDU is read.
     */
    var onChannelData: suspend (channelId: Int, data: ByteArray) -> Unit = { _, _ -> }

    /**
     * Chunks of a channel message that has not arrived whole yet, by channel.
     *
     * A virtual channel message longer than the chunk size is split, and only the first chunk
     * carries the total length. Handing each chunk over on its own would leave every channel to
     * reassemble for itself — and would quietly lose any clipboard paste past 1600 bytes, which is
     * the size at which a paste becomes worth having.
     */
    private val channelBuffers = mutableMapOf<Int, RdpWriter>()

    private suspend fun channelData(channelId: Int, reader: RdpReader) {
        // Channel PDU header (MS-RDPBCGR 2.2.6.1.1): total length and flags, then the chunk.
        if (reader.remaining < 8) return
        val totalLength = reader.u32le()
        val flags = reader.u32le()
        val chunk = reader.rest()
        val first = flags and CHANNEL_FLAG_FIRST != 0
        val last = flags and CHANNEL_FLAG_LAST != 0
        if (first && last) {
            channelBuffers.remove(channelId)
            onChannelData(channelId, chunk)
            return
        }
        if (first) {
            if (totalLength < 0 || totalLength > MAX_CHANNEL_MESSAGE) {
                throw RdpProtocolException("a channel message of $totalLength bytes")
            }
            channelBuffers[channelId] = RdpWriter(minOf(totalLength, INITIAL_CHANNEL_BUFFER))
        }
        // A continuation with no beginning belongs to a message from before this client cared.
        val buffer = channelBuffers[channelId] ?: return
        buffer.bytes(chunk)
        if (buffer.size > MAX_CHANNEL_MESSAGE) {
            channelBuffers.remove(channelId)
            throw RdpProtocolException("a channel message past $MAX_CHANNEL_MESSAGE bytes")
        }
        if (!last) return
        channelBuffers.remove(channelId)
        onChannelData(channelId, buffer.toByteArray())
    }

    // ---- client → server ----

    suspend fun sendKey(scancode: Int, down: Boolean, extended: Boolean = false) =
        sink.write(RdpInput.key(scancode, down, extended))

    suspend fun sendUnicode(code: Int, down: Boolean) = sink.write(RdpInput.unicode(code, down))

    suspend fun sendPointerMove(x: Int, y: Int) = sink.write(RdpInput.mouseMove(x, y))

    suspend fun sendPointerButton(button: RdpMouseButton, down: Boolean, x: Int, y: Int) =
        sink.write(RdpInput.mouseButton(button, down, x, y))

    suspend fun sendWheel(clicks: Int, axis: RdpWheelAxis, x: Int, y: Int) =
        sink.write(RdpInput.mouseWheel(clicks, axis, x, y))

    suspend fun sendLockKeys(scroll: Boolean, num: Boolean, caps: Boolean, kana: Boolean = false) =
        sink.write(RdpInput.syncLockKeys(scroll, num, caps, kana))

    /**
     * Ask the server to repaint [rects]. Both this and [setOutputVisible] are optional PDUs the
     * server opts into in its General capability set (MS-RDPBCGR 2.2.11.2 / 2.2.11.3) — sending one
     * it never advertised is a protocol violation, and a Windows host answers it by tearing the
     * connection down, so an unsupported request is dropped here rather than on the wire.
     */
    suspend fun requestRefresh(rects: List<RdpRect>) {
        if (!state.capabilities.refreshRectSupported) return
        sendShareData(RdpClientPdus.refreshRect(state.capabilities.shareId, state.userId, rects))
    }

    /** Tell the server whether to keep streaming this session; optional, see [requestRefresh]. */
    suspend fun setOutputVisible(visible: Boolean) {
        if (!state.capabilities.suppressOutputSupported) return
        sendShareData(
            RdpClientPdus.suppressOutput(
                state.capabilities.shareId,
                state.userId,
                visible,
                state.capabilities.desktopWidth,
                state.capabilities.desktopHeight,
            ),
        )
    }

    suspend fun requestShutdown() =
        sendShareData(RdpClientPdus.shutdownRequest(state.capabilities.shareId, state.userId))

    /** Send [data] on a named virtual channel (clipboard, dynamic channels). */
    suspend fun sendChannelData(channelName: String, data: ByteArray) {
        val channelId = state.channels[channelName] ?: return
        var offset = 0
        while (offset < data.size || offset == 0) {
            val chunk = minOf(CHANNEL_CHUNK_SIZE, data.size - offset)
            var flags = 0
            if (offset == 0) flags = flags or CHANNEL_FLAG_FIRST
            if (offset + chunk >= data.size) flags = flags or CHANNEL_FLAG_LAST
            val body = RdpWriter(chunk + 8)
                .u32le(data.size)
                .u32le(flags)
                .bytes(data, offset, chunk)
                .toByteArray()
            sink.write(Mcs.sendDataRequest(state.userId, channelId, body))
            offset += chunk
            if (chunk == 0) break
        }
    }

    private suspend fun acknowledgeFrame(frameId: Int) {
        if (!state.capabilities.frameAcknowledgeSupported) return
        sendShareData(RdpClientPdus.frameAcknowledge(state.capabilities.shareId, state.userId, frameId))
    }

    private suspend fun sendShareData(pdu: ByteArray) =
        sink.write(Mcs.sendDataRequest(state.userId, state.ioChannelId, pdu))

    private companion object {

        const val UPDATETYPE_ORDERS = 0x0000
        const val UPDATETYPE_BITMAP = 0x0001
        const val UPDATETYPE_PALETTE = 0x0002

        const val PTR_MSGTYPE_SYSTEM = 0x0001
        const val PTR_MSGTYPE_POSITION = 0x0003
        const val PTR_MSGTYPE_COLOR = 0x0006
        const val PTR_MSGTYPE_POINTER = 0x0008
        const val PTR_MSGTYPE_LARGE_POINTER = 0x0009
        const val SYSPTR_NULL = 0x00000000

        const val COMPRESSION_USED = 0x20

        const val CHANNEL_FLAG_FIRST = 0x00000001
        const val CHANNEL_FLAG_LAST = 0x00000002

        /** Matches the VCChunkSize advertised in the Virtual Channel capability set. */
        const val CHANNEL_CHUNK_SIZE = 1600

        /** Past this a channel message is not one this client has any use for. */
        const val MAX_CHANNEL_MESSAGE = 16 * 1024 * 1024
        const val INITIAL_CHANNEL_BUFFER = 64 * 1024
    }
}
