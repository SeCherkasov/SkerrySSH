package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.PlanarCodec
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RemoteFxDecoder

/**
 * The codecs the graphics pipeline may use (MS-RDPEGFX 2.2.4.x), keyed by the ids the wire carries.
 * They are separate from the legacy surface-command ids: the same RemoteFX stream is codec 3 here
 * and codec 3 there only by coincidence, and the pipeline adds codecs the legacy path never had.
 *
 * A codec that is not plugged in stays unknown rather than silently drawing nothing, so a server
 * that ignores what was advertised is reported instead of leaving a frozen rectangle on screen.
 */
class GraphicsCodecs(
    private val remoteFx: RemoteFxDecoder? = null,
    val progressive: ProgressiveDecoder? = null,
    // ClearCodec carries state across messages — its caches are what make it cheap — so one decoder
    // serves the whole connection rather than being made per bitmap.
    private val clear: ClearCodec? = null,
) {

    /** Decode a whole [width]×[height] image, or null when [codecId] is one this client lacks. */
    fun decode(codecId: Int, data: ByteArray, width: Int, height: Int): IntArray? = when (codecId) {
        CODEC_UNCOMPRESSED -> uncompressed(data, width, height)
        CODEC_PLANAR -> PlanarCodec.decode(data, width, height)
        CODEC_REMOTEFX -> remoteFx?.decode(data, width, height)
        CODEC_CLEARCODEC -> clear?.decode(data, width, height)
        else -> null
    }

    /** 32 bits per pixel, top-down, with the alpha byte forced opaque — surfaces have no alpha. */
    private fun uncompressed(data: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        val reader = RdpReader(data)
        for (index in out.indices) {
            if (reader.remaining < 4) return out
            val blue = reader.u8()
            val green = reader.u8()
            val red = reader.u8()
            reader.u8()
            out[index] = OPAQUE or (red shl 16) or (green shl 8) or blue
        }
        return out
    }

    companion object {
        const val CODEC_UNCOMPRESSED = 0x0000
        const val CODEC_REMOTEFX = 0x0003
        const val CODEC_CLEARCODEC = 0x0008
        const val CODEC_PROGRESSIVE = 0x0009
        const val CODEC_PLANAR = 0x000A

        private const val OPAQUE = 0xFF shl 24

        /** The name of a codec id, for the message a session ends with when one is not supported. */
        fun codecName(codecId: Int): String = when (codecId) {
            0x000B -> "H.264 (AVC420)"
            0x000C -> "alpha"
            0x000D -> "progressive v2"
            0x000E, 0x000F -> "H.264 (AVC444)"
            else -> "0x${codecId.toString(16)}"
        }
    }
}

/**
 * The progressive codec's decoder. It keeps state per surface — a tile arrives coarse and is
 * refined by later passes — so it is handed the surface rather than a bare buffer, and it says
 * which parts of that surface it touched.
 */
interface ProgressiveDecoder {
    /**
     * Decode [data] into [surface]. [destination] is the rectangle the command names, which the
     * stream's own regions are relative to.
     */
    fun decode(data: ByteArray, surface: GraphicsSurface, destination: RdpRect): List<RdpRect>

    /** Forget everything held for a surface that is going away. */
    fun forgetSurface(surfaceId: Int)
}
