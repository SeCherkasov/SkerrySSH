package app.skerry.shared.vnc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VncDesCipherTest {

    @Test
    fun reverse_bits_mirrors_the_byte() {
        assertEquals(0x80.toByte(), VncDesCipher.reverseBits(0x01))
        assertEquals(0x01.toByte(), VncDesCipher.reverseBits(0x80.toByte()))
        assertEquals(0xF0.toByte(), VncDesCipher.reverseBits(0x0F))
        assertEquals(0xFF.toByte(), VncDesCipher.reverseBits(0xFF.toByte()))
        assertEquals(0x00.toByte(), VncDesCipher.reverseBits(0x00))
        assertEquals(0xA5.toByte(), VncDesCipher.reverseBits(0xA5.toByte())) // palindrome bit pattern
    }

    @Test
    fun response_is_two_des_blocks() {
        val response = VncDesCipher.respond("secret", ByteArray(16) { it.toByte() })
        assertEquals(16, response.size)
    }

    @Test
    fun response_is_deterministic() {
        val challenge = ByteArray(16) { (it * 7).toByte() }
        assertContentEquals(
            VncDesCipher.respond("hunter2", challenge),
            VncDesCipher.respond("hunter2", challenge),
        )
    }

    @Test
    fun password_is_truncated_to_eight_bytes() {
        val challenge = ByteArray(16) { it.toByte() }
        // VNC keys use only the first 8 password bytes: extra characters must not change the result.
        assertContentEquals(
            VncDesCipher.respond("12345678", challenge),
            VncDesCipher.respond("12345678ABCDEF", challenge),
        )
    }

    @Test
    fun different_passwords_yield_different_responses() {
        val challenge = ByteArray(16) { it.toByte() }
        val a = VncDesCipher.respond("alpha", challenge)
        val b = VncDesCipher.respond("bravo", challenge)
        assertFalse(a.contentEquals(b))
    }
}
