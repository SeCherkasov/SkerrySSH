package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteDesktopDiagnostics
import app.skerry.shared.graphics.RemoteFramebuffer

/**
 * Surface commands (MS-RDPBCGR 2.2.9.2): the modern graphics path, where the server hands over
 * rectangles of pixels in a negotiated codec instead of drawing orders.
 *
 * Frame markers bracket a set of commands. They matter beyond bookkeeping: the client acknowledges
 * each frame, and the server uses those acknowledgements to pace itself — a client that never
 * answers gets throttled to the two frames it allowed in flight.
 */
class SurfaceDecoder(
    private val codecs: RdpCodecs = RdpCodecs(),
    /** The session's counters for the diagnostics overlay; a private default when nobody reads them. */
    private val diagnostics: RemoteDesktopDiagnostics = RemoteDesktopDiagnostics(),
) {

    /** Decode a run of surface commands, applying pixels to [framebuffer]. */
    fun decode(reader: RdpReader, framebuffer: RemoteFramebuffer): List<RdpUpdate> {
        val updates = mutableListOf<RdpUpdate>()
        while (reader.remaining >= 2) {
            when (val commandType = reader.u16le()) {
                CMDTYPE_SET_SURFACE_BITS, CMDTYPE_STREAM_SURFACE_BITS ->
                    updates += surfaceBits(reader, framebuffer)

                CMDTYPE_FRAME_MARKER -> {
                    val action = reader.u16le()
                    val frameId = reader.u32le()
                    updates += RdpUpdate.Frame(frameId, begin = action == FRAME_ACTION_BEGIN)
                }

                else -> throw RdpProtocolException("unknown surface command 0x${commandType.toString(16)}")
            }
        }
        return updates
    }

    private fun surfaceBits(reader: RdpReader, framebuffer: RemoteFramebuffer): List<RdpUpdate> {
        val left = reader.u16le()
        val top = reader.u16le()
        val right = reader.u16le()
        val bottom = reader.u16le()
        // TS_BITMAP_DATA_EX
        val bitsPerPixel = reader.u8()
        val flags = reader.u8()
        reader.u8() // reserved
        val codecId = reader.u8()
        val width = reader.u16le()
        val height = reader.u16le()
        val length = reader.u32le()
        if (flags and EX_COMPRESSED_BITMAP_HEADER_PRESENT != 0) {
            reader.skip(24) // TS_COMPRESSED_BITMAP_HEADER_EX
        }
        if (length < 0 || length > reader.remaining) {
            throw RdpProtocolException("surface bits declare $length bytes, ${reader.remaining} remain")
        }
        val data = reader.bytes(length)
        if (width <= 0 || height <= 0) return emptyList()
        RdpImageBounds.requireSize(width, height, "surface bits")

        diagnostics.noteCodec(
            when (codecId) {
                0 -> "Raw"
                ClientCapabilities.CODEC_ID_REMOTEFX -> "RemoteFX"
                else -> "0x${codecId.toString(16)}"
            },
        )
        val pixels = codecs.decode(codecId, data, width, height, bitsPerPixel)
            ?: throw RdpProtocolException("server used codec $codecId, which was not negotiated")
        for (row in 0 until height) {
            framebuffer.blitRow(left, top + row, width, pixels, row * width)
        }
        return listOf(
            RdpUpdate.Region(
                listOf(RdpRect(left, top, minOf(width, right - left), minOf(height, bottom - top))),
            ),
        )
    }

    private companion object {
        const val CMDTYPE_SET_SURFACE_BITS = 0x0001
        const val CMDTYPE_FRAME_MARKER = 0x0004
        const val CMDTYPE_STREAM_SURFACE_BITS = 0x0006
        const val FRAME_ACTION_BEGIN = 0x0000
        const val EX_COMPRESSED_BITMAP_HEADER_PRESENT = 0x01
    }
}

/**
 * The codecs a session can decode, keyed by the ids this client assigned in its Bitmap Codecs
 * capability set. Uncompressed is always present; the rest are plugged in as they are negotiated.
 */
class RdpCodecs(private val remoteFx: RemoteFxDecoder? = null) {

    /** Decode [data] to ARGB, or null when [codecId] names a codec this session never negotiated. */
    fun decode(codecId: Int, data: ByteArray, width: Int, height: Int, bitsPerPixel: Int): IntArray? = when (codecId) {
        CODEC_ID_NONE -> uncompressed(data, width, height, bitsPerPixel)
        ClientCapabilities.CODEC_ID_REMOTEFX -> remoteFx?.decode(data, width, height)
        else -> null
    }

    // Reused across commands: an uncompressed full-desktop command otherwise allocates the whole
    // screen per PDU (F-35). Safe because [decode]'s caller blits the pixels before the next call,
    // and one RdpCodecs belongs to one session's read loop.
    private var scratch = IntArray(0)

    /**
     * Raw pixels of a surface command. Unlike a legacy bitmap update these arrive top-down, which is
     * the one difference that makes a picture come out upside down if it is missed. Decoded with
     * direct byte indexing — this is exactly the path of a host with no codec negotiated, where a
     * reader call per byte made every full-screen paint a few million virtual calls (F-35).
     *
     * The returned array may be larger than `width * height`; pixels beyond the wire data are zero.
     */
    private fun uncompressed(data: ByteArray, width: Int, height: Int, bitsPerPixel: Int): IntArray {
        val bytesPerPixel = (bitsPerPixel + 7) / 8
        val count = width * height
        if (scratch.size < count) scratch = IntArray(count)
        val out = scratch
        // Anything but 16 and 24 bpp is read as 32-bit, so the stride is what the branch consumes.
        val stride = when (bytesPerPixel) {
            2, 3 -> bytesPerPixel
            else -> 4
        }
        val available = minOf(count, data.size / stride)
        when (bytesPerPixel) {
            2 -> for (p in 0 until available) {
                val i = p * 2
                val raw = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                out[p] = InterleavedRle.rgb565ToArgb(raw)
            }

            3 -> for (p in 0 until available) {
                val i = p * 3
                out[p] = OPAQUE or ((data[i + 2].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)
            }

            else -> for (p in 0 until available) {
                val i = p * 4
                out[p] = OPAQUE or ((data[i + 2].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)
            }
        }
        // Short wire data reads as unset, as it always has — and the scratch must not leak the
        // previous command's pixels into this one.
        out.fill(0, available, count)
        return out
    }

    private companion object {
        const val CODEC_ID_NONE = 0
        const val OPAQUE = 0xFF shl 24
    }
}

/** Decodes a RemoteFX stream into ARGB pixels (MS-RDPRFX); implemented alongside the codec itself. */
interface RemoteFxDecoder {
    fun decode(data: ByteArray, width: Int, height: Int): IntArray
}
