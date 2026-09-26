package app.skerry.shared.rdp.rfx

import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpWriter

/** RemoteFX messages built by hand for tests that need a region and real tiles. */
internal object RfxTestStreams {

    /** A tile at grid position ([xIdx], [yIdx]) with empty planes, which decodes to flat mid grey. */
    fun greyTile(xIdx: Int, yIdx: Int): ByteArray = RdpWriter(19).apply {
        u16le(0xCAC3) // CBT_TILE
        u32le(6 + 13)
        u8(0).u8(0).u8(0) // quantIdxY, Cb, Cr
        u16le(xIdx).u16le(yIdx)
        u16le(0).u16le(0).u16le(0) // YLen, CbLen, CrLen
    }.toByteArray()

    /** WBT_REGION (MS-RDPRFX 2.2.2.3.3) listing [rects]. */
    fun region(rects: List<RdpRect>): ByteArray {
        val bodySize = 2 + 1 + 2 + rects.size * 8 + 4
        return RdpWriter(bodySize + 6).apply {
            u16le(0xCCC6) // WBT_REGION
            u32le(bodySize + 6)
            u8(1).u8(0) // codecId, channelId
            u8(1) // regionFlags: lrf
            u16le(rects.size)
            for (rect in rects) u16le(rect.x).u16le(rect.y).u16le(rect.width).u16le(rect.height)
            u16le(0xCAC1) // regionType: CBT_REGION
            u16le(1) // numTilesets
        }.toByteArray()
    }

    /** WBT_EXTENSION carrying a tile set of [tiles]. */
    fun tileSet(tiles: List<ByteArray>): ByteArray {
        val tileBytes = tiles.fold(ByteArray(0)) { all, tile -> all + tile }
        val body = RdpWriter(22 + tileBytes.size).apply {
            u8(1).u8(0) // codecId, channelId
            u16le(0xCAC2) // CBT_TILESET
            u16le(0) // idx
            u16le(0) // properties: RLGR1
            u8(1) // numQuant
            u8(64) // tileSize
            u16le(tiles.size)
            u32le(tileBytes.size)
            bytes(byteArrayOf(0x66, 0x66, 0x66, 0x66, 0x66))
            bytes(tileBytes)
        }.toByteArray()
        return RdpWriter(body.size + 6).apply {
            u16le(0xCCC7) // WBT_EXTENSION
            u32le(body.size + 6)
            bytes(body)
        }.toByteArray()
    }

    fun message(regionRects: List<RdpRect>, tiles: List<ByteArray>): ByteArray =
        region(regionRects) + tileSet(tiles)

    const val GREY = 0xFF808080.toInt()
}
