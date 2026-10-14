package app.skerry.shared.rdp

/** Legacy bitmap and surface streams are bottom-up; graphics-pipeline surfaces are top-down. */
internal fun bottomUpToTopDown(pixels: IntArray, width: Int, height: Int): IntArray {
    for (row in 0 until height / 2) {
        val upper = row * width
        val lower = (height - 1 - row) * width
        for (column in 0 until width) {
            val saved = pixels[upper + column]
            pixels[upper + column] = pixels[lower + column]
            pixels[lower + column] = saved
        }
    }
    return pixels
}
