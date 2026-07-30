package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpRect

/**
 * One H.264 frame as the graphics pipeline wraps it (MS-RDPEGFX 2.2.4.4 RFX_AVC420_BITMAP_STREAM):
 * the frame covers the whole surface, and [regions] says which parts of it the server actually
 * redrew. Everything outside them is the previous picture, which the decoder still needs — the frame
 * is a difference — but which must not be painted again.
 *
 * The rectangles are in surface coordinates, not relative to the rectangle the surface command names.
 */
internal class Avc420Stream(val regions: List<RdpRect>, val bitstream: ByteArray)

/**
 * The 4:4:4 pair (MS-RDPEGFX 2.2.4.5 RFX_AVC444_BITMAP_STREAM).
 *
 * [luma] carries a 4:2:0 picture of the desktop; [chroma] carries a second 4:2:0 picture whose planes
 * are packed with the chroma samples the first one had to drop. Either may be absent: the server
 * sends only the half that changed.
 */
internal class Avc444Stream(val luma: Avc420Stream?, val chroma: Avc420Stream?)

/**
 * Read a 4:2:0 bitmap stream. [bitstreamLength] bounds the H.264 frame when a length is known from
 * outside (the first half of a 4:4:4 message); `null` means the frame runs to the end of [reader].
 */
internal fun readAvc420Stream(reader: RdpReader, bitstreamLength: Int? = null): Avc420Stream {
    val regions = readMetaBlock(reader)
    val bitstream = if (bitstreamLength == null) reader.rest() else reader.bytes(bitstreamLength)
    return Avc420Stream(regions, bitstream)
}

/**
 * Read the 4:4:4 pair. The header's low 30 bits are the byte length of the first stream *including*
 * its own region list, and the top two are which halves are present.
 */
internal fun readAvc444Stream(data: ByteArray): Avc444Stream {
    val reader = RdpReader(data)
    val header = reader.u32le()
    val declared = header and DECLARED_LENGTH_MASK
    val contents = header ushr CONTENTS_SHIFT
    val start = reader.position
    return when (contents) {
        CONTENTS_BOTH -> {
            val regions = readMetaBlock(reader)
            val consumed = reader.position - start
            if (declared < consumed) {
                throw RdpProtocolException("a 4:4:4 luma stream of $declared bytes has $consumed of region list")
            }
            val luma = Avc420Stream(regions, reader.bytes(declared - consumed))
            Avc444Stream(luma = luma, chroma = readAvc420Stream(reader))
        }

        CONTENTS_LUMA -> Avc444Stream(luma = readAvc420Stream(reader), chroma = null)
        CONTENTS_CHROMA -> Avc444Stream(luma = null, chroma = readAvc420Stream(reader))
        else -> throw RdpProtocolException("a 4:4:4 bitmap stream that carries neither luma nor chroma")
    }
}

/**
 * The region list (MS-RDPEGFX 2.2.4.4.1). The quantiser and quality that follow each rectangle
 * describe how the server encoded it, not how it is read back, so they are consumed and dropped.
 */
private fun readMetaBlock(reader: RdpReader): List<RdpRect> {
    val count = reader.u32le()
    // Bounded by what is left rather than by a constant: a rectangle costs eight bytes here and two
    // more in the quantiser list, so a count past that is a lie — and building the list first would
    // allocate for the declared count before the first read could refuse it.
    if (count < 0 || count > reader.remaining / RECT_AND_QUANT_SIZE) {
        throw RdpProtocolException("a region list of $count rectangles, ${reader.remaining} bytes remain")
    }
    val rects = List(count) {
        val left = reader.u16le()
        val top = reader.u16le()
        val right = reader.u16le()
        val bottom = reader.u16le()
        RdpRect(left, top, right - left, bottom - top)
    }
    repeat(count) { reader.u16le() }
    return rects
}

private const val DECLARED_LENGTH_MASK = 0x3FFFFFFF
private const val CONTENTS_SHIFT = 30

/** Both halves: the 4:2:0 picture in the first stream, the packed chroma in the second. */
private const val CONTENTS_BOTH = 0

/** Only the 4:2:0 picture — the chroma the client already has still applies. */
private const val CONTENTS_LUMA = 1

/** Only the packed chroma, over the luma of an earlier message. */
private const val CONTENTS_CHROMA = 2

private const val RECT_AND_QUANT_SIZE = 10
