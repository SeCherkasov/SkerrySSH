package app.skerry.ui.connection

import app.skerry.shared.securitykey.SecurityKeyException
import app.skerry.shared.securitykey.SecurityKeyFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecurityKeyFailureReasonTest {
    @Test
    fun hardwareFailureSurvivesTransportWrapping() {
        val error = IllegalStateException("Transport failed", SecurityKeyException(SecurityKeyFailure.Detached))
        assertEquals(SecurityKeyFailure.Detached, securityKeyFailureReason(error))
    }

    @Test
    fun ordinaryAuthenticationFailureIsNotHardwareFailure() {
        assertNull(securityKeyFailureReason(IllegalArgumentException("Authentication failed")))
    }
}
