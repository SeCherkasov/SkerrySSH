package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.rfx.BitReader
import app.skerry.shared.rdp.rfx.RfxColor
import app.skerry.shared.rdp.rfx.RfxDwt
import app.skerry.shared.rdp.rfx.Rlgr

/**
 * The RemoteFX Progressive codec (MS-RDPEGFX 2.2.4.2, 3.2.8) — how a modern Windows desktop
 * actually arrives over the graphics pipeline.
 *
 * A tile is sent coarse first and then refined: the first pass carries entropy-coded wavelet
 * coefficients, and each later pass carries only the bits below what has already been sent. That
 * makes the codec stateful in a way RemoteFX is not — the decoder keeps every tile's coefficients
 * and the sign of each one, because an upgrade pass reads a coefficient's new bits from one of two
 * streams depending on whether its sign is already known.
 *
 * State lives per surface and per tile, and is dropped when the surface goes away. A decoder that
 * loses a pass paints a coarser tile rather than a wrong one, which is why the passes are additive.
 */
class Progressive : ProgressiveDecoder {

    private val surfaces = mutableMapOf<Int, SurfaceTiles>()

    // Scratch reused across tiles (F-05): decoding runs on the session's single read loop, so one
    // set per codec instance is safe, and a 1080p pass otherwise allocates these ~500 times over.
    // TileState's own arrays are NOT scratch — they are the codec's cross-frame state.
    private val scratchComponents = Array(3) { IntArray(TILE_COEFFICIENTS) }
    private val scratchPixels = IntArray(TILE_SIZE * TILE_SIZE)
    private val scratchNumBits = IntArray(BAND_COUNT)
    private val scratchShift = IntArray(BAND_COUNT)
    private val scratchDwt = IntArray(TILE_COEFFICIENTS)

    override fun decode(data: ByteArray, surface: GraphicsSurface, destination: RdpRect): List<RdpRect> {
        // Keyed by id, but only as long as the geometry still matches: a server that rebuilds a
        // surface at a new size may reuse the id, and the tiles held under it then describe a grid
        // that no longer exists — the stale rectangle a resolution change leaves on screen.
        val tiles = surfaces[surface.id]?.takeIf { it.matches(surface) }
            ?: SurfaceTiles(surface.width, surface.height).also { surfaces[surface.id] = it }
        val damaged = mutableListOf<RdpRect>()
        val reader = RdpReader(data)
        while (reader.remaining >= BLOCK_HEADER_SIZE) {
            val blockType = reader.u16le()
            val blockLength = reader.u32le()
            if (blockLength < BLOCK_HEADER_SIZE || blockLength - BLOCK_HEADER_SIZE > reader.remaining) {
                throw RdpProtocolException("a progressive block of $blockLength bytes does not fit the stream")
            }
            val body = reader.slice(blockLength - BLOCK_HEADER_SIZE)
            when (blockType) {
                WBT_CONTEXT -> {
                    body.u8() // ctxId
                    val tileSize = body.u16le()
                    if (tileSize != TILE_SIZE) throw RdpProtocolException("progressive tile size $tileSize")
                    // The sub-band diffing flag describes how the encoder chose its coefficients,
                    // not how they are read back, so there is nothing here to switch on.
                    body.u8()
                }

                WBT_REGION -> region(body, tiles, surface, destination, damaged)

                // Sync carries a magic number, and the frame blocks only bracket the regions.
                WBT_SYNC, WBT_FRAME_BEGIN, WBT_FRAME_END -> Unit
                else -> throw RdpProtocolException("progressive block 0x${blockType.toString(16)}")
            }
        }
        return damaged
    }

    override fun forgetSurface(surfaceId: Int) {
        surfaces.remove(surfaceId)
    }

