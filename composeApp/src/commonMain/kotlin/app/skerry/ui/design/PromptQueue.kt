package app.skerry.ui.design

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The queue behind a modal question a connection asks and a person answers: a second factor, a host
 * key nobody has vouched for yet. [Q] is what the dialog draws, [A] what the connection waits for.
 *
 * A [StateFlow] rather than Compose state because the caller is a transport thread (sshj's reader,
 * a TLS handshake) and a flow needs no confinement of its own. A mutex because two hosts can ask at
 * the same moment: one dialog can only be answered for one of them, and the loser would otherwise
 * wait for an answer that can never arrive. The wait for a *turn* is unbounded and the wait for the
 * *user* is not — [timeoutMillis] starts when the question reaches the screen, so one queued behind
 * another cannot spend its whole budget unseen and then fail as if it had been answered.
 *
 * The question on screen and the deferred its answer goes into are one value ([Slot]), read in one
 * go by [answer]: checking the id against one field and completing a deferred read from another
 * leaves a window — the asking thread's deadline expires between the two, the next question installs
 * its own deferred, and the answer a person gave for the host they inspected settles a host they
 * never saw.
 *
 * A nullable [A] cannot tell "the user answered null" from "nobody answered": both come back as the
 * `onTimeout` value. That is deliberate for the callers here — dismissing the 2FA prompt and letting
 * it expire are the same outcome — but a caller that needs to tell them apart has to carry the
 * difference in [A] itself (a wrapper, a sealed answer), not read it off the null.
 */
@Stable
class PromptQueue<Q : Any, A>(
    private val timeoutMillis: Long,
    private val idOf: (Q) -> Long,
) {

    private val _pending = MutableStateFlow<Q?>(null)

    /** The question to show, or null when nothing is being asked. */
    val pending: StateFlow<Q?> = _pending.asStateFlow()

    private val slot = MutableStateFlow<Slot<A>?>(null)
    private val turnstile = Mutex()
    private var nextId: Long = 0

    // Bumped by [cancelPending]. An ask that entered before the bump has had its answer taken away
    // from it and never reaches the screen.
    private val generation = MutableStateFlow(0L)

    /**
     * Publishes the question [build] makes from the id it is given, and waits. Returns [onTimeout]
     * if the deadline passes with nobody answering — an unanswered question must resolve to whatever
     * is safe for the caller, never to the answer it was hoping for.
     */
    suspend fun ask(build: (Long) -> Q, onTimeout: A): A {
        val entered = generation.value
        return turnstile.withLock { asked(entered, build, onTimeout) }
    }

    private suspend fun asked(entered: Long, build: (Long) -> Q, onTimeout: A): A {
        // Drained while this one waited for its turn: showing it now would put a dialog on a screen
        // that has since gone behind the lock, and hold the handshake open for the full deadline
        // waiting for an answer nobody can give.
        if (generation.value != entered) return onTimeout
        val answer = CompletableDeferred<A>()
        val question = build(nextId++)
        slot.value = Slot(idOf(question), answer)
        _pending.value = question
        try {
            // Read again now that the slot is published: [cancelPending] bumps the generation before
            // it reads the slot, so a drain that ran past an empty slot is always visible here.
            // Missing it would leave the question drawn on a chrome that is gone, holding its
            // handshake open for the whole deadline.
            if (generation.value != entered) return onTimeout
            return withTimeoutOrNull(timeoutMillis) { answer.await() } ?: onTimeout
        } finally {
            slot.value = null
            // Only clear a question that is still ours: with the mutex held it always is, but the
            // check keeps that assumption from silently breaking if the guard ever changes.
            _pending.compareAndSet(question, null)
        }
    }

    /**
     * Answers the question [id] identifies with [value]. A null [id], or one belonging to a dialog
     * that has since been replaced, is dropped: a late click must not settle the next connection's
     * question.
     */
    fun answer(id: Long?, value: A) {
        val showing = slot.value ?: return
        if (id == null || id != showing.id) return
        showing.answer.complete(value)
    }

    /**
     * Answers whatever is on screen right now with [value], for a caller with no id to check, and
     * drains the questions still waiting for a turn behind it — they are answered with their own
     * `onTimeout` without ever being drawn. Answering only the visible one would leave the next in
     * line publishing itself into a chrome that is no longer composed.
     *
     * Asks made *after* this call are unaffected: the queue is emptied, not closed.
     */
    fun cancelPending(value: A) {
        generation.update { it + 1 }
        slot.value?.answer?.complete(value)
    }

    private data class Slot<A>(val id: Long, val answer: CompletableDeferred<A>)
}
