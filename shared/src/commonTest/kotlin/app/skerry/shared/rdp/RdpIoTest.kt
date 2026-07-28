package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The reader every RDP parser is built on. Its bounds check answers for all of them, so it has to
 * hold for a length the server picked to break it, not only for an honestly oversized one.
 */
class RdpIoTest {

    @Test
    fun `a read long enough to wrap the position is refused`() {
        val reader = RdpReader(ByteArray(8))
        reader.skip(1)

        assertFailsWith<RdpProtocolException> { reader.bytes(Int.MAX_VALUE) }
    }

    @Test
    fun `a slice long enough to wrap the position is refused`() {
        val reader = RdpReader(ByteArray(8))
        reader.skip(1)

        assertFailsWith<RdpProtocolException> { reader.slice(Int.MAX_VALUE) }
    }
}