    private fun region(
        body: RdpReader,
        tiles: SurfaceTiles,
        surface: GraphicsSurface,
        destination: RdpRect,
        damaged: MutableList<RdpRect>,
    ) {
        val tileSize = body.u8()
        val rectCount = body.u16le()
        val quantCount = body.u8()
        val progressiveQuantCount = body.u8()
        val flags = body.u8()
        body.u16le() // numTiles: the tile blocks are bounded by tileDataSize instead
        val tileDataSize = body.u32le()
        if (tileSize != TILE_SIZE) throw RdpProtocolException("progressive region tile size $tileSize")

        val rects = List(rectCount) {
            RdpRect(body.u16le(), body.u16le(), body.u16le(), body.u16le())
        }
        val quants = List(quantCount) { readQuant(body) }
        val progressiveQuants = List(progressiveQuantCount) {
            body.u8() // quality: the index in this list is what a tile refers to
            arrayOf(readQuant(body), readQuant(body), readQuant(body))
        }
        val region = Region(rects, quants, progressiveQuants, flags and REGION_REDUCE_EXTRAPOLATE != 0)

        if (tileDataSize < 0 || tileDataSize > body.remaining) {
            throw RdpProtocolException("progressive tiles of $tileDataSize bytes, ${body.remaining} remain")
        }
        val tileData = body.slice(tileDataSize)
        while (tileData.remaining >= BLOCK_HEADER_SIZE) {
            val blockType = tileData.u16le()
            val blockLength = tileData.u32le()
            if (blockLength < BLOCK_HEADER_SIZE || blockLength - BLOCK_HEADER_SIZE > tileData.remaining) {
                throw RdpProtocolException("a progressive tile of $blockLength bytes does not fit the region")
            }
            val block = tileData.slice(blockLength - BLOCK_HEADER_SIZE)
            when (blockType) {
                WBT_TILE_SIMPLE, WBT_TILE_FIRST -> firstPass(
                    block,
                    simple = blockType == WBT_TILE_SIMPLE,
                    region, tiles, surface, destination, damaged,
                )

                WBT_TILE_UPGRADE -> upgradePass(block, region, tiles, surface, destination, damaged)
                else -> throw RdpProtocolException("progressive tile block 0x${blockType.toString(16)}")
            }
        }
    }

    /** The pass that establishes a tile: entropy-coded coefficients for all three components. */
    private fun firstPass(
        block: RdpReader,
        simple: Boolean,
        region: Region,
        tiles: SurfaceTiles,
        surface: GraphicsSurface,
        destination: RdpRect,
        damaged: MutableList<RdpRect>,
    ) {
        val quantIndices = intArrayOf(block.u8(), block.u8(), block.u8())
        val xIdx = block.u16le()
        val yIdx = block.u16le()
        val flags = block.u8()
        val quality = if (simple) FULL_QUALITY else block.u8()
        val lengths = intArrayOf(block.u16le(), block.u16le(), block.u16le())
        val tailLength = block.u16le()
        val planes = Array(3) { component -> readPlane(block, lengths[component]) }
        block.skip(minOf(tailLength, block.remaining)) // the tail is reserved and carries nothing

        val tile = tiles.at(xIdx, yIdx) ?: return
        val coefficientDifference = flags and TILE_DIFFERENCE != 0
        val components = Array(3) { component ->
            val quant = region.quant(quantIndices[component])
            val progressiveQuant = region.progressiveQuant(quality, component)
            val bands = region.bands()
            val samples = scratchComponents[component]
            Rlgr.decode(planes[component], samples, Rlgr.Mode.Rlgr1)
            val sign = tile.sign[component]
            for (index in 0 until TILE_COEFFICIENTS) sign[index] = clampToShort(samples[index])

            val lowPass = bands[BAND_LL3]
            differentialDecode(samples, lowPass.offset, lowPass.length)
            for (band in bands.indices) {
                val shift = quant[band] + progressiveQuant[band] - 1
                if (shift > 0) shiftBand(samples, bands[band], shift)
                tile.bitPosition[component][band] = quant[band] + progressiveQuant[band]
            }

            // A difference is measured against whatever the tile last held, and a surface starts at
            // zero on both ends — so an unestablished tile is not a reason to refuse one. The only
            // way a difference goes wrong is state dropped on this side while the server kept its
            // own, which is a question of when state is forgotten, not of what this pass may do.
            val current = tile.current[component]
            for (index in 0 until TILE_COEFFICIENTS) {
                val value = if (coefficientDifference) samples[index] + current[index] else samples[index]
                samples[index] = value
                current[index] = clampToShort(value)
            }
            transform(samples, region.extrapolate)
            samples
        }
        paint(components, tile, surface, region, destination, xIdx, yIdx, damaged)
    }

