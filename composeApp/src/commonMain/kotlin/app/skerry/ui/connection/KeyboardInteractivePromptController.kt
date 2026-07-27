package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractiveResponder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * State is a [StateFlow] rather than Compose state because the transport calls in from its own
 * thread (sshj's reader), and a flow needs no confinement of its own.
 *
 * Challenges are serialized by a mutex: two hosts asking for a code at the same moment queue instead
 * of overwriting each other's prompt — a single dialog can only be answered for one of them, and the
 * loser would otherwise wait for an answer that can never arrive.
 *
 * The transport applies its own timeout, so a prompt nobody answers eventually resolves as dismissed
 * on that side; the controller itself waits indefinitely.
 */
@Stable
class KeyboardInteractivePromptController {

    private val _pending = MutableStateFlow<KeyboardInteractiveRequest?>(null)

    /** The challenge to show, or null when nothing is being asked. */
    val pending: StateFlow<KeyboardInteractiveRequest?> = _pending.asStateFlow()

    private val turnstile = Mutex()
    private var waiter: CompletableDeferred<List<String>?>? = null
    private var nextId: Long = 0

    /** Hand this to the transport (see `SshjTransport`'s `keyboardInteractiveResponder`). */
    val responder: KeyboardInteractiveResponder = KeyboardInteractiveResponder { challenge ->
        ask(challenge)
    }

    private suspend fun ask(challenge: KeyboardInteractiveChallenge): List<String>? = turnstile.withLock {
        val answer = CompletableDeferred<List<String>?>()
        val request = KeyboardInteractiveRequest(id = nextId++, challenge = challenge)
        waiter = answer
        _pending.value = request
        try {
            answer.await()
        } finally {
            waiter = null
            // Only clear a prompt that is still ours: with the mutex held it always is, but the
            // check keeps that assumption from silently breaking if the guard ever changes.
            _pending.compareAndSet(request, null)
        }
    }

    /**
     * Answers the pending challenge, in prompt order. [requestId] guards against a late submit from
     * a dialog that has since been replaced; the default answers whatever is showing now.
     */
    fun submit(answers: List<String>, requestId: Long? = _pending.value?.id) {
        if (requestId == null || requestId != _pending.value?.id) return
        waiter?.complete(answers)
    }

    /** Dismisses the pending challenge, which aborts authentication for that connection. */
    fun dismiss(requestId: Long? = _pending.value?.id) {
        if (requestId == null || requestId != _pending.value?.id) return
        waiter?.complete(null)
    }
}
