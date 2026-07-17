package app.skerry.shared.vnc

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Desktop JPEG decode via ImageIO; `getRGB` yields packed ARGB directly. */
actual fun platformVncImageDecoder(): VncImageDecoder? = VncImageDecoder { jpeg ->
    val img = ImageIO.read(ByteArrayInputStream(jpeg))
        ?: throw VncProtocolException("could not decode Tight JPEG")
    val w = img.width
    val h = img.height
    val argb = IntArray(w * h)
    img.getRGB(0, 0, w, h, argb, 0, w) // TYPE_INT_ARGB packed; JPEG is opaque (alpha 0xFF)
    DecodedImage(argb, w, h)
}
