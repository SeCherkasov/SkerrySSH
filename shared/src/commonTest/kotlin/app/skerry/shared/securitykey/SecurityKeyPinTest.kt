package app.skerry.shared.securitykey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityKeyPinTest {
    @Test fun pinBoundaryIsUtf8BytesAndRejectsNulAndMalformedSurrogates() {
        assertTrue(isSecurityKeyPinValid("1234".toCharArray()))
        assertTrue(isSecurityKeyPinValid("x".repeat(63).toCharArray()))
        assertFalse(isSecurityKeyPinValid("x".repeat(64).toCharArray()))
        assertTrue(isSecurityKeyPinValid("я".repeat(31).toCharArray()))
        assertFalse(isSecurityKeyPinValid("я".repeat(32).toCharArray()))
        assertFalse(isSecurityKeyPinValid(charArrayOf('\u0000')))
        assertFalse(isSecurityKeyPinValid(charArrayOf('\ud800')))
        assertFalse(isSecurityKeyPinValid(charArrayOf('\udc00')))
    }
}
