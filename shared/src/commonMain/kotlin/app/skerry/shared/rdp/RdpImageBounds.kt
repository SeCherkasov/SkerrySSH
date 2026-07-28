package app.skerry.shared.rdp

/**
 * The size a server is allowed to declare for an image before the client allocates one.
 *
 * Every codec takes its width and height straight off the wire and allocates the pixels before a
 * byte of the payload is read, so a rectangle of 65535x32000 costs eight gigabytes of heap for a
 * header of a dozen bytes. The product is computed in `Long` because the multiplication is itself
 * part of the problem: 65535 * 65535 wraps to a negative `Int`, and what that allocates is a
 * `NegativeArraySizeException` rather than a picture.
 *
 * The failure has to be an [RdpProtocolException] and nothing else: that is what the read loop and
 * the per-rectangle recovery of a bitmap update are written to catch, and an `OutOfMemoryError` is
 * an `Error`, which neither of them sees.
 */
internal object RdpImageBounds {

    /** Neither a desktop nor an offscreen surface of one is wider or taller than this. */
    const val MAX_DIMENSION = 16384

    /** 64 megapixels — a 16K-wide desktop four thousand pixels tall, and 256 MB as ARGB. */
    const val MAX_PIXELS = 1 shl 26

    /** @throws RdpProtocolException [width] by [height] is not a picture this client will allocate */
    fun requireSize(width: Int, height: Int, what: String) {
        if (width <= 0 || height <= 0 ||
            width > MAX_DIMENSION || height > MAX_DIMENSION ||
            width.toLong() * height > MAX_PIXELS
        ) {
            throw RdpProtocolException("$what of ${width}x$height")
        }
    }
}