    /**
     * A refinement pass. It carries no coefficients of its own: for every coefficient whose sign is
     * already known the new low bits come from the raw stream, and for the ones still at zero they
     * come from the run-length stream that also says how many stay zero.
     */
    private fun upgradePass(
        block: RdpReader,
        region: Region,
        tiles: SurfaceTiles,
        surface: GraphicsSurface,
        destination: RdpRect,
        damaged: MutableList<RdpRect>,
    ) {
        val quantIndices = intArrayOf(block.u8(), block.u8(), block.u8())
        val xIdx = block.u16le()
        val yIdx = block.u16le()
        val quality = block.u8()
        val srlLengths = IntArray(3)
        val rawLengths = IntArray(3)
        for (component in 0 until 3) {
            srlLengths[component] = block.u16le()
            rawLengths[component] = block.u16le()
        }
        val streams = Array(3) { component ->
            readPlane(block, srlLengths[component]) to readPlane(block, rawLengths[component])
        }

        val tile = tiles.at(xIdx, yIdx) ?: return
        // A refinement of a tile that was never established would refine nothing; the server sends
        // the first pass again after a surface is recreated, so dropping this is safe.
        if (!tile.established) return

        val components = Array(3) { component ->
            val quant = region.quant(quantIndices[component])
            val progressiveQuant = region.progressiveQuant(quality, component)
            val bitPosition = tile.bitPosition[component]
            val numBits = scratchNumBits
            val shift = scratchShift
            for (band in 0 until BAND_COUNT) {
                val position = quant[band] + progressiveQuant[band]
                numBits[band] = bitPosition[band] - position
                if (numBits[band] < 0) throw RdpProtocolException("a progressive pass that loses precision")
                // A server is required to quantize by at least six; clamping keeps a stream that
                // does not from turning into a shift by a negative amount.
                shift[band] = (position - 1).coerceAtLeast(0)
                bitPosition[band] = position
            }
            val (srl, raw) = streams[component]
            upgradeComponent(tile, component, numBits, shift, srl, raw)
            val samples = scratchComponents[component]
            for (index in 0 until TILE_COEFFICIENTS) samples[index] = tile.current[component][index].toInt()
            // An upgrade always speaks the extrapolated layout; it is the only one Windows encodes.
            transform(samples, extrapolate = true)
            samples
        }
        paint(components, tile, surface, region, destination, xIdx, yIdx, damaged)
    }

    private fun upgradeComponent(
        tile: TileState,
        component: Int,
        numBits: IntArray,
        shift: IntArray,
        srlData: ByteArray,
        rawData: ByteArray,
    ) {
        val state = UpgradeState(BitReader(srlData), BitReader(rawData))
        val current = tile.current[component]
        val sign = tile.sign[component]
        for (band in 0 until BAND_COUNT) {
            // The low-pass band has no sign stream: every coefficient there is already non-zero.
            state.lowPass = band == BAND_LL3
            upgradeBand(state, current, sign, EXTRAPOLATE_BANDS[band], shift[band], numBits[band])
        }
    }

    private fun upgradeBand(
        state: UpgradeState,
        current: ShortArray,
        sign: ShortArray,
        band: Band,
        shift: Int,
        numBits: Int,
    ) {
        if (numBits < 1) return
        for (index in band.offset until band.offset + band.length) {
            val value = when {
                state.lowPass -> state.raw.readBits(numBits)
                sign[index] > 0 -> state.raw.readBits(numBits)
                sign[index] < 0 -> -state.raw.readBits(numBits)
                else -> state.srlRead(numBits).also { sign[index] = clampToShort(it) }
            }
            current[index] = clampToShort(current[index] + (value shl shift))
        }
    }

    private fun transform(samples: IntArray, extrapolate: Boolean) {
        if (extrapolate) ProgressiveDwt.inverse(samples, scratchDwt) else RfxDwt.inverseTransform(samples, scratchDwt)
    }

