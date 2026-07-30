package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpRect

/**
 * The H.264 codecs of the graphics pipeline (MS-RDPEGFX 2.2.4.4 and 2.2.4.5), over whatever decoder
 * the platform brought.
 *
 * State is per surface, like the progressive codec's and for the same reason: a frame is a difference
 * from the pictures before it, so the decoder — and, in 4:4:4, the full-resolution chroma assembled
 * across messages — belongs to the surface and goes away with it.
 *
 * A picture that does not arrive is not fatal here. A decoder that produced nothing costs the update
 * it was given, and a region that falls outside the picture is dropped rather than ending the
 * session: the server keeps sending, and the next frame that covers that part of the screen repairs
 * it. The alternative — one unreadable frame closing a working session — is the trade the legacy
 * bitmap path already refused.
 */
class AvcCodec(
    private val decoders: H264DecoderFactory,
    /** Where a line about the decoder goes; silent by default, see `h264Trace` on the JVM. */
    private val trace: (String) -> Unit = {},
) : AvcDecoder {

    private val surfaces = mutableMapOf<Int, SurfaceStream>()

    /** Traced once: every message for a surface past the decoder limit would otherwise say so again. */
    private var refused = false

    override fun decodeAvc420(data: ByteArray, surface: GraphicsSurface): List<RdpRect> {
        val message = readAvc420Stream(RdpReader(data))
        val stream = stream(surface) ?: return emptyList()
        val frame = stream.decode(message.bitstream) ?: return emptyList()
        val damaged = mutableListOf<RdpRect>()
        for (region in message.regions) {
            val visible = visible(region, frame, surface) ?: continue
            paint420(frame, visible, surface)
            damaged += visible
        }
        return damaged
    }

    override fun decodeAvc444(data: ByteArray, surface: GraphicsSurface, version2: Boolean): List<RdpRect> {
        val message = readAvc444Stream(data)
        val stream = stream(surface) ?: return emptyList()
        // Both halves describe the same pixels, so nothing is painted until both have been taken:
        // painting after each would run the colour transform and the blit twice over every region of
        // the frame, and the first pass would never be seen.
        val damaged = LinkedHashSet<RdpRect>()
        // In wire order, through the one decoder: the server encodes the two pictures as consecutive
        // frames of the same H.264 sequence, so the second one is a difference from the first.
        message.luma?.let { luma ->
            val frame = stream.decode(luma.bitstream) ?: return@let
            for (region in luma.regions) {
                val visible = visible(region, frame, surface) ?: continue
                stream.planes.takeLuma(frame, visible)
                damaged += visible
            }
        }
        message.chroma?.let { chroma ->
            val frame = stream.decode(chroma.bitstream) ?: return@let
            for (region in chroma.regions) {
                val visible = visible(region, frame, surface) ?: continue
                if (version2) {
                    stream.planes.takeChromaV2(frame, visible, totalWidth(surface, frame))
                } else {
                    stream.planes.takeChromaV1(frame, visible)
                }
                damaged += visible
            }
        }
        for (region in damaged) stream.planes.paint(region, surface)
        return damaged.toList()
    }

    override fun forgetSurface(surfaceId: Int) {
        surfaces.remove(surfaceId)?.close()
    }

    override fun close() {
        for (stream in surfaces.values) stream.close()
        surfaces.clear()
    }

    /** The stream state of [surface], with a decoder opened the first time it is drawn on. */
    private fun stream(surface: GraphicsSurface): SurfaceStream? {
        surfaces[surface.id]?.let { existing ->
            // Geometry, not just the id: a server may rebuild a surface at another size under the id
            // it had, and the pictures held under it then describe a screen that no longer exists.
            if (existing.matches(surface)) return existing
            existing.close()
            surfaces.remove(surface.id)
        }
        // A decoder is an expensive thing to hold — a process on the desktop, a hardware codec
        // instance on Android — and nothing in the protocol bounds how many surfaces a server may
        // draw on. Past this many it stops getting them: the ones already open keep working, which is
        // more than a client that spawned decoders until the machine gave up would manage.
        if (surfaces.size >= MAX_DECODERS) {
            if (!refused) trace("$MAX_DECODERS surfaces already have a decoder, surface ${surface.id} gets none")
            refused = true
            return null
        }
        // Nothing recovers from this. The client said it could take H.264 before the first surface
        // existed, the server took it up and now sends this surface no other way, and capabilities
        // cannot be renegotiated mid-session — so a decoder that will not open means this desktop
        // will never paint again. Ending the session with a reason is the only honest answer;
        // returning empty would leave a frozen screen and retry the same doomed open every frame.
        val decoder = decoders.open(surface.width, surface.height)
            ?: error("no H.264 decoder opened for ${surface.width}x${surface.height}")
        return SurfaceStream(decoder, surface.width, surface.height).also { surfaces[surface.id] = it }
    }

    /**
     * [region] clipped to what can actually be painted: the region list is the server's, in surface
     * coordinates, and the picture behind it may be smaller than the surface — a frame from a server
     * that encodes less than the whole screen, or one that has not caught up with a resize.
     */
    private fun visible(region: RdpRect, frame: YuvFrame, surface: GraphicsSurface): RdpRect? {
        val clipped = surface.clip(region)
        val right = minOf(clipped.x + clipped.width, frame.width)
        val bottom = minOf(clipped.y + clipped.height, frame.height)
        if (right <= clipped.x || bottom <= clipped.y) return null
        return RdpRect(clipped.x, clipped.y, right - clipped.x, bottom - clipped.y)
    }

    private fun paint420(frame: YuvFrame, region: RdpRect, surface: GraphicsSurface) {
        for (row in 0 until region.height) {
            val luma = (region.y + row) * frame.yStride
            val chroma = ((region.y + row) / 2) * frame.chromaStride
            var target = (region.y + row) * surface.width + region.x
            for (col in 0 until region.width) {
                val x = region.x + col
                surface.pixels[target++] = H264Color.yuvToArgb(
                    frame.y[luma + x].toInt() and 0xFF,
                    frame.u[chroma + x / 2].toInt() and 0xFF,
                    frame.v[chroma + x / 2].toInt() and 0xFF,
                )
            }
        }
    }

    /**
     * Where the V half of a row starts in the auxiliary picture of codec 0x000F: the surface width
     * rounded up to 32, and never past what the picture actually holds — a server that sends a frame
     * narrower than the surface would otherwise be read outside its own plane.
     */
    private fun totalWidth(surface: GraphicsSurface, frame: YuvFrame): Int {
        val aligned = surface.width + (V2_ALIGNMENT - surface.width % V2_ALIGNMENT) % V2_ALIGNMENT
        return minOf(aligned, frame.yStride)
    }

    /** One surface's decoder and, in 4:4:4, the full-resolution planes it assembles. */
    private class SurfaceStream(
        private val decoder: H264Decoder,
        private val width: Int,
        private val height: Int,
    ) {
        /**
         * The planes to assemble a 4:4:4 picture in, sized to the surface rather than to the picture:
         * only what is painted has to fit, and painting is clipped to the surface either way. Sizing
         * them from the picture would put a buffer the server chose the size of outside the budget
         * that bounds every other allocation here — and would have to be rebuilt mid-message, losing
         * the luma that message had just assembled.
         *
         * Built on first use: a session that never sees 4:4:4 never pays for them.
         */
        val planes: Avc444Planes by lazy { Avc444Planes(width, height) }

        fun matches(surface: GraphicsSurface): Boolean =
            surface.width == width && surface.height == height

        fun decode(accessUnit: ByteArray): YuvFrame? = decoder.decode(accessUnit)

        fun close() = decoder.close()
    }

    private companion object {
        /** What the packing of codec 0x000F aligns a row to before splitting it in half. */
        const val V2_ALIGNMENT = 32

        /**
         * Decoders held at once. A Windows host draws the desktop on one surface, and RemoteApp one
         * per window; eight is past anything a session does and far short of what would hurt.
         */
        const val MAX_DECODERS = 8
    }
}
