package app.skerry.shared.rdp.rfx

import app.skerry.shared.rdp.RdpProtocolException

/**
 * RLGR — the adaptive Run-Length/Golomb-Rice entropy coder of RemoteFX (MS-RDPRFX 3.1.8.1.7), also
 * used for the first pass of the progressive codec.
 *
 * Two modes steered by one adaptive parameter `k`. While `k > 0` the coder is in run-length mode,
 * where a stretch of zero coefficients — which is what a wavelet-transformed tile mostly is — costs
 * a handful of bits. The value that ends a run drops `k`, and the coder falls into Golomb-Rice mode,
 * which codes magnitudes directly. Every parameter update is derived from the data, so nothing is
 * transmitted; the decoder must move in lockstep with the encoder, and one missed update turns
 * everything after it into noise.
 *
 * RLGR1 and RLGR3 differ only in Golomb-Rice mode: RLGR3 packs two coefficients into one code word.
 */
object Rlgr {

    /** Which variant the stream uses; announced in the tile set properties. */
    enum class Mode { Rlgr1, Rlgr3 }

    internal const val KPMAX = 80
    internal const val LSGR = 3
    private const val UP_GR = 4
    private const val DN_GR = 6
    private const val UQ_GR = 3
    private const val DQ_GR = 3

    /**
     * Decode [data] into [count] coefficients. A stream that ends mid-symbol simply stops: the last
     * byte of a plane is padded with bits that mean nothing, and the coefficients not reached are
     * zero.
     *
     * @throws RdpProtocolException the stream codes an implausible prefix
     */
    fun decode(data: ByteArray, count: Int, mode: Mode): IntArray {
        require(count >= 0) { "negative coefficient count" }
        val out = IntArray(count)
        Decoder(BitReader(data), mode).decodeInto(out)
        return out
    }

    /**
     * [decode] into a caller-owned [out] (F-05's buffer reuse). Zeroed first: a stream that ends
     * early must leave zeroes behind it, exactly as a fresh allocation would — the previous tile's
     * coefficients bleeding through would paint ghosts of it.
     */
    fun decode(data: ByteArray, out: IntArray, mode: Mode) {
        out.fill(0)
        Decoder(BitReader(data), mode).decodeInto(out)
    }

    private class Decoder(private val bits: BitReader, private val mode: Mode) {
        private var k = 1
        private var kp = 1 shl LSGR
        private var kr = 1
        private var krp = 1 shl LSGR

        fun decodeInto(out: IntArray) {
            var index = 0
            while (index < out.size && bits.remaining > 0) {
                index = (if (k != 0) runMode(out, index) else golombMode(out, index)) ?: return
            }
        }

        /**
         * Run-length mode. A prefix of zero bits counts how many times the run doubles — each one
         * also raises `k`, so a long run costs a few bits — then `k` more bits carry the remainder,
         * a sign bit and a Golomb-Rice magnitude describe the non-zero value that ended the run.
         */
        private fun runMode(out: IntArray, start: Int): Int? {
            val doublings = bits.countPrefix(0) ?: return null
            var run = 0
            repeat(doublings) {
                run += 1 shl k
                kp = minOf(kp + UP_GR, KPMAX)
                k = kp shr LSGR
            }
            if (bits.remaining < k + 1) return null
            run += bits.readBits(k)
            val negative = bits.readBit() == 1
            val code = golombRice() ?: return null
            kp = maxOf(kp - DN_GR, 0)
            k = kp shr LSGR

            // The zeroes of the run need no writing — the output starts out zero — but they do move
            // the cursor, and a run longer than what is left simply fills the rest.
            var index = start + minOf(run, out.size - start)
            if (index < out.size) out[index++] = if (negative) -(code + 1) else code + 1
            return index
        }

        /** Golomb-Rice mode: coefficients are coded directly until a zero brings run mode back. */
        private fun golombMode(out: IntArray, start: Int): Int? {
            val code = golombRice() ?: return null
            var index = start
            if (mode == Mode.Rlgr1) {
                kp = if (code == 0) minOf(kp + UQ_GR, KPMAX) else maxOf(kp - DQ_GR, 0)
                k = kp shr LSGR
                out[index++] = signed(code)
                return index
            }

            // RLGR3 codes a pair: the sum of the two magnitudes, then how it splits between them.
            val width = bitLength(code)
            if (bits.remaining < width) return null
            val first = bits.readBits(width)
            val second = code - first
            if (second < 0) throw RdpProtocolException("RLGR3 split of $first exceeds the coded sum $code")
            when {
                first != 0 && second != 0 -> kp = maxOf(kp - 2 * DQ_GR, 0)
                first == 0 && second == 0 -> kp = minOf(kp + 2 * UQ_GR, KPMAX)
            }
            k = kp shr LSGR
            out[index++] = signed(first)
            if (index < out.size) out[index++] = signed(second)
            return index
        }

        /**
         * One Golomb-Rice code word: a prefix of one bits, then [kr] remainder bits. The prefix
         * steers the adaptation too — but a prefix of exactly one leaves the parameter alone, which
         * is the detail that keeps encoder and decoder in step.
         */
        private fun golombRice(): Int? {
            val prefix = bits.countPrefix(1) ?: return null
            if (bits.remaining < kr) return null
            val code = (prefix shl kr) or bits.readBits(kr)
            when (prefix) {
                0 -> krp = maxOf(krp - 2, 0)
                1 -> Unit
                else -> krp = minOf(krp + prefix, KPMAX)
            }
            kr = krp shr LSGR
            return code
        }
    }

    /** Undo the sign folding: even magnitudes are positive, odd ones negative. */
    private fun signed(value: Int): Int = if (value and 1 == 0) value shr 1 else -((value + 1) shr 1)

    private fun bitLength(value: Int): Int {
        var bits = 0
        var rest = value
        while (rest > 0) {
            bits++
            rest = rest shr 1
        }
        return bits
    }
}

/**
 * MSB-first bit reader over a byte array. Reading past the end yields zeroes rather than throwing:
 * the codecs pad their last byte, so running out of bits is the normal way a plane ends — callers
 * watch [remaining] to tell padding from data.
 */
internal class BitReader(private val data: ByteArray, private val offset: Int = 0, length: Int = data.size - offset) {
    private var position = 0
    private val totalBits = length.coerceAtLeast(0) * 8

    /** Bits left to read. */
    val remaining: Int get() = totalBits - position

    fun readBit(): Int {
        if (position >= totalBits) return 0
        val bit = (data[offset + (position shr 3)].toInt() shr (7 - (position and 7))) and 1
        position++
        return bit
    }

    fun readBits(count: Int): Int {
        var value = 0
        repeat(count.coerceIn(0, 32)) { value = (value shl 1) or readBit() }
        return value
    }

    /**
     * Count how many [bit]s start here, consuming them and the value that terminates them. Null
     * means the stream ended without a terminator, which is how a padded final byte reads.
     *
     * The count is deliberately unbounded. A prefix here is not always a run length: in Golomb-Rice
     * mode it carries the high part of a coefficient's magnitude, so a large coefficient encoded
     * while the remainder width is small is legitimately hundreds of bits long. The stream itself is
     * the only honest bound, and running off its end is already handled.
     */
    fun countPrefix(bit: Int): Int? {
        var count = 0
        while (remaining > 0) {
            if (readBit() != bit) return count
            count++
        }
        return null
    }
}
