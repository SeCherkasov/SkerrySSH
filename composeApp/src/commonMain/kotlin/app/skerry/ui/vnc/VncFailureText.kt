package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import app.skerry.shared.io.findCause
import app.skerry.shared.rdp.RdpAuthException
import app.skerry.shared.rdp.RdpAuthFailure
import app.skerry.shared.rdp.RdpCertificateRejectedException
import app.skerry.shared.rdp.RdpConnectException
import app.skerry.shared.rdp.RdpNegotiationException
import app.skerry.shared.rdp.RdpNegotiationFailure
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpTlsException
import app.skerry.shared.vnc.VncAuthException
import app.skerry.shared.vnc.VncProtocolException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rdp_error_account_restricted
import app.skerry.ui.generated.resources.rdp_error_credentials
import app.skerry.ui.generated.resources.rdp_error_legacy_security
import app.skerry.ui.generated.resources.rdp_error_license
import app.skerry.ui.generated.resources.rdp_error_logon_not_allowed
import app.skerry.ui.generated.resources.rdp_error_negotiation
import app.skerry.ui.generated.resources.rdp_error_password_expired
import app.skerry.ui.generated.resources.rdp_error_protocol
import app.skerry.ui.generated.resources.rdp_error_security_check
import app.skerry.ui.generated.resources.rdp_error_server_refused
import app.skerry.ui.generated.resources.rdp_error_tls
import app.skerry.ui.generated.resources.vnc_connect_failed
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_error_auth
import app.skerry.ui.generated.resources.vnc_error_cert_rejected
import app.skerry.ui.generated.resources.vnc_error_protocol
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.remote.RemoteDesktopUiState
import org.jetbrains.compose.resources.stringResource

/**
 * Why a VNC or RDP connect failed, as a localization contract. Wire exceptions carry English
 * diagnostics ("truncated varint", "unsupported ZRLE subencoding 4") that are useless to a user and
 * untranslatable, so the reason travels typed and the text is resolved in composition.
 */
enum class VncFailure {
    /** Server demands an authentication scheme Skerry doesn't implement, or the secret was rejected. */
    Auth,

    /** The RFB stream was malformed or used an unsupported feature. */
    Protocol,

    /**
     * The server's certificate was never trusted: the user turned it down, left the question
     * unanswered, or a second connection recorded a different certificate for the host first.
     * Its own case because "failed to connect" is the one answer that tells the user nothing —
     * the whole point of asking them was that they could decide, and they need to see that they did.
     */
    CertificateRejected,

    /** RDP: wrong user name or password. */
    RdpCredentials,

    /** RDP: the account is disabled, locked out or expired. */
    RdpAccountRestricted,

    /** RDP: the password has expired and must be changed first. */
    RdpPasswordExpired,

    /** RDP: the account may not log on through Remote Desktop. */
    RdpLogonNotAllowed,

    /** RDP: the server could not license the session. */
    RdpLicense,

    /** RDP: the server ended the logon for a reason of its own. */
    RdpServerRefused,

    /** RDP: CredSSP could not bind the logon to the TLS channel — possibly an interception. */
    RdpSecurityCheck,

    /**
     * RDP: the server allows only Standard RDP Security, which Skerry does not speak. The fix is on
     * the server, and saying so is the difference between a dead end and a setting to change.
     */
    RdpLegacySecurity,

    /** RDP: the server refused every security protocol offered, for another reason. */
    RdpNegotiation,

    /** RDP: the TLS handshake failed — no shared version or cipher suite, or a dropped connection. */
    RdpTls,

    /** RDP: the stream was malformed or used an unsupported feature. */
    RdpProtocol,

    /** Anything else — transport drop, refused socket, timeout. */
    Other,
}

/**
 * Classifies a connect exception. The cause chain is walked as well as the exception itself: a
 * transport or a coroutine stack-trace copy can each add a layer, and a reason found one level too
 * deep would read as the generic "failed to connect" this exists to avoid.
 */