    /**
     * Turn the three decoded components into pixels and copy them onto the surface, clipped to the
     * rectangles the region declared: a tile on the edge of a region carries samples outside it,
     * and painting those would smear the previous frame's contents over the neighbour.
     */
    private fun paint(
        components: Array<IntArray>,
        state: TileState,
        surface: GraphicsSurface,
        region: Region,
        destination: RdpRect,
        xIdx: Int,
        yIdx: Int,
        damaged: MutableList<RdpRect>,
    ) {
        state.established = true
        val pixels = scratchPixels
        for (index in 0 until TILE_SIZE * TILE_SIZE) {
            pixels[index] = RfxColor.ycbcrToArgb(components[0][index], components[1][index], components[2][index])
        }
        val tileX = destination.x + xIdx * TILE_SIZE
        val tileY = destination.y + yIdx * TILE_SIZE
        for (rect in region.rects) {
            val clip = intersect(
                RdpRect(tileX, tileY, TILE_SIZE, TILE_SIZE),
                RdpRect(destination.x + rect.x, destination.y + rect.y, rect.width, rect.height),
            ) ?: continue
            val visible = surface.clip(clip)
            if (visible.width == 0 || visible.height == 0) continue
            for (row in 0 until visible.height) {
                val source = (visible.y - tileY + row) * TILE_SIZE + (visible.x - tileX)
                pixels.copyInto(
                    surface.pixels,
                    destinationOffset = (visible.y + row) * surface.width + visible.x,
                    startIndex = source,
                    endIndex = source + visible.width,
                )
            }
            damaged += visible
        }
    }

    private fun intersect(first: RdpRect, second: RdpRect): RdpRect? {
        val left = maxOf(first.x, second.x)
        val top = maxOf(first.y, second.y)
        val right = minOf(first.x + first.width, second.x + second.width)
        val bottom = minOf(first.y + first.height, second.y + second.height)
        if (right <= left || bottom <= top) return null
        return RdpRect(left, top, right - left, bottom - top)
    }

    private fun readPlane(reader: RdpReader, length: Int): ByteArray {
        if (length < 0 || length > reader.remaining) {
            throw RdpProtocolException("a progressive plane of $length bytes, ${reader.remaining} remain")
        }
        return reader.bytes(length)
    }

    /** Ten 4-bit factors, in the order this codec packs them — not RemoteFX's order. */
    private fun readQuant(reader: RdpReader): IntArray {
        val values = IntArray(BAND_COUNT)
        for (pair in QUANT_ORDER.indices step 2) {
            val byte = reader.u8()
            values[QUANT_ORDER[pair]] = byte and 0x0F
            values[QUANT_ORDER[pair + 1]] = (byte shr 4) and 0x0F
        }
        return values
    }

    private fun differentialDecode(samples: IntArray, offset: Int, length: Int) {
        for (index in offset + 1 until offset + length) samples[index] += samples[index - 1]
    }

    private fun shiftBand(samples: IntArray, band: Band, shift: Int) {
        for (index in band.offset until band.offset + band.length) samples[index] = samples[index] shl shift
    }

    private fun clampToShort(value: Int): Short =
        value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Where a sub-band starts in the coefficient buffer, and how many coefficients it holds. */
    private class Band(val offset: Int, val length: Int)

    private class Region(
        val rects: List<RdpRect>,
        private val quants: List<IntArray>,
        private val progressiveQuants: List<Array<IntArray>>,
        val extrapolate: Boolean,
    ) {
        fun quant(index: Int): IntArray =
            quants.getOrNull(index) ?: throw RdpProtocolException("a tile using quantization set $index")

        /**
         * The progressive quantization to add on top. The full-quality marker means "nothing to
         * add": the tile is already at the precision the non-progressive factors describe.
         */
        fun progressiveQuant(quality: Int, component: Int): IntArray {
            if (quality == FULL_QUALITY) return NO_PROGRESSIVE_QUANT
            val set = progressiveQuants.getOrNull(quality)
                ?: throw RdpProtocolException("a tile using progressive quality $quality")
            return set[component]
        }

        fun bands(): Array<Band> = if (extrapolate) EXTRAPOLATE_BANDS else CLASSIC_BANDS
    }

    private class TileState {
        /** The coefficients decoded so far, which every later pass adds to. */
        val current = Array(3) { ShortArray(TILE_COEFFICIENTS) }

        /** The sign of each coefficient, which decides where an upgrade pass reads its bits. */
        val sign = Array(3) { ShortArray(TILE_COEFFICIENTS) }

        /** Per component and band, the bit the next pass will start at. */
        val bitPosition = Array(3) { IntArray(BAND_COUNT) }

        var established = false
    }

    private class SurfaceTiles(private val width: Int, private val height: Int) {
        private val gridWidth = (width + TILE_SIZE - 1) / TILE_SIZE
        private val gridHeight = (height + TILE_SIZE - 1) / TILE_SIZE
        private val tiles = arrayOfNulls<TileState>(maxOf(gridWidth * gridHeight, 0))

