package app.skerry.shared.mosh

/**
 * mosh datagram timestamps are the lower 16 bits of a millisecond clock; RTT is measured by
 * echoing them back (`timestamp_reply`). 0xFFFF is the "no timestamp" sentinel on the wire.
 */
const val MOSH_TS_NONE: Int = 0xFFFF

fun timestamp16(nowMs: Long): Int = (nowMs and 0xFFFF).toInt()

/** RTT sample from an echoed timestamp, tolerant of 16-bit wrap-around. */
fun rttSample(now16: Int, echoed16: Int): Int = (now16 - echoed16) and 0xFFFF

/**
 * Smoothed RTT estimator, mirroring mosh's `Connection` (which mirrors TCP's RFC 6298):
 * SRTT/RTTVAR with 1/8 and 1/4 gains, RTO clamped to [50, 1000] ms.
 */
class MoshRtt {
    // Volatile: written by the datagram loop, read by the status-bar poller.
    @kotlin.concurrent.Volatile
    var srtt: Double = 1000.0
        private set

    @kotlin.concurrent.Volatile
    var rttvar: Double = 500.0
        private set

    @kotlin.concurrent.Volatile
    private var hit = false

    fun onSample(rttMs: Int) {
        val r = rttMs.toDouble()
        if (!hit) {
            srtt = r
            rttvar = r / 2
            hit = true
        } else {
            rttvar = 0.75 * rttvar + 0.25 * kotlin.math.abs(srtt - r)
            srtt = 0.875 * srtt + 0.125 * r
        }
    }

    /** Latest smoothed RTT in ms, or null before the first sample (for the status bar). */
    fun currentRttMs(): Long? = if (hit) srtt.toLong() else null

    fun rto(): Long = (srtt + 4 * rttvar).toLong().coerceIn(50L, 1000L)
}
