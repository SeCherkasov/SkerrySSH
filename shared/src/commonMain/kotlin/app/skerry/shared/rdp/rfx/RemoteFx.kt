package app.skerry.shared.rdp.rfx

import app.skerry.shared.rdp.DecodedImage
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RemoteFxDecoder

/**
 * RemoteFX (MS-RDPRFX): the codec a modern RDP server streams its desktop with.
 *
 * A frame is a set of 64×64 tiles, each carrying three planes (Y, Cb, Cr) that went through a
 * colour transform, a wavelet transform, quantization and an entropy coder — undone here in reverse
 * order. Tiles are addressed by index rather than by pixel, and only the ones that changed are
 * sent, which is what makes the codec cheap on a mostly-static desktop.
 *
 * The decoder keeps no state between frames beyond the surface it paints into: this client asks for
 * image mode in its capabilities, where every tile is self-contained. Video mode's inter-frame
 * references would need a reference frame per surface, and a dropped frame would then corrupt
 * everything after it rather than one tile.
 *
 * What a message paints is the tiles clipped to its region (MS-RDPRFX 3.1.8.1.7.3), and nothing
 * else: the destination a server names is often just the region's bounding box, and the parts of it
 * no tile covers must keep what the screen already shows.
 */
class RemoteFx : RemoteFxDecoder {

    // Scratch reused across tiles (F-05): three planes decode per tile, six 16 KB arrays a tile
    // before this. Safe because decoding runs on the session's single read loop, and every plane
    // is consumed into `out` before the next tile touches these.
    private val scratchPlanes = Array(3) { IntArray(RfxDwt.TILE_COEFFICIENTS) }
    private val scratchDwt = IntArray(RfxDwt.TILE_COEFFICIENTS)

    override fun decode(data: ByteArray, width: Int, height: Int): DecodedImage {
        val out = IntArray(width * height)
        val tiles = mutableListOf<RdpRect>()
        var region: List<RdpRect>? = null
        val reader = RdpReader(data)
        while (reader.remaining >= BLOCK_HEADER_SIZE) {
            val blockType = reader.u16le()
            val blockLength = reader.u32le()
            if (blockLength < BLOCK_HEADER_SIZE || blockLength - BLOCK_HEADER_SIZE > reader.remaining) {
                throw RdpProtocolException("RemoteFX block of $blockLength bytes does not fit the stream")
            }
            val body = reader.slice(blockLength - BLOCK_HEADER_SIZE)
            when (blockType) {
                WBT_SYNC, WBT_CODEC_VERSIONS, WBT_CHANNELS, WBT_CONTEXT,
                WBT_FRAME_BEGIN, WBT_FRAME_END,
                -> Unit // negotiation and framing

                WBT_REGION -> region = readRegion(body, width, height)
                WBT_EXTENSION -> decodeTileSet(body, out, width, height, tiles)
                else -> Unit // unknown blocks are skipped by their length, as the spec requires
            }
        }
        // A message without a region block is out of spec; reading it as covering the whole
        // destination keeps such a server's tiles on screen instead of dropping them.
        return DecodedImage(out, painted(tiles, region ?: listOf(RdpRect(0, 0, width, height))))
    }

    /** TS_RFX_REGION's rectangles, clipped to the destination. None at all means all of it (2.2.2.3.3). */
    private fun readRegion(reader: RdpReader, width: Int, height: Int): List<RdpRect> {
        reader.u8() // codecId
        reader.u8() // channelId
        reader.u8() // regionFlags
        val count = reader.u16le()
        if (count * RECT_SIZE > reader.remaining) {
            throw RdpProtocolException("RemoteFX region of $count rectangles does not fit its block")
        }
        if (count == 0) return listOf(RdpRect(0, 0, width, height))
        val rects = ArrayList<RdpRect>(count)
        repeat(count) {
            val rect = intersect(
                RdpRect(reader.u16le(), reader.u16le(), reader.u16le(), reader.u16le()),
                RdpRect(0, 0, width, height),
            )
            if (rect != null) rects += rect
        }
        return rects
    }

    /**
     * Every tile clipped to every region rectangle, with runs that line up in a row joined so a
     * full-width update reports a band rather than one rectangle per tile.
     */
    private fun painted(tiles: List<RdpRect>, region: List<RdpRect>): List<RdpRect> {
        val distinctTiles = tiles.distinct()
        // Both counts are the server's to choose; past the bound, clip to the region's bounding box
        // so one message cannot cost billions of intersections.
        val clip = if (distinctTiles.size.toLong() * region.size > MAX_CLIP_PAIRS) listOfNotNull(bounds(region)) else region
        val pieces = ArrayList<RdpRect>()
        for (tile in distinctTiles) {
            for (rect in clip) intersect(tile, rect)?.let { pieces += it }
        }
        pieces.sortWith(compareBy<RdpRect>({ it.y }, { it.height }, { it.x }))
        val joined = ArrayList<RdpRect>(pieces.size)
        for (piece in pieces) {
            val last = joined.lastOrNull()
            if (last != null && continues(last, piece)) {
                val right = maxOf(last.x + last.width, piece.x + piece.width)
                joined[joined.lastIndex] = RdpRect(last.x, last.y, right - last.x, last.height)
            } else {
                joined += piece
            }
        }
        return joined
    }

    /** Whether [piece] lies in [last]'s band and starts no further right than [last] ends. */
    private fun continues(last: RdpRect, piece: RdpRect) =
        last.y == piece.y && last.height == piece.height && piece.x <= last.x + last.width