fun vncFailureOf(e: Throwable): VncFailure {
    e.findCause<RdpCertificateRejectedException>()?.let { return VncFailure.CertificateRejected }
    e.findCause<RdpAuthException>()?.let { return rdpAuthFailure(it.reason) }
    e.findCause<RdpNegotiationException>()?.let {
        return if (it.reason == RdpNegotiationFailure.SSL_NOT_ALLOWED_BY_SERVER) {
            VncFailure.RdpLegacySecurity
        } else {
            VncFailure.RdpNegotiation
        }
    }
    e.findCause<RdpTlsException>()?.let { return VncFailure.RdpTls }
    e.findCause<RdpProtocolException>()?.let { return VncFailure.RdpProtocol }
    e.findCause<VncAuthException>()?.let { return VncFailure.Auth }
    e.findCause<VncProtocolException>()?.let { return VncFailure.Protocol }
    return VncFailure.Other
}

/** The step an RDP connect failed at and how it ended, if the transport recorded one in [e]'s chain. */
fun rdpConnectFailureOf(e: Throwable): RdpConnectException? = e.findCause()

private fun rdpAuthFailure(reason: RdpAuthFailure): VncFailure = when (reason) {
    RdpAuthFailure.Credentials -> VncFailure.RdpCredentials
    RdpAuthFailure.AccountRestricted -> VncFailure.RdpAccountRestricted
    RdpAuthFailure.PasswordExpired -> VncFailure.RdpPasswordExpired
    RdpAuthFailure.LogonNotAllowed -> VncFailure.RdpLogonNotAllowed
    RdpAuthFailure.License -> VncFailure.RdpLicense
    RdpAuthFailure.ServerRefused -> VncFailure.RdpServerRefused
    RdpAuthFailure.SecurityCheck -> VncFailure.RdpSecurityCheck
}

/**
 * What a screen reader should hear when a remote-desktop session changes state on its own, or the
 * empty string for the states worth no announcement.
 *
 * A failed connect and a dropped session replace the picture with a line of text: visible to a
 * sighted user, silent to everyone else (WCAG 4.1.3). Connecting and Connected say nothing — the
 * first follows a keystroke the user just made, and the second is the desktop itself.
 *
 * Pass it to a [app.skerry.ui.design.StatusAnnouncer] composed *above* the `when` that picks the
 * surface, or the node carrying the message appears with it and is an insertion rather than a change.
 */
@Composable
fun remoteDesktopAnnouncement(ui: RemoteDesktopUiState): String = when (ui) {
    is RemoteDesktopUiState.Error -> remoteDesktopErrorText(ui)
    is RemoteDesktopUiState.Disconnected ->
        stringResource(if (ui.cleanExit) Res.string.vnc_session_closed else Res.string.vnc_connection_lost)
    else -> ""
}

/** User-facing text for [failure]. */
@Composable
fun vncFailureText(failure: VncFailure): String = when (failure) {
    VncFailure.Auth -> stringResource(Res.string.vnc_error_auth)
    VncFailure.Protocol -> stringResource(Res.string.vnc_error_protocol)
    VncFailure.CertificateRejected -> stringResource(Res.string.vnc_error_cert_rejected)
    VncFailure.RdpCredentials -> stringResource(Res.string.rdp_error_credentials)
    VncFailure.RdpAccountRestricted -> stringResource(Res.string.rdp_error_account_restricted)
    VncFailure.RdpPasswordExpired -> stringResource(Res.string.rdp_error_password_expired)
    VncFailure.RdpLogonNotAllowed -> stringResource(Res.string.rdp_error_logon_not_allowed)
    VncFailure.RdpLicense -> stringResource(Res.string.rdp_error_license)
    VncFailure.RdpServerRefused -> stringResource(Res.string.rdp_error_server_refused)
    VncFailure.RdpSecurityCheck -> stringResource(Res.string.rdp_error_security_check)
    VncFailure.RdpLegacySecurity -> stringResource(Res.string.rdp_error_legacy_security)
    VncFailure.RdpNegotiation -> stringResource(Res.string.rdp_error_negotiation)
    VncFailure.RdpTls -> stringResource(Res.string.rdp_error_tls)
    VncFailure.RdpProtocol -> stringResource(Res.string.rdp_error_protocol)
    VncFailure.Other -> stringResource(Res.string.vnc_connect_failed)
}
