package app.skerry.shared.vnc

/**
 * RFB streaming-extension messages (ContinuousUpdates + Fence, V-01), sliced out of [RfbCodec] the
 * way RfbEncodings.kt holds the rectangle decoders: standalone functions over [VncSource]/[VncSink],
 * so the codec keeps only the message dispatch and the session state.
 */

/** EnableContinuousUpdates (150): stream updates for the whole given region, unasked. */
internal suspend fun writeEnableContinuousUpdates(sink: VncSink, width: Int, height: Int) {
    val msg = ByteArray(10)
    msg[0] = RfbCodec.MSG_END_OF_CONTINUOUS_UPDATES.toByte() // 150 both ways: EnableContinuousUpdates
    msg[1] = 1 // enable; x/y stay 0 — the region is the whole framebuffer
    msg[6] = (width ushr 8).toByte(); msg[7] = width.toByte()
    msg[8] = (height ushr 8).toByte(); msg[9] = height.toByte()
    sink.write(msg)
}

/**
 * A fence (248), type byte already consumed. One with the Request flag must be echoed back with
 * Request cleared and the flags cut down to the modes we honour: the codec reads and applies
 * messages serially, so BlockBefore/BlockAfter hold by construction; SyncNext is not supported and
 * is dropped, as the spec requires for unsupported modes. One without Request would answer a fence
 * WE sent — we send none, so it is consumed and ignored.
 */
internal suspend fun answerFence(source: VncSource, sink: VncSink) {
    val head = ByteArray(8) // 3 bytes padding, u32 flags, u8 payload length
    source.readFully(head, 0, 8)
    val flags = ((head[3].toInt() and 0xFF) shl 24) or ((head[4].toInt() and 0xFF) shl 16) or
        ((head[5].toInt() and 0xFF) shl 8) or (head[6].toInt() and 0xFF)
    val length = head[7].toInt() and 0xFF
    if (length > RfbCodec.MAX_FENCE_PAYLOAD) {
        throw VncProtocolException("fence payload $length exceeds max ${RfbCodec.MAX_FENCE_PAYLOAD}")
    }
    val payload = ByteArray(length)
    if (length > 0) source.readFully(payload, 0, length)
    if (flags and RfbCodec.FENCE_REQUEST == 0) return
    val reply = ByteArray(9 + length)
    reply[0] = RfbCodec.MSG_FENCE.toByte()
    val masked = flags and (RfbCodec.FENCE_BLOCK_BEFORE or RfbCodec.FENCE_BLOCK_AFTER)
    reply[4] = (masked ushr 24).toByte(); reply[5] = (masked ushr 16).toByte()
    reply[6] = (masked ushr 8).toByte(); reply[7] = masked.toByte()
    reply[8] = length.toByte()
    payload.copyInto(reply, 9)
    sink.write(reply)
}
