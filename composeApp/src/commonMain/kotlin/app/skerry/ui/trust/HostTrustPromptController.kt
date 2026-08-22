package app.skerry.ui.trust

import androidx.compose.runtime.Stable
import app.skerry.shared.trust.HOST_TRUST_TIMEOUT_MILLIS
import app.skerry.shared.trust.HostTrustPrompt
import app.skerry.shared.trust.HostTrustRequest
import app.skerry.ui.design.PromptQueue
import kotlinx.coroutines.flow.StateFlow

/**
 * A trust question waiting for the user. [id] identifies this exact question, so an answer from a
 * dialog that has already been replaced is dropped instead of landing on the next connection's.
 */
data class HostTrustQuestion(val id: Long, val request: HostTrustRequest)

/**
 * Bridges the verifiers' trust question to a dialog: the connection asks, this controller publishes
 * it as [pending], and the handshake waits until the user answers or the deadline passes.
 *
 * The queueing, the deadline and the id-guarded answer are [PromptQueue]'s, the same machinery
 * behind [app.skerry.ui.connection.KeyboardInteractivePromptController] — a 2FA code and a host key
 * are the same shape of question asked from a transport thread.
 *
 * An unanswered question refuses. A handshake is held open the whole time it is on screen, and
 * refusing costs a reconnect while accepting-by-timeout would trust a key nobody looked at.
 */
@Stable
class HostTrustPromptController : HostTrustPrompt {

    private val queue = PromptQueue<HostTrustQuestion, Boolean>(
        timeoutMillis = HOST_TRUST_TIMEOUT_MILLIS,
        idOf = { it.id },
    )

    /** The question to show, or null when nothing is being asked. */
    val pending: StateFlow<HostTrustQuestion?> = queue.pending

    override suspend fun confirm(request: HostTrustRequest): Boolean =
        queue.ask(build = { HostTrustQuestion(id = it, request = request) }, onTimeout = false)

    /** Trusts the key on screen. [questionId] guards against an answer from a replaced dialog. */
    fun accept(questionId: Long? = pending.value?.id) = queue.answer(questionId, true)

    /** Refuses the key on screen, which fails that connection. */
    fun refuse(questionId: Long? = pending.value?.id) = queue.answer(questionId, false)

    /**
     * Refuses whatever is being asked right now.
     *
     * Called when the vault locks: the dialog lives inside the unlocked chrome, so a lock takes the
     * question off screen while the handshake would keep waiting behind the lock screen for an
     * answer nobody can give — the same call the keyboard-interactive prompt makes.
     */
    fun cancelPending() = queue.cancelPending(false)
}
