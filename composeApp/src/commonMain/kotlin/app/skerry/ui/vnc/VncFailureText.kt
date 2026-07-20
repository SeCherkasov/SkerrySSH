package app.skerry.ui.vnc

import androidx.compose.runtime.Composable
import app.skerry.shared.vnc.VncAuthException
import app.skerry.shared.vnc.VncProtocolException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vnc_connect_failed
import app.skerry.ui.generated.resources.vnc_error_auth
import app.skerry.ui.generated.resources.vnc_error_protocol
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

    /** Anything else — transport drop, refused socket, timeout. */
    Other,
}

/** Classifies a connect exception; the cause chain is checked because the transport wraps. */
fun vncFailureOf(e: Throwable): VncFailure = when {
    e is VncAuthException || e.cause is VncAuthException -> VncFailure.Auth
    e is VncProtocolException || e.cause is VncProtocolException -> VncFailure.Protocol
    else -> VncFailure.Other
}

/** User-facing text for [failure]. */
@Composable
fun vncFailureText(failure: VncFailure): String = when (failure) {
    VncFailure.Auth -> stringResource(Res.string.vnc_error_auth)
    VncFailure.Protocol -> stringResource(Res.string.vnc_error_protocol)
    VncFailure.Other -> stringResource(Res.string.vnc_connect_failed)
}
