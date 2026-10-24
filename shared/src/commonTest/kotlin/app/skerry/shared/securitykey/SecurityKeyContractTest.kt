package app.skerry.shared.securitykey

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class SecurityKeyContractTest {
    @Test fun rejectsForeignApplicationsAndUnboundedInputs() {
        assertFailsWith<IllegalArgumentException> { SecurityKeyGenerationOptions(application = "https://example.com") }
        assertFailsWith<IllegalArgumentException> { SecurityKeyGenerationOptions(application = "ssh:" + "x".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { SecurityKeyGenerationOptions(comment = "bad\nline") }
    }
    @Test fun malformedAssertionsCannotSatisfyPresenceOrVerification() {
        val request = SecurityKeySignRequest(SecurityKeyAlgorithm.ED25519, "ssh:", byteArrayOf(1), ByteArray(32), 5)
        assertFailsWith<SecurityKeyException> { validateAssertion(request, SecurityKeyAssertion(ByteArray(64), 1, 0)) }
        assertFailsWith<SecurityKeyException> { validateAssertion(request, SecurityKeyAssertion(ByteArray(63), 5, 0)) }
        assertFailsWith<SecurityKeyException> { validateAssertion(request, SecurityKeyAssertion(ByteArray(64), 5, -1)) }
    }
    @Test fun safeDefaultsRequirePresence() {
        assertEquals(1, SecurityKeyGenerationOptions().flags)
        assertEquals(5, SecurityKeyGenerationOptions(userVerification = true).flags)
    }
}