    private fun bounds(rects: List<RdpRect>): RdpRect? {
        if (rects.isEmpty()) return null
        val left = rects.minOf { it.x }
        val top = rects.minOf { it.y }
        return RdpRect(left, top, rects.maxOf { it.x + it.width } - left, rects.maxOf { it.y + it.height } - top)
    }

    private fun intersect(a: RdpRect, b: RdpRect): RdpRect? {
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.x + a.width, b.x + b.width)
        val bottom = minOf(a.y + a.height, b.y + b.height)
        return if (right > left && bottom > top) RdpRect(left, top, right - left, bottom - top) else null
    }

    private fun decodeTileSet(reader: RdpReader, out: IntArray, width: Int, height: Int, tiles: MutableList<RdpRect>) {
        reader.u8() // codecId
        reader.u8() // channelId
        val subtype = reader.u16le()
        if (subtype != CBT_TILESET) return
        reader.u16le() // idx
        val properties = reader.u16le()
        val quantCount = reader.u8()
        val tileSize = reader.u8()
        val tileCount = reader.u16le()
        reader.u32le() // tilesDataSize
        if (tileSize != RfxDwt.TILE_SIZE) throw RdpProtocolException("RemoteFX tile size $tileSize")
        if (quantCount !in 1..MAX_QUANT_SETS) throw RdpProtocolException("$quantCount quantization sets")

        // Entropy mode lives in bits 3-4 of the tile set properties.
        val mode = if ((properties shr 3) and 0x03 == ENTROPY_RLGR3) Rlgr.Mode.Rlgr3 else Rlgr.Mode.Rlgr1

        val quants = Array(quantCount) { reader.bytes(RfxDwt.QUANT_SET_SIZE) }

        repeat(tileCount) {
            if (reader.remaining < BLOCK_HEADER_SIZE) return
            val tileType = reader.u16le()
            val tileLength = reader.u32le()
            if (tileLength < BLOCK_HEADER_SIZE || tileLength - BLOCK_HEADER_SIZE > reader.remaining) {
                throw RdpProtocolException("RemoteFX tile of $tileLength bytes does not fit the block")
            }
            val tile = reader.slice(tileLength - BLOCK_HEADER_SIZE)
            if (tileType == CBT_TILE) decodeTile(tile, quants, mode, out, width, height)?.let { tiles += it }
        }
    }

    private fun decodeTile(
        reader: RdpReader,
        quants: Array<ByteArray>,
        mode: Rlgr.Mode,
        out: IntArray,
        width: Int,
        height: Int,
    ): RdpRect? {
        val quantY = reader.u8()
        val quantCb = reader.u8()
        val quantCr = reader.u8()
        val xIdx = reader.u16le()
        val yIdx = reader.u16le()
        val yLength = reader.u16le()
        val cbLength = reader.u16le()
        val crLength = reader.u16le()
        if (quantY >= quants.size || quantCb >= quants.size || quantCr >= quants.size) {
            throw RdpProtocolException("tile references a quantization set that was not sent")
        }
        if (yLength + cbLength + crLength > reader.remaining) {
            throw RdpProtocolException("tile planes claim more bytes than the tile carries")
        }

        val y = plane(reader.bytes(yLength), quants[quantY], mode, scratchPlanes[0])
        val cb = plane(reader.bytes(cbLength), quants[quantCb], mode, scratchPlanes[1])
        val cr = plane(reader.bytes(crLength), quants[quantCr], mode, scratchPlanes[2])

        val originX = xIdx * RfxDwt.TILE_SIZE
        val originY = yIdx * RfxDwt.TILE_SIZE
        for (row in 0 until RfxDwt.TILE_SIZE) {
            val destY = originY + row
            if (destY !in 0 until height) continue
            for (column in 0 until RfxDwt.TILE_SIZE) {
                val destX = originX + column
                if (destX !in 0 until width) continue
                val index = row * RfxDwt.TILE_SIZE + column
                out[destY * width + destX] = RfxColor.ycbcrToArgb(y[index], cb[index], cr[index])
            }
        }
        return intersect(RdpRect(originX, originY, RfxDwt.TILE_SIZE, RfxDwt.TILE_SIZE), RdpRect(0, 0, width, height))
    }

    /**
     * One plane: entropy decode, sum the differentially coded low-pass band, dequantize, then the
     * inverse wavelet transform. The order is the encoder's, reversed — moving dequantization
     * before the differential sum scales differences instead of values.
     */
    private fun plane(data: ByteArray, quants: ByteArray, mode: Rlgr.Mode, into: IntArray): IntArray {
        Rlgr.decode(data, into, mode)
        RfxDwt.differentialDecodeLowPass(into)
        RfxDwt.dequantize(into, quants)
        RfxDwt.inverseTransform(into, scratchDwt)
        return into
    }

    private companion object {
        const val BLOCK_HEADER_SIZE = 6
        const val RECT_SIZE = 8

        const val WBT_SYNC = 0xCCC0
        const val WBT_CODEC_VERSIONS = 0xCCC1
        const val WBT_CHANNELS = 0xCCC2
        const val WBT_CONTEXT = 0xCCC3
        const val WBT_FRAME_BEGIN = 0xCCC4
        const val WBT_FRAME_END = 0xCCC5
        const val WBT_REGION = 0xCCC6
        const val WBT_EXTENSION = 0xCCC7

        const val CBT_TILESET = 0xCAC2
        const val CBT_TILE = 0xCAC3

        const val MAX_QUANT_SETS = 64
        const val MAX_CLIP_PAIRS = 1L shl 20
        const val ENTROPY_RLGR3 = 0x01
    }
}
