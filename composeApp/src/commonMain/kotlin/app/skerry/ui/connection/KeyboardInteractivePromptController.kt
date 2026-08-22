package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import app.skerry.shared.ssh.KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractiveResponder
import app.skerry.ui.design.PromptQueue
import kotlinx.coroutines.flow.StateFlow

/**
 * A challenge waiting for the user. [id] identifies this exact request so a late answer from a
 * dialog that has already been replaced can be dropped rather than applied to the next connection's
 * prompt.
 */
data class KeyboardInteractiveRequest(
    val id: Long,
    val challenge: KeyboardInteractiveChallenge,
)

/**
 * Bridges the transport's [KeyboardInteractiveResponder] to a prompt in the UI: the transport asks,
 * this controller publishes the challenge as [pending], and the connection waits until the user
 * submits or dismisses it.
 *
 * The queueing, the deadline and the id-guarded answer are [PromptQueue]'s — the same machinery
 * behind [app.skerry.ui.trust.HostTrustPromptController], which asks a host-key question from the
 * same transport threads.
 */
@Stable
class KeyboardInteractivePromptController {

    private val queue = PromptQueue<KeyboardInteractiveRequest, List<String>?>(
        timeoutMillis = KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS,
        idOf = { it.id },
    )

    /** The challenge to show, or null when nothing is being asked. */
    val pending: StateFlow<KeyboardInteractiveRequest?> = queue.pending

    /** Hand this to the transport (see `SshjTransport`'s `keyboardInteractiveResponder`). */
    val responder: KeyboardInteractiveResponder = KeyboardInteractiveResponder { challenge ->
        queue.ask(
            build = { KeyboardInteractiveRequest(id = it, challenge = challenge) },
            onTimeout = null,
        )
    }

    /**
     * Answers the pending challenge, in prompt order. [requestId] guards against a late submit from
     * a dialog that has since been replaced; the default answers whatever is showing now.
     */
    fun submit(answers: List<String>, requestId: Long? = pending.value?.id) =
        queue.answer(requestId, answers)

    /** Dismisses the pending challenge, which aborts authentication for that connection. */
    fun dismiss(requestId: Long? = pending.value?.id) = queue.answer(requestId, null)

    /**
     * Drops whatever is being asked right now, aborting that connection's authentication.
     *
     * Called when the vault locks: the prompt lives inside the unlocked chrome, so a lock takes it
     * off screen while the connection would keep waiting behind the lock screen for a code nobody
     * can see. Failing the attempt outright is the same call the runbook runner makes when the vault
     * locks mid-run — the user comes back to a plain failure instead of a connection that hung for
     * two minutes on an invisible question.
     */
    fun cancelPending() = queue.cancelPending(null)
}
