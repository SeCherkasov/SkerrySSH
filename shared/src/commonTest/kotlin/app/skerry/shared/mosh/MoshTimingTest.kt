package app.skerry.shared.mosh

import kotlin.test.Test
import kotlin.test.assertEquals

class MoshTimingTest {

    @Test
    fun `timestamps are lower 16 bits of milliseconds`() {
        assertEquals(0, timestamp16(0L))
        assertEquals(0xFFFF, timestamp16(65535L))
        assertEquals(1, timestamp16(65537L))
    }

    @Test
    fun `rtt sample handles wrap-around of the 16-bit clock`() {
        assertEquals(10, rttSample(now16 = 5, echoed16 = 0xFFFB))
        assertEquals(100, rttSample(now16 = 0x0164, echoed16 = 0x0100))
    }

    @Test
    fun `first sample initializes srtt directly`() {
        val rtt = MoshRtt()
        rtt.onSample(200)
        assertEquals(200.0, rtt.srtt)
        assertEquals(100.0, rtt.rttvar)
    }

    @Test
    fun `subsequent samples are smoothed like tcp`() {
        val rtt = MoshRtt()
        rtt.onSample(100)
        rtt.onSample(200)
        // RTTVAR = 3/4*50 + 1/4*|100-200| = 62.5 ; SRTT = 7/8*100 + 1/8*200 = 112.5
        assertEquals(112.5, rtt.srtt)
        assertEquals(62.5, rtt.rttvar)
    }

    @Test
    fun `rto is clamped between 50 and 1000 ms`() {
        val fast = MoshRtt()
        fast.onSample(1)
        assertEquals(50L, fast.rto())
        val slow = MoshRtt()
        slow.onSample(5000)
        assertEquals(1000L, slow.rto())
    }
}
