package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantTimeTest {

    @Test
    fun `equal arrays compare equal`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `difference in any position is detected`() {
        assertFalse(constantTimeEquals(byteArrayOf(9, 2, 3), byteArrayOf(1, 2, 3)), "первый байт")
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 9), byteArrayOf(1, 2, 3)), "последний байт")
        // Различие только в знаковом бите — ловушка наивного сложения вместо XOR/or.
        assertFalse(constantTimeEquals(byteArrayOf(0x7f), byteArrayOf(-0x80)))
    }

    @Test
    fun `different lengths compare not equal`() {
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
