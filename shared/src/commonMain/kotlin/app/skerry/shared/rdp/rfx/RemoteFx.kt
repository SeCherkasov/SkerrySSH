package app.skerry.shared.rdp.rfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
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
 */
class RemoteFx : RemoteFxDecoder {

    // Scratch reused across tiles (F-05): three planes decode per tile, six 16 KB arrays a tile
    // before this. Safe because decoding runs on the session's single read loop, and every plane
    // is consumed into `out` before the next tile touches these.
    private val scratchPlanes = Array(3) { IntArray(RfxDwt.TILE_COEFFICIENTS) }
    private val scratchDwt = IntArray(RfxDwt.TILE_COEFFICIENTS)

    override fun decode(data: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
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
                WBT_FRAME_BEGIN, WBT_FRAME_END, WBT_REGION,
                -> Unit // negotiation and framing; the pixels are all in the tile set

                WBT_EXTENSION -> decodeTileSet(body, out, width, height)
                else -> Unit // unknown blocks are skipped by their length, as the spec requires
            }
        }
        return out
    }

    private fun decodeTileSet(reader: RdpReader, out: IntArray, width: Int, height: Int) {
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
            if (tileType == CBT_TILE) decodeTile(tile, quants, mode, out, width, height)
        }
    }

    private fun decodeTile(
        reader: RdpReader,
        quants: Array<ByteArray>,
        mode: Rlgr.Mode,
        out: IntArray,
        width: Int,
        height: Int,
    ) {
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
        const val ENTROPY_RLGR3 = 0x01
    }
}
