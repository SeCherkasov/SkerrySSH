package app.skerry.shared.rdp.egfx

import app.skerry.shared.graphics.RemoteDesktopDiagnostics
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.rdp.RdpH264Mode
import app.skerry.shared.rdp.RdpImageBounds
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpUpdate
import app.skerry.shared.rdp.RdpWriter
import kotlin.time.TimeSource

/**
 * The graphics pipeline (MS-RDPEGFX): the dynamic channel a modern server prefers over surface
 * commands, and the only path on which the progressive codec travels.
 *
 * The model is different from the legacy one. The server draws into surfaces of its own choosing
 * and maps them onto the desktop; frames bracket the drawing and are acknowledged, which is what
 * paces the stream; and a small cache lets a repeated region be sent once. This class owns the
 * surfaces and the cache, paints whatever is mapped into [framebuffer], and collects the damage
 * the session emits once a frame ends.
 *
 * Everything here runs on the session's read loop, which is why nothing is guarded: the loop
 * decodes a message, then drains the updates it produced before reading the next one.
 */
class GraphicsChannel(
    private val framebuffer: RemoteFramebuffer,
    private val codecs: GraphicsCodecs,
    private val surfaceBudgetPixels: Int = MAX_SURFACE_PIXELS,
    /** Which H.264 ladder to advertise (F-28); capped by whether [GraphicsCodecs.avc] exists. */
    private val h264Mode: RdpH264Mode = RdpH264Mode.Auto,
    /**
     * Advertise CAPS_FLAG_SMALL_CACHE (F-07). Small cache trades memory for retransmission on a
     * busy desktop; the default keeps it, desktop turns it off and budgets the spec's full cache.
     */
    private val smallCache: Boolean = true,
    /**
     * Where a line about the capability exchange goes; silent by default. Which version the server
     * picked decides whether it may use H.264 at all, and nothing else on the wire says so.
     */
    private val trace: (String) -> Unit = {},
    /** The session's counters for the diagnostics overlay; a private default when nobody reads them. */
    private val diagnostics: RemoteDesktopDiagnostics = RemoteDesktopDiagnostics(),
    private val send: suspend (ByteArray) -> Unit,
) : DynamicChannelHandler {

    /**
     * The channel's own bulk decompressor. Client→server PDUs go out unwrapped — which is what
     * every server accepts — so only this direction needs one.
     */
    private val bulk = Zgfx()

    /** What the advertisement promised is what eviction honours — a server sizes its slots on it. */
    private val cacheBudgetPixels = if (smallCache) MAX_CACHE_PIXELS else LARGE_CACHE_PIXELS

    private val surfaces = mutableMapOf<Int, GraphicsSurface>()
    private val cache = mutableMapOf<Int, CachedBitmap>()
    private val cacheOrder = ArrayDeque<Int>()
    private var cachedPixels = 0
    private var surfacePixels = 0

    private val updates = mutableListOf<RdpUpdate>()
    private val damage = mutableListOf<RdpRect>()
    private var insideFrame = false
    private var framesDecoded = 0

    override suspend fun onOpen() {
        // The client speaks first here: the server picks one of the advertised versions and answers
        // with a capability confirmation, and nothing is drawn on the channel before that.
        send(pdu(CMDID_CAPS_ADVERTISE, capsAdvertise()))
    }

    override suspend fun onMessage(data: ByteArray) {
        // Decompression and decoding are one number in the overlay: both stand between a received
        // PDU and pixels on screen, and the split would not change any decision the number drives.
        val started = TimeSource.Monotonic.markNow()
        // Every message on this channel is bulk-encoded, whatever the dynamic channel layer did
        // with it: the two compressions are independent, and each keeps its own history.
        val reader = RdpReader(bulk.decompress(data))
        while (reader.remaining >= HEADER_SIZE) {
            val commandId = reader.u16le()
            reader.u16le() // flags
            val length = reader.u32le()
            if (length < HEADER_SIZE || length - HEADER_SIZE > reader.remaining) {
                throw RdpProtocolException("a graphics PDU of $length bytes does not fit its message")
            }
            handle(commandId, reader.slice(length - HEADER_SIZE))
        }
        // A server that draws without bracketing its work in frames still has to reach the screen.
        if (!insideFrame) flushDamage()
        diagnostics.decodeTime(started.elapsedNow().inWholeNanoseconds)
    }

    /**
     * The session is over: give back what the codecs hold outside this process.
     *
     * The surfaces and the caches are memory and go with the object, but an H.264 decoder is a
     * process on the desktop and one of a handful of hardware codec instances on Android, and a
     * server has no reason to delete the surface it drew the desktop on before disconnecting.
     */
    fun close() {
        codecs.avc?.close()
    }

    /** The updates decoded since the last call; the session emits them into its flow. */
    fun drainUpdates(): List<RdpUpdate> {
        if (updates.isEmpty()) return emptyList()
        val out = updates.toList()
        updates.clear()
        return out
    }

    private suspend fun handle(commandId: Int, body: RdpReader) {
        when (commandId) {
            // The confirmation names the version the server picked out of what was advertised. There
            // is nothing to decode differently — a version this client offered is one it can take,
            // and the codec id of every bitmap says which codec it is anyway — but it is the only
            // place that says whether the server took the offer of H.264, so it goes to the trace
            // and to the diagnostics overlay.
            CMDID_CAPS_CONFIRM -> {
                val version = body.u32le()
                trace("the server confirmed capability version 0x${version.toString(16)}")
                diagnostics.noteNegotiated("GFX ${gfxVersionName(version)}")
            }
            CMDID_RESET_GRAPHICS -> resetGraphics(body)
            CMDID_CREATE_SURFACE -> createSurface(body)
            CMDID_DELETE_SURFACE -> deleteSurface(body)
            CMDID_START_FRAME -> insideFrame = true
            CMDID_END_FRAME -> endFrame(body)
            CMDID_WIRE_TO_SURFACE_1 -> wireToSurface(body)
            CMDID_SOLID_FILL -> solidFill(body)
            CMDID_SURFACE_TO_SURFACE -> surfaceToSurface(body)
            CMDID_SURFACE_TO_CACHE -> surfaceToCache(body)
            CMDID_CACHE_TO_SURFACE -> cacheToSurface(body)
            CMDID_EVICT_CACHE_ENTRY -> evictCacheEntry(body)
            // A scaled mapping asks the client to stretch the surface; the view already scales the
            // whole desktop to its window, so the target size that follows is not applied twice.
            CMDID_MAP_SURFACE_TO_OUTPUT, CMDID_MAP_SURFACE_TO_SCALED_OUTPUT -> mapSurfaceToOutput(body)

            // A codec context names a stream, not the picture the stream describes. The server goes
            // on encoding tiles as differences from the ones it sent for that surface, so ending a
            // context is not permission to forget them — only deleting the surface is. Forgetting
            // them here left an unchanged tile arriving as a difference added to nothing, and a
            // progressive tile of zero coefficients is a mid-grey square that nothing repaints,
            // because the server has no reason to send that tile again.
            CMDID_DELETE_ENCODING_CONTEXT -> Unit

            CMDID_WIRE_TO_SURFACE_2 -> wireToSurfaceProgressive(body)

            // Window mapping belongs to RemoteApp, cache import replies to a cache we never offer,
            // and anything newer than the version advertised is not ours to guess at.
            else -> Unit
        }
    }

    private fun resetGraphics(body: RdpReader) {
        val width = body.u32le()
        val height = body.u32le()
        RdpImageBounds.requireSize(width, height, "a desktop reset")
        // The monitor layout that follows only matters to a multi-monitor client.
        framebuffer.resize(width, height)
        damage.clear()
        updates += RdpUpdate.Resize(width, height)
        // A reset invalidates every pixel drawn so far. Surfaces keep their identity and the caches
        // keep theirs — the Clear codec's are explicitly not bound to a surface — but their content
        // is gone: the server redraws the new desktop as if the screen were empty and then sends
        // only what changes. Keeping the old pixels (or repainting them onto the new desktop) is
        // what left a rectangle of the previous resolution stuck on screen after a resize.
        for (surface in surfaces.values) surface.clear()
    }

    private fun createSurface(body: RdpReader) {
        val surfaceId = body.u16le()
        val width = body.u16le()
        val height = body.u16le()
        body.u8() // pixel format: surfaces are 32-bit either way, the difference is only the alpha
        RdpImageBounds.requireSize(width, height, "a surface")
        // A single surface is bounded, but nothing bounds how many ids the server may use, and each
        // one is memory this client holds until it is told to let go. One budget across all of them,
        // in the spirit of the cache's own — except that a surface cannot be evicted behind the
        // server's back, so the answer to a surface that does not fit is to refuse it.
        val replaced = surfaces[surfaceId]?.pixels?.size ?: 0
        if (surfacePixels - replaced + width * height > surfaceBudgetPixels) {
            throw RdpProtocolException("a surface of ${width}x$height past the client's memory budget")
        }
        surfacePixels += width * height - replaced
        surfaces[surfaceId] = GraphicsSurface(surfaceId, width, height)
        // An id in use again is a new surface, not the old one resized: whatever a stateful codec
        // still holds under it belongs to the picture that has just been thrown away.
        forgetSurfaceState(surfaceId)
    }

    private fun deleteSurface(body: RdpReader) {
        val surfaceId = body.u16le()
        surfaces.remove(surfaceId)?.let { surfacePixels -= it.pixels.size }
        forgetSurfaceState(surfaceId)
    }

    private fun forgetSurfaceState(surfaceId: Int) {
        codecs.progressive?.forgetSurface(surfaceId)
        codecs.avc?.forgetSurface(surfaceId)
    }

    /**
     * A frame ended: the server is told how far the client has got, because it stops sending once
     * its allowance of unacknowledged frames is used up.
     */
    private suspend fun endFrame(body: RdpReader) {
        val frameId = body.u32le()
        insideFrame = false
        framesDecoded++
        diagnostics.serverFrame()
        flushDamage()
        val acknowledge = RdpWriter(12)
            .u32le(QUEUE_DEPTH_UNAVAILABLE)
            .u32le(frameId)
            .u32le(framesDecoded)
            .toByteArray()
        send(pdu(CMDID_FRAME_ACKNOWLEDGE, acknowledge))
    }

    /**
     * One H.264 bitmap onto [surface]. The rectangle in the PDU is the whole frame; which parts
     * were redrawn is in the message's own region list, in surface coordinates. The mode gates the
     * receive side too: a server ignoring the capability exchange must not reach a codec path the
     * profile advertised away (F-28's escape-hatch promise).
     */
    private fun decodeAvc(codecId: Int, data: ByteArray, surface: GraphicsSurface) {
        val avc = codecs.avc
            ?: throw RdpProtocolException("the server used ${GraphicsCodecs.codecName(codecId)}, not advertised")
        if (codecId != GraphicsCodecs.CODEC_AVC420 && h264Mode == RdpH264Mode.Avc420) {
            throw RdpProtocolException("the server used ${GraphicsCodecs.codecName(codecId)}, not advertised")
        }
        val touched = if (codecId == GraphicsCodecs.CODEC_AVC420) {
            avc.decodeAvc420(data, surface)
        } else {
            avc.decodeAvc444(data, surface, version2 = codecId == GraphicsCodecs.CODEC_AVC444_V2)
        }
        for (region in touched) present(surface, region)
    }

    private fun wireToSurface(body: RdpReader) {
        val surfaceId = body.u16le()
        val codecId = body.u16le()
        diagnostics.notePath("EGFX")
        diagnostics.noteCodec(codecLabel(codecId))
        body.u8() // pixelFormat
        val rect = readRect(body)
        val length = body.u32le()
        if (length < 0 || length > body.remaining) {
            throw RdpProtocolException("a surface bitmap of $length bytes, ${body.remaining} remain")
        }
        val data = body.bytes(length)
        val surface = surfaces[surfaceId] ?: return
        if (rect.width <= 0 || rect.height <= 0) return
        // The rectangle is the server's to declare and is not clipped to the surface before the
        // codec allocates the pixels it asks for; `createSurface` bounding the surface does not
        // bound this.
        RdpImageBounds.requireSize(rect.width, rect.height, "a surface bitmap")

        if (codecId == GraphicsCodecs.CODEC_PROGRESSIVE) {
            val progressive = codecs.progressive
                ?: throw RdpProtocolException("the server used the progressive codec, which was not advertised")
            for (touched in progressive.decode(data, surface, rect)) present(surface, touched)
            return
        }

        if (codecId in AVC_CODECS) {
            decodeAvc(codecId, data, surface)
            return
        }

        val pixels = codecs.decode(codecId, data, rect.width, rect.height)
            ?: throw RdpProtocolException("the server used the ${GraphicsCodecs.codecName(codecId)} codec")
        surface.blit(rect.x, rect.y, rect.width, rect.height, pixels)
        present(surface, rect)
    }

    /**
     * The other way a bitmap reaches a surface: no rectangle, and a codec context that outlives the
     * message. It is how the progressive codec sends one image over several PDUs — the first carries
     * a coarse picture and the rest refine it — so the destination is the whole surface and the
     * stream's own regions say which parts of it are being refined.
     *
     * The context id is not kept: this client holds progressive state per surface, and a surface is
     * never being progressively drawn by two contexts at once. That state outlives the context —
     * see [CMDID_DELETE_ENCODING_CONTEXT] — and goes away with the surface.
     */
    private fun wireToSurfaceProgressive(body: RdpReader) {
        val surfaceId = body.u16le()
        val codecId = body.u16le()
        diagnostics.notePath("EGFX")
        diagnostics.noteCodec(codecLabel(codecId))
        body.u32le() // codecContextId
        body.u8() // pixelFormat
        // A length field sits here in the specification, but the stream that follows runs to the end
        // of the PDU either way. Treating it as a length only when it accounts for exactly what is
        // left keeps this working against a server that leaves it out.
        if (body.remaining >= 4) {
            val declared = body.u32le()
            if (declared != body.remaining) body.rewind(4)
        }
        val data = body.rest()

        val surface = surfaces[surfaceId] ?: return
        if (codecId != GraphicsCodecs.CODEC_PROGRESSIVE) {
            throw RdpProtocolException("the server used ${GraphicsCodecs.codecName(codecId)} with a codec context")
        }
        val progressive = codecs.progressive
            ?: throw RdpProtocolException("the server used the progressive codec, which was not advertised")
        val whole = RdpRect(0, 0, surface.width, surface.height)
        for (touched in progressive.decode(data, surface, whole)) present(surface, touched)
    }

    private fun solidFill(body: RdpReader) {
        val surface = surfaces[body.u16le()]
        val blue = body.u8()
        val green = body.u8()
        val red = body.u8()
        body.u8() // alpha: surfaces are opaque
        val argb = OPAQUE or (red shl 16) or (green shl 8) or blue
        val count = body.u16le()
        repeat(count) {
            val rect = readRect(body)
            if (surface == null) return@repeat
            surface.fill(rect, argb)
            present(surface, rect)
        }
    }

    private fun surfaceToSurface(body: RdpReader) {
        val source = surfaces[body.u16le()]
        val destination = surfaces[body.u16le()]
        val rect = readRect(body)
        val count = body.u16le()
        val pixels = source?.read(rect)
        val clipped = source?.clip(rect)
        repeat(count) {
            val x = body.u16le()
            val y = body.u16le()
            if (destination == null || pixels == null || clipped == null) return@repeat
            if (clipped.width == 0 || clipped.height == 0) return@repeat
            destination.blit(x, y, clipped.width, clipped.height, pixels)
            present(destination, RdpRect(x, y, clipped.width, clipped.height))
        }
    }

    private fun surfaceToCache(body: RdpReader) {
        val surface = surfaces[body.u16le()]
        body.u32le() // cache key, low half — only the slot identifies an entry within a session
        body.u32le()
        val slot = body.u16le()
        val rect = readRect(body)
        if (surface == null) return
        val clipped = surface.clip(rect)
        if (clipped.width == 0 || clipped.height == 0) return
        putCache(slot, CachedBitmap(clipped.width, clipped.height, surface.read(rect)))
    }

    private fun cacheToSurface(body: RdpReader) {
        val entry = cache[body.u16le()]
        val surface = surfaces[body.u16le()]
        val count = body.u16le()
        repeat(count) {
            val x = body.u16le()
            val y = body.u16le()
            if (surface == null || entry == null) return@repeat
            surface.blit(x, y, entry.width, entry.height, entry.pixels)
            present(surface, RdpRect(x, y, entry.width, entry.height))
        }
    }

    private fun evictCacheEntry(body: RdpReader) = evict(body.u16le())

    private fun mapSurfaceToOutput(body: RdpReader) {
        val surface = surfaces[body.u16le()]
        body.u16le() // reserved
        val x = body.u32le()
        val y = body.u32le()
        if (surface == null) return
        surface.mapToOutput(x, y)
        present(surface, RdpRect(0, 0, surface.width, surface.height))
    }

    /** Copy the part of [surface] named by [rect] onto the desktop, if the surface is mapped. */
    private fun present(surface: GraphicsSurface, rect: RdpRect) {
        val originX = surface.outputX ?: return
        val clipped = surface.clip(rect)
        if (clipped.width == 0 || clipped.height == 0) return
        for (row in 0 until clipped.height) {
            framebuffer.blitRow(
                originX + clipped.x,
                surface.outputY + clipped.y + row,
                clipped.width,
                surface.pixels,
                (clipped.y + row) * surface.width + clipped.x,
            )
        }
        damage += RdpRect(originX + clipped.x, surface.outputY + clipped.y, clipped.width, clipped.height)
    }

    private fun flushDamage() {
        if (damage.isEmpty()) return
        updates += RdpUpdate.Region(damage.toList())
        damage.clear()
    }

    private fun putCache(slot: Int, bitmap: CachedBitmap) {
        evict(slot)
        cache[slot] = bitmap
        cacheOrder.addLast(slot)
        cachedPixels += bitmap.pixels.size
        // The server keeps its own idea of the cache, so an entry dropped here is not an error —
        // the region it would have restored simply stays as it was until the server sends it again.
        while (cachedPixels > cacheBudgetPixels && cacheOrder.isNotEmpty()) {
            evict(cacheOrder.first())
        }
    }

    private fun evict(slot: Int) {
        cache.remove(slot)?.let { cachedPixels -= it.pixels.size }
        cacheOrder.remove(slot)
    }

    private fun readRect(reader: RdpReader): RdpRect {
        val left = reader.u16le()
        val top = reader.u16le()
        val right = reader.u16le()
        val bottom = reader.u16le()
        return RdpRect(left, top, right - left, bottom - top)
    }

    /**
     * What the client can take. The server picks the highest version it also knows and confirms that
     * one, so a version is advertised only when every codec it implies can be decoded here —
     * advertising more would trade a working session for a frozen screen.
     *
     * Version 8 alone means no H.264. 8.1 adds it for 4:2:0 through an explicit flag, and 10.4 adds
     * 4:4:4, which is on unless the client asks for it to be off. 10.5 and later are left out
     * deliberately: they oblige the client to scale a surface onto the output, and this one only
     * scales the whole desktop, in the view.
     */
    private fun capsAdvertise(): ByteArray {
        // The mode is a preference, the decoder a possibility: both must agree before a version
        // that implies H.264 goes out, or the server would send pictures nothing here can decode.
        val avc420 = codecs.avc != null && h264Mode != RdpH264Mode.Off
        val avc444 = avc420 && h264Mode != RdpH264Mode.Avc420
        val sets = 1 + (if (avc420) 1 else 0) + (if (avc444) 1 else 0)
        val cacheFlag = if (smallCache) CAPS_FLAG_SMALL_CACHE else 0
        val writer = RdpWriter(40).u16le(sets) // capsSetCount
        writer.u32le(CAPVERSION_8).u32le(4).u32le(cacheFlag)
        if (avc420) {
            writer.u32le(CAPVERSION_8_1).u32le(4).u32le(cacheFlag or CAPS_FLAG_AVC420_ENABLED)
        }
        if (avc444) {
            writer.u32le(CAPVERSION_10_4).u32le(4).u32le(cacheFlag)
        }
        return writer.toByteArray()
    }

    /** Overlay label of a pipeline codec id — shorter than [GraphicsCodecs.codecName]'s error text. */
    private fun codecLabel(codecId: Int): String = when (codecId) {
        GraphicsCodecs.CODEC_UNCOMPRESSED -> "Raw"
        GraphicsCodecs.CODEC_REMOTEFX -> "RemoteFX"
        GraphicsCodecs.CODEC_CLEARCODEC -> "ClearCodec"
        GraphicsCodecs.CODEC_PROGRESSIVE -> "Progressive"
        GraphicsCodecs.CODEC_PLANAR -> "Planar"
        GraphicsCodecs.CODEC_AVC420 -> "AVC420"
        GraphicsCodecs.CODEC_AVC444, GraphicsCodecs.CODEC_AVC444_V2 -> "AVC444"
        else -> "0x${codecId.toString(16)}"
    }

    private fun gfxVersionName(version: Int): String = when (version) {
        CAPVERSION_8 -> "8"
        CAPVERSION_8_1 -> "8.1"
        CAPVERSION_10_4 -> "10.4"
        else -> "0x${version.toString(16)}"
    }

    private fun pdu(commandId: Int, body: ByteArray): ByteArray = RdpWriter(body.size + HEADER_SIZE)
        .u16le(commandId)
        .u16le(0) // flags
        .u32le(body.size + HEADER_SIZE)
        .bytes(body)
        .toByteArray()

    private class CachedBitmap(val width: Int, val height: Int, val pixels: IntArray)

    companion object {
        /** The channel the server opens for the pipeline. */
        const val NAME = "Microsoft::Windows::RDS::Graphics"

        private const val HEADER_SIZE = 8

        private const val CMDID_WIRE_TO_SURFACE_1 = 0x0001
        private const val CMDID_WIRE_TO_SURFACE_2 = 0x0002
        private const val CMDID_DELETE_ENCODING_CONTEXT = 0x0003
        private const val CMDID_SOLID_FILL = 0x0004
        private const val CMDID_SURFACE_TO_SURFACE = 0x0005
        private const val CMDID_SURFACE_TO_CACHE = 0x0006
        private const val CMDID_CACHE_TO_SURFACE = 0x0007
        private const val CMDID_EVICT_CACHE_ENTRY = 0x0008
        private const val CMDID_CREATE_SURFACE = 0x0009
        private const val CMDID_DELETE_SURFACE = 0x000A
        private const val CMDID_START_FRAME = 0x000B
        private const val CMDID_END_FRAME = 0x000C
        private const val CMDID_FRAME_ACKNOWLEDGE = 0x000D
        private const val CMDID_RESET_GRAPHICS = 0x000E
        private const val CMDID_MAP_SURFACE_TO_OUTPUT = 0x000F
        private const val CMDID_CAPS_ADVERTISE = 0x0012
        private const val CMDID_CAPS_CONFIRM = 0x0013
        private const val CMDID_MAP_SURFACE_TO_SCALED_OUTPUT = 0x0017

        private const val CAPVERSION_8 = 0x00080004
        private const val CAPVERSION_8_1 = 0x00080105
        private const val CAPVERSION_10_4 = 0x000A0400
        private const val CAPS_FLAG_SMALL_CACHE = 0x00000002

        /** 4:2:0 H.264, in version 8.1 only; from version 10 on, AVC is on unless disabled. */
        private const val CAPS_FLAG_AVC420_ENABLED = 0x00000010

        private val AVC_CODECS = setOf(
            GraphicsCodecs.CODEC_AVC420,
            GraphicsCodecs.CODEC_AVC444,
            GraphicsCodecs.CODEC_AVC444_V2,
        )

        /** "I am not reporting a queue depth": the server then paces on frame ids alone. */
        private const val QUEUE_DEPTH_UNAVAILABLE = 0x00000000

        private const val OPAQUE = 0xFF shl 24
        /** Every surface the server holds open at once, together: 256 MB as ARGB. */
        private const val MAX_SURFACE_PIXELS = 1 shl 26

        /** 32 MB of cached pixels — well past what a server told to keep a small cache will use. */
        private const val MAX_CACHE_PIXELS = 8 * 1024 * 1024

        /** The spec's full cache once the small-cache flag is dropped (F-07): 100 MB as pixels. */
        private const val LARGE_CACHE_PIXELS = 25 * 1024 * 1024
    }
}
