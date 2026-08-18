package app.skerry.shared.ai.local

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands [value] to whoever waits on [answer], and leaves it in [held] so it can still be closed.
 *
 * A reply and the deadline its caller set are two independent things, and which lands first is a
 * race nobody arbitrates — so the loser must not be the resource. One that arrives after the caller
 * gave up belongs to nobody and is closed here; one that wins the completion is left in [held],
 * because the caller can be cancelled between being handed the value and taking ownership of it.
 * The caller closes whatever is still there once it knows it never received it.
 *
 * A `null` [value] only closes the door: there is nothing to own and nothing to leak.
 */
internal fun <T : Any> deliverOrClose(
    answer: CompletableDeferred<T?>,
    held: AtomicReference<T?>,
    value: T?,
    close: (T) -> Unit,
) {
    if (value == null) {
        answer.complete(null)
        return
    }
    // Published before the completion, not after: a waiter can resume and be cancelled while this
    // thread is still between the two statements. On losing the race the previous occupant goes
    // back — it is the one that was handed over, and the caller still has to be able to close it.
    val previous = held.getAndSet(value)
    if (!answer.complete(value)) {
        // Closed only if the restore reclaimed it. A failed exchange means the caller took the
        // value out in the meantime and is closing it itself, and closing an fd twice can land on
        // a number something else has since reopened.
        if (held.compareAndSet(value, previous)) runCatching { close(value) }
    }
}
