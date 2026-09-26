package app.skerry.ui.terminal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel

/**
 * Bytes on their way to the PTY — typed input, mouse reports, the emulator's replies. Any coroutine
 * may queue; the single [drainTo] loop writes them in order. The queue is unbounded, so queueing
 * never blocks or drops, except for [reply] while the host is not reading.
 */
internal class TerminalOutbound {
    private class Item(val bytes: ByteArray, val reply: Boolean)

    private val queue = Channel<Item>(Channel.UNLIMITED)

    // Reply bytes queued and not yet handed to the session — replies only: a paste backed up
    // behind a slow link is not a host flooding queries, and must not cost it the answer it waits
    // on. Read by [reply] on the emulator owner, written there and by the writer, hence the lock.
    private val lock = SynchronizedObject()
    private var queuedReplyBytes = 0L

    fun send(bytes: ByteArray) {
        queue.trySend(Item(bytes, reply = false))
    }

    /**
     * A reply the emulator owes the host. Dropped while [REPLY_BACKLOG_BYTES] already wait: a host
     * that asks without reading would otherwise grow the queue without bound, each query costing a
     * few bytes and its answer several times that. [send] is never dropped.
     */
    fun reply(bytes: ByteArray) {
        synchronized(lock) {
            if (queuedReplyBytes >= REPLY_BACKLOG_BYTES) return
            queuedReplyBytes += bytes.size
        }
        queue.trySend(Item(bytes, reply = true))
    }

    /** The sole consumer: writes everything queued through [write], in order, until cancelled. */
    suspend fun drainTo(write: suspend (ByteArray) -> Unit) {
        for (item in queue) write(withQueuedBehind(item))
    }

    /**
     * [first] joined by whatever else is already queued, up to [OUTBOUND_BATCH_BYTES] — in order, and
     * without waiting for more: an idle keystroke goes out alone and at once.
     */
    private fun withQueuedBehind(first: Item): ByteArray {
        var total = first.bytes.size
        var replies = if (first.reply) total else 0
        var batch: ArrayList<Item>? = null
        while (total < OUTBOUND_BATCH_BYTES) {
            val next = queue.tryReceive().getOrNull() ?: break
            (batch ?: arrayListOf(first).also { batch = it }).add(next)
            total += next.bytes.size
            if (next.reply) replies += next.bytes.size
        }
        if (replies > 0) synchronized(lock) { queuedReplyBytes -= replies }
        val parts = batch ?: return first.bytes
        val out = ByteArray(total)
        var at = 0
        for (part in parts) {
            part.bytes.copyInto(out, at)
            at += part.bytes.size
        }
        return out
    }
}

/**
 * Most bytes one PTY write carries when several were queued behind the write in flight. Each write
 * is a flushed SSH packet; a burst — key repeat, mouse motion reports, replies to a volley of
 * queries — goes out in one instead of a packet per few bytes. Capped so one write never holds the
 * channel for long; a single larger item (a paste) still goes out whole.
 */
internal const val OUTBOUND_BATCH_BYTES = 32 * 1024

/**
 * Queued reply bytes past which the emulator's replies are dropped rather than queued. Well above any
 * honest volley — a full 256-color palette query answers in about 6 KiB.
 */
internal const val REPLY_BACKLOG_BYTES = 64 * 1024
