package app.skerry.shared.securitykey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecurityKeyWireTest {
    @Test fun stubRoundTripKeepsApplicationHandleFlagsAndBothPublicAlgorithms() {
        for (algorithm in SecurityKeyAlgorithm.entries) {
            val public = if (algorithm == SecurityKeyAlgorithm.ED25519) ByteArray(32) { 1 } else byteArrayOf(4) + ByteArray(64) { 1 }
            val options = SecurityKeyGenerationOptions(algorithm, "ssh:example", resident = true, userVerification = true)
            val stub = encodeSecurityKey(options, public, byteArrayOf(9, 8, 7))
            val parsed = decodeUnencryptedSecurityKey(stub.privateKeyPem)
            assertEquals(options.application, parsed.application)
            assertEquals(0x25, parsed.flags)
            kotlin.test.assertContentEquals(public, parsed.publicKey)
            kotlin.test.assertContentEquals(byteArrayOf(9, 8, 7), parsed.keyHandle)
        }
    }
    @Test fun signatureEncodingMatchesOpenSshProtocolVector() {
        val request = SecurityKeySignRequest(SecurityKeyAlgorithm.ED25519, "ssh:", byteArrayOf(1), ByteArray(32) { 0xab.toByte() }, 1)
        val bytes = securityKeySignedData(request, SecurityKeyAssertion(ByteArray(64), 1, 0x01020304))
        assertEquals("e30610e8a162115960fe1ec223e6529c9f4b6e80200dcb5e5c321c8af1e2b1bf0101020304" + "ab".repeat(32),
            bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }
    @Test fun refusesMalformedAndOversizedWireBeforeAllocation() {
        assertFailsWith<IllegalArgumentException> { decodeUnencryptedSecurityKey("not pem") }
        assertFailsWith<IllegalArgumentException> { decodeUnencryptedSecurityKey("x".repeat(262145)) }
        assertFailsWith<IllegalArgumentException> { encodeSecurityKey(SecurityKeyGenerationOptions(), ByteArray(31), byteArrayOf(1)) }
    }
}
