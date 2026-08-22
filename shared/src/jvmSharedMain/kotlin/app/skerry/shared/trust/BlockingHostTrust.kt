package app.skerry.shared.trust

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapts a [HostTrustPrompt] to the [HostTrustDecider] the verifiers can call: they run inside a
 * handshake on the transport's own thread, which is blocked until the user answers. The same shape
 * as the keyboard-interactive bridge in `SshjTransport`, and for the same reason.
 *
 * Nothing but an explicit "yes" trusts the key. An unanswered question refuses — [timeoutMillis] is
 * the backstop for a UI that dropped it entirely; the deadline the user experiences belongs to the
 * prompt, which starts counting when the dialog is actually on screen.
 *
 * A prompt that *throws* is not turned into a refusal, though the connection fails either way: a
 * broken dialog and a person saying no are different events, and collapsing them here would leave
 * the failure claiming the user turned the key down. The exception travels out to the connection
 * instead, where it is reported with its own cause. This client keeps connection metadata out of
 * logs, so the failure the user is shown is the only place it can surface at all.
 *
 * An interrupt is the one exception that is answered here: it means the transport is being torn down
 * and wants its reader to stop, so the flag [runBlocking] cleared is set again and the refusal is
 * returned to the handshake that is already on its way out.
 */
fun HostTrustPrompt.asDecider(timeoutMillis: Long = HOST_TRUST_BACKSTOP_MILLIS): HostTrustDecider =
    HostTrustDecider { request ->
        try {
            runBlocking { withTimeoutOrNull(timeoutMillis) { confirm(request) } } ?: false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
