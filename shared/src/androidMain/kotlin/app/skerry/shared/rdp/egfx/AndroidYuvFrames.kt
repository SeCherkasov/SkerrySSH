package app.skerry.shared.rdp.egfx

import android.media.Image
import app.skerry.shared.rdp.RdpRect
import java.nio.ByteBuffer

/**
 * One plane of a decoded picture as `MediaCodec` hands it over: the bytes, the distance between rows,
 * and the distance between two samples in a row.
 *
 * That last one is why this class exists. A decoder asked for flexible 4:2:0 may answer with planar
 * chroma (a stride of one) or with the two chroma planes interleaved into one (a stride of two, the
 * NV12 layout every hardware decoder prefers), and both are the same picture packed differently.
 */
class AndroidImagePlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * Turns a `MediaCodec` picture into the compact 4:2:0 the graphics pipeline decodes from, reusing its
 * buffers across pictures — at a couple of megabytes each, allocating per picture would make the
 * collector the slowest part of drawing the screen.
 *
 * Public, and split from the decoder that drives it, for the same reason as `AndroidAudioMapping`:
 * a `MediaCodec` cannot be started off a device, while the packing above can be got wrong in silence.
 * Its tests live in `:androidApp`.
 */
class AndroidYuvFrames {

    private var luma = ByteArray(0)
    private var chromaU = ByteArray(0)
    private var chromaV = ByteArray(0)

    /** The picture inside [image]'s crop, or `null` when it is in a layout this cannot read. */
    fun frame(image: Image): YuvFrame? {
        val crop = image.cropRect
        return frame(
            planes = image.planes.map { AndroidImagePlane(it.buffer, it.rowStride, it.pixelStride) },
            width = crop.width(),
            height = crop.height(),
            left = crop.left,
            top = crop.top,
        )
    }

    /**
     * The picture [width]×[height] at ([left], [top]) of [planes], copied out tightly packed.
     *
     * The crop is the encoded picture, which is not the whole plane: a decoder pads to whole
     * macroblocks, and on some devices the padding is there even when the size divides evenly.
     */
    fun frame(planes: List<AndroidImagePlane>, width: Int, height: Int, left: Int = 0, top: Int = 0): YuvFrame? {
        if (planes.size < PLANES) return null
        if (width <= 0 || height <= 0) return null
        if (left < 0 || top < 0) return null
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        luma = sized(luma, width * height)
        chromaU = sized(chromaU, chromaWidth * chromaHeight)
        chromaV = sized(chromaV, chromaWidth * chromaHeight)
        val chroma = RdpRect(left / 2, top / 2, chromaWidth, chromaHeight)
        copy(planes[0], RdpRect(left, top, width, height), luma)
        copy(planes[1], chroma, chromaU)
        copy(planes[2], chroma, chromaV)
        return YuvFrame(luma, chromaU, chromaV, width, height, yStride = width, chromaStride = chromaWidth)
    }

    /** Copy [crop] out of [plane] into [target], tightly packed at [crop]'s own width. */
    private fun copy(plane: AndroidImagePlane, crop: RdpRect, target: ByteArray) {
        // A view of its own: the position is moved to read whole rows, and the buffer belongs to the
        // decoder, which hands the same one over for the next picture.
        val view = plane.buffer.duplicate()
        for (row in 0 until crop.height) {
            var source = (crop.y + row) * plane.rowStride + crop.x * plane.pixelStride
            val destination = row * crop.width
            if (plane.pixelStride == 1) {
                view.position(source)
                view.get(target, destination, crop.width)
            } else {
                for (col in 0 until crop.width) {
                    target[destination + col] = view.get(source)
                    source += plane.pixelStride
                }
            }
        }
    }

    private fun sized(current: ByteArray, size: Int): ByteArray =
        if (current.size >= size) current else ByteArray(size)

    private companion object {
        const val PLANES = 3
    }
}
