package app.skerry.ui.securitykey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SecurityKeyGenerationFormTest {
    @Test fun applicationUsesSshNamespaceAndRejectsControls() {
        assertTrue(SecurityKeyGenerationForm().valid)
        assertTrue(SecurityKeyGenerationForm(application = "ssh:production").valid)
        assertFalse(SecurityKeyGenerationForm(application = "https:example").valid)
        assertFalse(SecurityKeyGenerationForm(application = "ssh:\n").valid)
        assertFalse(SecurityKeyGenerationForm(application = "ssh:" + "a".repeat(252)).valid)
    }
    @Test fun optionsRetainExplicitHardwarePolicy() {
        val options = SecurityKeyGenerationForm(resident = true, userVerification = true, username = "user").options("label")
        assertTrue(options.resident)
        assertTrue(options.userVerification)
        assertEquals("user", options.username)
        assertEquals("label", options.comment)
    }
}