        /** Whether this state was built for [surface] as it is now, rather than an earlier shape. */
        fun matches(surface: GraphicsSurface) = surface.width == width && surface.height == height

        /** Null for a tile outside the surface — a stream may describe more than the surface holds. */
        fun at(xIdx: Int, yIdx: Int): TileState? {
            if (xIdx < 0 || yIdx < 0 || xIdx >= gridWidth || yIdx >= gridHeight) return null
            val index = yIdx * gridWidth + xIdx
            return tiles[index] ?: TileState().also { tiles[index] = it }
        }
    }

    /**
     * The two bit streams an upgrade pass reads from, and the run-length state of the sign stream.
     * The parameter `kp` adapts as runs of zeroes go by, exactly as the entropy coder's does.
     */
    private class UpgradeState(val srl: BitReader, val raw: BitReader) {
        var lowPass = false
        private var kp = 8
        private var unary = false
        private var zeroes = 0

        fun srlRead(numBits: Int): Int {
            // Once the stream is spent nothing more changes, and saying so here is what keeps a
            // truncated stream from being read as thousands of maximum-length magnitudes.
            if (srl.remaining <= 0) return 0
            if (zeroes > 0) {
                zeroes--
                return 0
            }
            val k = kp / 8
            if (!unary) {
                if (srl.readBit() == 0) {
                    // A zero bit means at least 2^k coefficients stay zero, and raises k.
                    zeroes = (1 shl k) - 1
                    kp = minOf(kp + 4, KP_MAX)
                    return 0
                }
                unary = true
                zeroes = if (k > 0) srl.readBits(k) else 0
                if (zeroes > 0) {
                    zeroes--
                    return 0
                }
            }
            unary = false
            val negative = srl.readBit() == 1
            kp = if (kp < 6) 0 else kp - 6
            if (numBits == 1) return if (negative) -1 else 1
            var magnitude = 1
            val maximum = (1 shl numBits) - 1
            while (magnitude < maximum && srl.readBit() == 0) magnitude++
            return if (negative) -magnitude else magnitude
        }
    }

    companion object {
        const val TILE_SIZE = 64
        const val TILE_COEFFICIENTS = TILE_SIZE * TILE_SIZE

        private const val BLOCK_HEADER_SIZE = 6

        private const val WBT_SYNC = 0xCCC0
        private const val WBT_FRAME_BEGIN = 0xCCC1
        private const val WBT_FRAME_END = 0xCCC2
        private const val WBT_CONTEXT = 0xCCC3
        private const val WBT_REGION = 0xCCC4
        private const val WBT_TILE_SIMPLE = 0xCCC5
        private const val WBT_TILE_FIRST = 0xCCC6
        private const val WBT_TILE_UPGRADE = 0xCCC7

        private const val REGION_REDUCE_EXTRAPOLATE = 0x01
        private const val TILE_DIFFERENCE = 0x01

        /** A tile at full quality carries no progressive quantization to add. */
        private const val FULL_QUALITY = 0xFF

        private const val KP_MAX = 80

        private const val BAND_COUNT = 10
        private const val BAND_LL3 = 9

        private val NO_PROGRESSIVE_QUANT = IntArray(BAND_COUNT)

        /**
         * Bands in the order they are processed, finest first: HL1, LH1, HH1, HL2, LH2, HH2, HL3,
         * LH3, HH3 and the low-pass image last.
         */
        private val CLASSIC_BANDS = arrayOf(
            Band(0, 1024), Band(1024, 1024), Band(2048, 1024),
            Band(3072, 256), Band(3328, 256), Band(3584, 256),
            Band(3840, 64), Band(3904, 64), Band(3968, 64),
            Band(4032, 64),
        )

        /** The extrapolated layout: every band is a sample wider, so none of them line up neatly. */
        private val EXTRAPOLATE_BANDS = arrayOf(
            Band(0, 1023), Band(1023, 1023), Band(2046, 961),
            Band(3007, 272), Band(3279, 272), Band(3551, 256),
            Band(3807, 72), Band(3879, 72), Band(3951, 64),
            Band(4015, 81),
        )

        /** Which band each quantization nibble belongs to, in wire order. */
        private val QUANT_ORDER = intArrayOf(9, 6, 7, 8, 3, 4, 5, 0, 1, 2)
    }
}
