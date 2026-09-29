package app.skerry.ui.vnc

import app.skerry.shared.rdp.RdpAuthException
import app.skerry.shared.rdp.RdpAuthFailure
import app.skerry.shared.rdp.RdpNegotiationException
import app.skerry.shared.rdp.RdpNegotiationFailure
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpTlsException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #395: every RDP connect failure used to read "Failed to connect", so a wrong password and a
 * server that only speaks legacy RDP security looked the same.
 */
class RdpFailureClassificationTest {

    @Test
    fun `each refused logon keeps its own reason`() {
        val expected = mapOf(
            RdpAuthFailure.Credentials to VncFailure.RdpCredentials,
            RdpAuthFailure.AccountRestricted to VncFailure.RdpAccountRestricted,
            RdpAuthFailure.PasswordExpired to VncFailure.RdpPasswordExpired,
            RdpAuthFailure.LogonNotAllowed to VncFailure.RdpLogonNotAllowed,
            RdpAuthFailure.License to VncFailure.RdpLicense,
            RdpAuthFailure.ServerRefused to VncFailure.RdpServerRefused,
            RdpAuthFailure.SecurityCheck to VncFailure.RdpSecurityCheck,
        )
        assertEquals(RdpAuthFailure.entries.toSet(), expected.keys, "a reason without a sentence")
        for ((reason, failure) in expected) {
            assertEquals(failure, vncFailureOf(RdpAuthException(reason, "wire text")), reason.name)
        }
    }

    @Test
    fun `a server limited to legacy rdp security is named as such`() {
        val refused = RdpNegotiationException(RdpNegotiationFailure.SSL_NOT_ALLOWED_BY_SERVER, "wire text")

        assertEquals(VncFailure.RdpLegacySecurity, vncFailureOf(refused))
    }

    @Test
    fun `any other negotiation refusal is a negotiation failure`() {
        assertEquals(
            VncFailure.RdpNegotiation,
            vncFailureOf(RdpNegotiationException(RdpNegotiationFailure.SSL_CERT_NOT_ON_SERVER, "wire text")),
        )
        assertEquals(VncFailure.RdpNegotiation, vncFailureOf(RdpNegotiationException(null, "wire text")))
    }

    @Test
    fun `tls and protocol failures are told apart from a generic connect failure`() {
        assertEquals(VncFailure.RdpTls, vncFailureOf(RdpTlsException("handshake", IllegalStateException())))
        assertEquals(VncFailure.RdpProtocol, vncFailureOf(RdpProtocolException("truncated")))
    }

    @Test
    fun `a reason wrapped once by the transport is still found`() {
        val wrapped = IllegalStateException("wrapped", RdpAuthException(RdpAuthFailure.Credentials, "wire text"))

        assertEquals(VncFailure.RdpCredentials, vncFailureOf(wrapped))
    }

    @Test
    fun `a reason wrapped twice is still found`() {
        val tls = RdpTlsException("handshake", IllegalStateException())
        val wrapped = IllegalStateException("outer", IllegalStateException("inner", tls))

        assertEquals(VncFailure.RdpTls, vncFailureOf(wrapped))
    }
}
