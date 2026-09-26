package app.skerry.shared.terminal

/**
 * Numeric parameters of one CSI sequence, collected byte by byte as the parser meets them. A
 * colourful stream is mostly SGR, and building a String per sequence, splitting it and boxing every
 * number was a tenth of what parsing cost.
 *
 * A parameter is a `;`-separated field; `:`-subparameters (`38:2::r:g:b`, `4:3`) follow their
 * parameter as fields of their own, flagged by [isSub]. An empty field — or one spoiled by a stray
 * marker byte — reads as [OMITTED], which every caller treats as "use the default". A leading
 * `?`/`<`/`=`/`>` is the private [marker], not part of the first field.
 *
 * Bounded against an untrusted server: values saturate at [MAX_VALUE] (so `cy + n` cannot overflow),
 * and fields past [MAX_PARAMS] are dropped while parsing continues to the final byte.
 */
internal class CsiParams {
    private val values = IntArray(MAX_PARAMS)
    private val sub = BooleanArray(MAX_PARAMS)

    /** Number of fields, subparameters included; 0 when the sequence carried no parameter bytes. */
    var size = 0
        private set

    /** Private marker (`?`, `<`, `=`, `>`) or [NO_MARKER]. */
    var marker = NO_MARKER
        private set

    private var current = OMITTED
    private var currentSub = false
    private var spoiled = false
    private var seenAny = false

    fun reset() {
        size = 0
        marker = NO_MARKER
        current = OMITTED
        currentSub = false
        spoiled = false
        seenAny = false
    }

    /** One parameter byte, 0x30..0x3f: a digit, `:`, `;`, or a marker. */
    fun accept(b: Int) {
        val first = !seenAny
        seenAny = true
        when (b) {
            in DIGIT_0..DIGIT_9 -> {
                val d = b - DIGIT_0
                current = if (current == OMITTED) d else (current * 10 + d).coerceAtMost(MAX_VALUE)
            }
            SEMICOLON -> { push(); currentSub = false }
            COLON -> { push(); currentSub = true }
            else -> if (first) marker = b.toChar() else spoiled = true
        }
    }

    /** Closes the last field; call once, at the final byte. */
    fun finish() {
        if (seenAny && !markerOnly()) push()
    }

    /** `CSI ? h`-style: a marker and nothing after it — no field to close. */
    private fun markerOnly(): Boolean = size == 0 && marker != NO_MARKER && current == OMITTED && !spoiled

    private fun push() {
        if (size < MAX_PARAMS) {
            values[size] = if (spoiled) OMITTED else current
            sub[size] = currentSub
            size++
        }
        current = OMITTED
        spoiled = false
    }

    /** Field [i]: its value, [OMITTED] when empty, or [absent] when the sequence has no such field. */
    fun at(i: Int, absent: Int): Int = if (i < size) values[i] else absent

    /** Field [i] as a count or coordinate: [default] when absent, omitted or zero. */
    fun count(i: Int, default: Int): Int = at(i, default).let { if (it > 0) it else default }

    /** Field [i] as a mode selector: [default] when absent or omitted; zero is a real selector. */
    fun mode(i: Int, default: Int): Int = at(i, default).let { if (it >= 0) it else default }

    /** Whether field [i] is a `:`-subparameter of the field before it. */
    fun isSub(i: Int): Boolean = i < size && sub[i]

    /** Whether any field equals [value]. */
    fun contains(value: Int): Boolean = (0 until size).any { values[it] == value }

    companion object {
        const val OMITTED = -1
        const val NO_MARKER = 0.toChar()

        /** Largest numeric parameter; xterm clamps at the same value. */
        const val MAX_VALUE = 65535

        /** Fields kept per sequence — xterm keeps 30; the rest are parsed and dropped. */
        const val MAX_PARAMS = 64

        private const val DIGIT_0 = 0x30
        private const val DIGIT_9 = 0x39
        private const val COLON = 0x3a
        private const val SEMICOLON = 0x3b

        /** Parameters of [raw] as the parser would collect them — for tests and callers holding text. */
        fun of(raw: String): CsiParams = CsiParams().apply {
            raw.forEach { accept(it.code) }
            finish()
        }
    }
}
