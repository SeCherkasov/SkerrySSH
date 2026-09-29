package app.skerry.shared.rdp

import app.skerry.shared.io.causeChain
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [e] as the failure of [stage]: wrapped in the [RdpConnectException] that names the step, or
 * passed through as it is when there is nothing to name. A redirection is not a failure and a
 * cancellation is not ours to relabel; a `null` stage is a failure between the named steps.
 */
internal fun rdpConnectFailure(stage: RdpConnectStage?, e: Exception): Exception = when {
    stage == null || e is CancellationException || e is RdpRedirectException -> e
    else -> RdpConnectException(stage, rdpDropOf(e), e)
}

/**
 * How the network ended a connect attempt, read off the JVM exception somewhere in [e]'s cause
 * chain; `null` when it was not the network, or when the server named its own reason — a refused
 * certificate or logon is that reason, whatever the socket did afterwards. The chain is walked
 * because JSSE reports a peer that hung up mid-handshake as a handshake failure, sometimes with the
 * EOF underneath and sometimes with only its message to go on.
 */
internal fun rdpDropOf(e: Throwable): RdpDrop? {
    val chain = e.causeChain().toList()
    if (chain.any { it is RdpCertificateRejectedException || it is RdpAuthException || it is RdpNegotiationException }) {
        return null
    }
    return chain.firstNotNullOfOrNull(::dropOf)
}

private fun dropOf(cause: Throwable): RdpDrop? {
    val message = cause.message.orEmpty()
    return when (cause) {
        is EOFException -> RdpDrop.Closed
        is SocketTimeoutException -> RdpDrop.Timeout
        is UnknownHostException -> RdpDrop.Unresolved
        // The connector dials with a timeout, so a dropped SYN arrives as a SocketTimeoutException and
        // a missing route as NoRouteToHostException (a plain SocketException); what is left here is
        // the refusal. Its message comes from strerror and is localised, so it is not read.
        is ConnectException -> RdpDrop.Refused
        is SSLHandshakeException -> RdpDrop.Closed.takeIf { message.contains("terminated the handshake", ignoreCase = true) }
        // "Socket closed" is our own cancellation; only a reset is the peer's doing. Both messages,
        // and the handshake one above, are the JDK's own English literals, not the OS's.
        is SocketException -> RdpDrop.Reset.takeIf { message.contains("reset", ignoreCase = true) }
        else -> null
    }
}
