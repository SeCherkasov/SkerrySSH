package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import app.skerry.shared.rdp.RdpCertificateRejectedException
import app.skerry.shared.vnc.VncAuthException
import app.skerry.shared.vnc.VncProtocolException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vnc_connect_failed
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_error_auth
import app.skerry.ui.generated.resources.vnc_error_cert_rejected
import app.skerry.ui.generated.resources.vnc_error_protocol
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.remote.RemoteDesktopUiState
import org.jetbrains.compose.resources.stringResource

/**
 * Why a VNC connect failed, as a localization contract. RFB wire exceptions carry English
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

    /** Anything else — transport drop, refused socket, timeout. */
    Other,
}

/**
 * Classifies a connect exception. The immediate cause is checked as well as the exception itself,
 * because the transport wraps once on its way out — one level, not a walk: the connectors that bury
 * a reason deeper unwrap it themselves (`SshjTransport.certificateCheckFailed`).
 */
fun vncFailureOf(e: Throwable): VncFailure = when {
    e is RdpCertificateRejectedException || e.cause is RdpCertificateRejectedException ->
        VncFailure.CertificateRejected
    e is VncAuthException || e.cause is VncAuthException -> VncFailure.Auth
    e is VncProtocolException || e.cause is VncProtocolException -> VncFailure.Protocol
    else -> VncFailure.Other
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
    is RemoteDesktopUiState.Error -> vncFailureText(ui.failure)
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
    VncFailure.Other -> stringResource(Res.string.vnc_connect_failed)
}
