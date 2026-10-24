package app.skerry.ui.securitykey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityKeyPinTest {
    @Test fun pinHasBoundedValidUtf8AndNoNul() {
        assertTrue(securityKeyPinValid("1234"))
        assertTrue(securityKeyPinValid("a".repeat(63)))
        assertFalse(securityKeyPinValid("a".repeat(64)))
        assertFalse(securityKeyPinValid("я".repeat(32)))
        assertFalse(securityKeyPinValid("\uD800"))
        assertFalse(securityKeyPinValid("12\u000034"))
    }
}
