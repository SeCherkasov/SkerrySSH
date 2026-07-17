package app.skerry.shared.vnc

import android.graphics.BitmapFactory

/** Android JPEG decode via BitmapFactory; `getPixels` yields packed ARGB directly. */
actual fun platformVncImageDecoder(): VncImageDecoder? = VncImageDecoder { jpeg ->
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        ?: throw VncProtocolException("could not decode Tight JPEG")
    val w = bmp.width
    val h = bmp.height
    val argb = IntArray(w * h)
    bmp.getPixels(argb, 0, w, 0, 0, w, h)
    bmp.recycle()
    DecodedImage(argb, w, h)
}
