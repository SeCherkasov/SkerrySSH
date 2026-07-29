package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The progressive codec on tiles it did not build itself — the vectors in [ProgressiveVectors].
 *
 * `ProgressiveTest` covers what can be reasoned about by hand: an empty tile is mid grey, a band
 * lands where the layout says it does. None of that catches the errors that still decode to a
 * picture — a quantization factor read into the wrong band, a shift off by one, a sub-band offset
 * that is right for the classic layout and wrong for the extrapolated one. Those need a stream
 * somebody else produced and pixels somebody else's decoder agreed on.
 */
class ProgressiveCaptureTest {

    private val surface = GraphicsSurface(id = 1, width = TILE, height = TILE)
    private val codec = Progressive()

    @Test
    fun `a picture encoded elsewhere decodes back into that picture`() {
        codec.decode(ProgressiveVectors.PICTURE, surface, DESTINATION)

        val source = ProgressiveVectors.sourcePicture()
        val offBy = surface.pixels.indices.map { deviation(source[it], surface.pixels[it]) }
        // The codec is lossy, and the loss lives on the edges of the picture — but only there. A
        // dequantization or scaling error is not a matter of a few units across an edge: it
        // multiplies a whole sub-band, and the tile stops resembling what was encoded at all.
        assertTrue(offBy.max() <= EDGE_TOLERANCE, "the tile is off the picture by ${offBy.max()}")
        // Every part of the picture that is not an edge, so that the bound above cannot be met by
        // a decoder that is quietly wrong everywhere instead of loudly wrong on one boundary.
        val panel = worstOver(offBy, x = 24, y = 12, width = 16, height = 8)
        assertTrue(panel <= FLAT_TOLERANCE, "the flat panel came back off by $panel")
        val desktop = worstOver(offBy, x = 2, y = 2, width = 16, height = 5)
        assertTrue(desktop <= FLAT_TOLERANCE, "the flat background came back off by $desktop")
        val gradient = worstOver(offBy, x = 4, y = 44, width = 56, height = 16)
        assertTrue(gradient <= FLAT_TOLERANCE, "the gradient came back off by $gradient")
    }

    @Test
    fun `the classic layout decodes to the pixels the other decoder produces`() {
        codec.decode(ProgressiveVectors.PICTURE, surface, DESTINATION)

        assertTile(ProgressiveVectors.PICTURE_SAMPLE, ProgressiveVectors.PICTURE_FINGERPRINT)
    }

    @Test
    fun `the extrapolated layout decodes to the pixels the other decoder produces`() {
        // The layout a Windows server actually encodes: every sub-band a sample wider than the
        // classic one, and a different inverse transform to match.
        codec.decode(ProgressiveVectors.EXTRAPOLATED, surface, DESTINATION)

        assertTile(ProgressiveVectors.EXTRAPOLATED_SAMPLE, ProgressiveVectors.EXTRAPOLATED_FINGERPRINT)
    }

    @Test
    fun `every sub-band boundary of the extrapolated layout is where the other decoder has it`() {
        // The picture above cannot show this: an encoder leaves runs of zeroes around the sub-band
        // boundaries, so a boundary in the wrong place reads a zero either way. This tile carries a
        // coefficient on every boundary of both layouts, under factors that differ between
        // neighbouring sub-bands — an offset or a length off by one then changes the picture.
        codec.decode(ProgressiveVectors.DENSE, surface, DESTINATION)

        assertTile(ProgressiveVectors.DENSE_SAMPLE, ProgressiveVectors.DENSE_FINGERPRINT)
    }

    @Test
    fun `a refinement pass leaves the tile where the other decoder leaves it`() {
        codec.decode(ProgressiveVectors.UPGRADE_FIRST, surface, DESTINATION)
        assertTile(
            ProgressiveVectors.UPGRADE_FIRST_SAMPLE,
            ProgressiveVectors.UPGRADE_FIRST_FINGERPRINT,
            "after the first pass",
        )

        codec.decode(ProgressiveVectors.UPGRADE_SECOND, surface, DESTINATION)
        assertTile(
            ProgressiveVectors.UPGRADE_SECOND_SAMPLE,
            ProgressiveVectors.UPGRADE_SECOND_FINGERPRINT,
            "after the first refinement",
        )

        // The pass that matters most: the refinement before it fixed the sign of some coefficients,
        // so this one reads those from the raw stream and the ones still at zero from the other.
        codec.decode(ProgressiveVectors.UPGRADE_THIRD, surface, DESTINATION)
        assertTile(
            ProgressiveVectors.UPGRADE_THIRD_SAMPLE,
            ProgressiveVectors.UPGRADE_THIRD_FINGERPRINT,
            "after the second refinement",
        )
    }

    /**
     * Compare the tile with what the other decoder made of it: every eighth pixel by value, so that
     * a failure names one, and the whole tile by its fold, so that nothing hides between them.
     */
    private fun assertTile(expected: List<Int>, fingerprint: Int, stage: String = "") {
        val sampled = (0 until TILE step STEP).flatMap { y ->
            (0 until TILE step STEP).map { x -> surface.pixels[y * TILE + x] }
        }
        val first = expected.indices.firstOrNull { expected[it] != sampled[it] }
        if (first != null) {
            val x = (first % (TILE / STEP)) * STEP
            val y = (first / (TILE / STEP)) * STEP
            assertEquals(asHex(expected[first]), asHex(sampled[first]), "pixel ($x, $y) $stage")
        }
        assertEquals(
            fingerprint,
            ProgressiveVectors.fingerprint(surface.pixels),
            "the tile differs from the reference away from the sampled pixels $stage",
        )
    }

    private fun asHex(pixel: Int): String = pixel.toUInt().toString(16)

    private fun deviation(expected: Int, actual: Int): Int =
        (0 until 24 step 8).maxOf { abs(((expected shr it) and 0xFF) - ((actual shr it) and 0xFF)) }

    private fun worstOver(offBy: List<Int>, x: Int, y: Int, width: Int, height: Int): Int =
        (y until y + height).maxOf { row -> (x until x + width).maxOf { offBy[row * TILE + it] } }

    private companion object {
        const val TILE = 64
        const val STEP = 8

        val DESTINATION = RdpRect(0, 0, TILE, TILE)

        /** Measured on this vector: the worst sample sits on the hard edge of the light panel. */
        const val EDGE_TOLERANCE = 20

        /** Away from an edge the wavelet has nothing to ring against, and the loss is a rounding. */
        const val FLAT_TOLERANCE = 3
    }
}
