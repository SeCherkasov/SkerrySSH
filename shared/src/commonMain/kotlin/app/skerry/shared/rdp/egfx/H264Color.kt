package app.skerry.shared.rdp.egfx

/**
 * The colour transform of the H.264 codecs (MS-RDPEGFX 3.3.8.3.1): full-range BT.709, on plain 8-bit
 * samples.
 *
 * Separate from `rfx/RfxColor` on purpose. That one is BT.601 on wavelet coefficients — 11.5
 * fixed-point, centred on zero — and the two agree on neither the matrix nor the scale; sharing one
 * would mean a parameter that is wrong for one caller by construction.
 */
internal object H264Color {

    private const val OPAQUE = 0xFF shl 24

    private const val FRACTION_BITS = 8
    private const val LUMA = 256
    private const val V_TO_RED = 403
    private const val U_TO_GREEN = 48
    private const val V_TO_GREEN = 120
    private const val U_TO_BLUE = 475

    /** One pixel from its luma and chroma samples, as opaque ARGB. */
    fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val luma = LUMA * y
        val chromaU = u - 128
        val chromaV = v - 128
        val red = (luma + V_TO_RED * chromaV) shr FRACTION_BITS
        val green = (luma - U_TO_GREEN * chromaU - V_TO_GREEN * chromaV) shr FRACTION_BITS
        val blue = (luma + U_TO_BLUE * chromaU) shr FRACTION_BITS
        return OPAQUE or (clamp(red) shl 16) or (clamp(green) shl 8) or clamp(blue)
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
