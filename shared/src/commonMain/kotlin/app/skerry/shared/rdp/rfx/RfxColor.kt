package app.skerry.shared.rdp.rfx

/**
 * The colour transform shared by RemoteFX and the progressive codec (MS-RDPRFX 3.1.8.1.3).
 *
 * The wavelet stage leaves samples as 11.5 fixed-point numbers — five fractional bits — and centred
 * on zero, so the luma offset is 128 in that scale (4096) and the result is shifted down by five at
 * the end. Treating the samples as plain 0..255 integers instead produces a picture that is
 * saturated everywhere, which is the failure this scaling exists to avoid.
 */
object RfxColor {

    private const val OPAQUE = 0xFF shl 24

    /** Luma offset (128) in the 11.5 fixed-point scale the coefficients use. */
    private const val LUMA_OFFSET = 128 shl 5

    private const val FRACTION_BITS = 16
    private const val CR_TO_RED = 91916 // 1.402525
    private const val CR_TO_GREEN = 46819 // 0.714401
    private const val CB_TO_GREEN = 22527 // 0.343730
    private const val CB_TO_BLUE = 115992 // 1.769905

    /** One pixel from its Y, Cb and Cr coefficients, as opaque ARGB. */
    fun ycbcrToArgb(y: Int, cb: Int, cr: Int): Int {
        val luma = (y + LUMA_OFFSET) shl FRACTION_BITS
        val red = ((luma + cr * CR_TO_RED) shr FRACTION_BITS) shr 5
        val green = ((luma - cb * CB_TO_GREEN - cr * CR_TO_GREEN) shr FRACTION_BITS) shr 5
        val blue = ((luma + cb * CB_TO_BLUE) shr FRACTION_BITS) shr 5
        return OPAQUE or (clamp(red) shl 16) or (clamp(green) shl 8) or clamp(blue)
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
