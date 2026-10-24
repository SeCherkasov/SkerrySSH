package app.skerry.shared.securitykey

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AuthenticatorCborValidationTest {
    private val allocationBomb = byteArrayOf(0, 0xa1.toByte(), 1, 0x5a, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
    @Test fun rejectsDeclaredTwoGiBBytesBeforeSdkAllocation() {
        assertFailsWith<SecurityKeyException> { validateAuthenticatorCbor(allocationBomb, 4) }
    }
    @Test fun rejectsExcessiveCollectionsDepthAndTruncation() {
        val bodies = listOf(
            byteArrayOf(0xa1.toByte(), 1, 0x9a.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            ByteArray(40) { 0x81.toByte() } + byteArrayOf(0),
            byteArrayOf(0xa1.toByte(), 1, 0x58, 0x20, 1),
            byteArrayOf(0xa1.toByte(), 1, 0x7f, 0xff.toByte()),
        )
        for (body in bodies) assertFailsWith<SecurityKeyException> { validateAuthenticatorCbor(byteArrayOf(0) + body, 4) }
    }
    @Test fun rejectsEmbeddedCoseAndExtensionAllocationBombs() {
        val malformed = byteArrayOf(0xa1.toByte(), 1, 0x5a, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val attested = ByteArray(55).apply { this[32] = 0x40; this[54] = 1 } + byteArrayOf(9) + malformed
        val extended = ByteArray(37).apply { this[32] = 0x80.toByte() } + malformed
        assertFailsWith<SecurityKeyException> { validateAuthenticatorCbor(withAuthData(attested), 1) }
        assertFailsWith<SecurityKeyException> { validateAuthenticatorCbor(withAuthData(extended), 2) }
    }
    @Test fun permitsBoundedGetInfoAndAuthenticatorData() {
        validateAuthenticatorCbor(byteArrayOf(0, 0xa1.toByte(), 1, 0x81.toByte(), 0x68) + "FIDO_2_0".encodeToByteArray(), 4)
        validateAuthenticatorCbor(withAuthData(ByteArray(37).apply { this[32] = 1 }), 2)
        val attested = ByteArray(55).apply { this[32] = 0x41; this[54] = 1 } + byteArrayOf(9, 0xa1.toByte(), 1, 2)
        validateAuthenticatorCbor(withAuthData(attested), 1)
        validateAuthenticatorCbor(byteArrayOf(0x31), 2) // CTAP error, no CBOR body.
    }
    private fun withAuthData(authData: ByteArray): ByteArray =
        byteArrayOf(0, 0xa1.toByte(), 2, 0x58, authData.size.toByte()) + authData
}
