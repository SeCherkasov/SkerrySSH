package app.skerry.shared.share

import app.skerry.shared.vault.DataKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sharing end of a live terminal: seals this session's output to the team and applies the
 * viewers' keystrokes back into the shell.
 *
 * Everything a viewer sends is untrusted twice over — the relay could have fabricated it, and a
 * viewer is a colleague, not the owner of the machine. So keystrokes are applied only while
 * [allowInput] says so (the host's own toggle, read per frame so revoking input takes effect on the
 * very next keystroke), and only from frames that authenticate as guest-to-host under the team key
 * ([SessionShareCodec]); anything else is dropped and the session continues.
 *
 * The screen geometry ([geometry]) is announced whenever a viewer joins and whenever the host's own
 * terminal is resized ([announceGeometry]) — a viewer renders the host's grid, not their own window.
 *
 * The names beside "is typing" and "wants control" ([onTyping], [onControlRequest]) come from the
 * relay ([ShareEvent.Data.from]), which reads them off the JWT each viewer socket authenticated
 * with. Nothing a viewer writes is used: a [ShareFrame.Hello] is sealed under the TEAM key, which
 * every member holds, so a member could name themselves anybody at all and ask the host for control
 * in their name — and granting it enables input for every viewer, not just the asker (#312). A frame
 * the relay does not name asks for control without naming anybody ([onControlRequest] is called with
 * null): granting is not scoped to the asker, so a nameless prompt gives away nothing a named one
 * would not, and refusing to raise it at all would leave the button dead against a relay older than
 * this — silently, since asking is the viewer's only route to being allowed to type.
 */
class SessionShareHost(
    private val codec: SessionShareCodec,
    private val teamKey: DataKey,
    private val channel: ShareChannel,
    private val output: Flow<ByteArray>,
    private val toShell: suspend (ByteArray) -> Unit,
    private val geometry: () -> ShareFrame.Resize,
    private val allowInput: () -> Boolean,
    /** Called with who is watching, whenever that changes (empty list = nobody). */
    private val onViewers: (List<String>) -> Unit,
    /** A viewer typed into this session; the account it named itself with, for the "typing" hint. */
    private val onTyping: (String) -> Unit = {},
    /** A viewer asked to be allowed to type; the host answers with [announceControl]. */
    private val onControlRequest: (String?) -> Unit = {},
) {
    // Serializes writes to the socket: the output pump, geometry announcements and the closing End
    // frame all reach it from different coroutines, and a half-interleaved frame is not a frame.
    private val writeLock = Mutex()

    // Guards the one-shot teardown separately from [writeLock] — endQuietly writes while holding it,
    // and the lock is not reentrant.
    private val endLock = Mutex()
    private var ended = false

    private var lastViewers = 0

    // Highest sequence number seen from each viewer's socket, with the account the relay named that
    // socket as. The relay sees every ciphertext it forwards and could hand back a captured
    // keystroke frame: it re-authenticates under the team key, so freshness — not the AEAD — is what
    // stops it being typed into the shell twice.
    //
    // The key is the sender id the viewer wrote, because that is what the sequence numbers are
    // counted per; the account is what bounds the table. A sender id costs its own account's budget
    // and nobody else's, and an account's rows go when it stops watching — otherwise a member could
    // seal a handful of frames under invented sender ids, fill the map, and every viewer joining
    // afterwards would have its keystrokes dropped in silence.
    private val lastInputSeq = mutableMapOf<Long, SenderSeq>()

    /**
     * Runs the share until the socket closes or the caller's scope is cancelled. Sends a closing
     * [ShareFrame.End] so viewers learn the session is over instead of watching a frozen screen.
     */
    suspend fun run() {
        try {
            coroutineScope {
                val pump = launch {
                    // The session's output flow is shared with the terminal the user is working in,
                    // and its buffer suspends the producer when the slowest subscriber falls behind.
                    // Buffering here (dropping the oldest under pressure) keeps a congested relay
                    // from freezing the host's own shell: viewers lose frames, the host does not.
                    output.buffer(SHARE_PUMP_FRAMES, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                        .collect { chunk -> chunkShareOutput(chunk).forEach { send(ShareFrame.Output(it)) } }
                }
                readFromViewers()
                pump.cancel() // the socket is gone; nothing left to pump output into
            }
        } finally {
            endQuietly()
        }
    }

    /** Stops sharing: viewers are told the session ended and the socket is released. */
    suspend fun stop() = endQuietly()

    /** Tells every viewer whether they may type right now (the host's toggle, or an answered request). */
    suspend fun announceControl(granted: Boolean) = send(ShareFrame.ControlState(granted))

    /** Re-announces the host's grid — call when the host's own terminal was resized. */
    suspend fun announceGeometry() = send(geometry())

    private suspend fun readFromViewers() {
        while (true) {
            when (val event = channel.receive() ?: return) {
                is ShareEvent.Viewers -> {
                    val count = event.count
                    forgetDepartedViewers(event.accounts)
                    onViewers(event.accounts)
                    // A viewer that just joined has an empty screen and no idea how wide the host's
                    // is; the catch-up buffer alone would render at the viewer's own size.
                    if (count > lastViewers) announceGeometry()
                    lastViewers = count
                }
                is ShareEvent.Data -> {
                    val frame = codec.open(teamKey, event.frame, ShareDirection.GUEST_TO_HOST) ?: continue
                    // Only keystrokes travel this way; a viewer sending anything else is either an
                    // older client or the relay making things up. Either way: not our shell's input.
                    when (frame) {
                        is ShareFrame.Input -> if (allowInput() && isFresh(frame, event.from)) {
                            toShell(frame.bytes)
                            event.from?.let(onTyping)
                        }
                        is ShareFrame.ControlRequest -> onControlRequest(event.from)
                        // Hello was how a viewer used to name itself; the relay names it now and the
                        // frame is ignored. Still sent, so a viewer on this version is named by a
                        // host on an older one. Output/Resize/End travel the other way; a viewer
                        // sending one is either an older client or the relay making things up.
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * Whether this keystroke frame is new rather than one the relay is replaying: sequence numbers
     * are per viewer socket and strictly increasing. An unknown sender is a viewer that just joined
     * and is accepted, as long as there is room for it ([makeRoom]).
     */
    private fun isFresh(frame: ShareFrame.Input, from: String?): Boolean {
        val known = lastInputSeq[frame.sender]
        if (known == null) {
            if (!makeRoom(from)) return false
            lastInputSeq[frame.sender] = SenderSeq(from, frame.seq)
            return true
        }
        // A sender id the viewer chose itself, now arriving from a different account: a member
        // guessing a colleague's id to burn their counter, not that colleague reconnecting.
        if (known.account != from || frame.seq <= known.seq) return false
        known.seq = frame.seq
        return true
    }

    /**
     * Room for one more sender. A named account over its own budget loses its oldest row rather
     * than being refused — a viewer whose socket drops and comes back arrives under a fresh random
     * sender id, and refusing would mute it for the rest of the share. Only frames the relay did not
     * name share one flat budget, because nothing separates them.
     */
    private fun makeRoom(account: String?): Boolean {
        if (account != null) {
            val mine = lastInputSeq.entries.filter { it.value.account == account }
            if (mine.size >= MAX_SENDERS_PER_ACCOUNT) lastInputSeq.remove(mine.first().key)
        }
        return lastInputSeq.size < MAX_TRACKED_SENDERS
    }

    /** Drops the freshness rows of accounts that are no longer watching; their ids cannot come back. */
    private fun forgetDepartedViewers(watching: List<String>) {
        val present = watching.toSet()
        val departed = lastInputSeq.entries.filter { it.value.account != null && it.value.account !in present }
        departed.forEach { lastInputSeq.remove(it.key) }
    }

    private suspend fun send(frame: ShareFrame) {
        writeLock.withLock { channel.send(codec.seal(teamKey, frame, ShareDirection.HOST_TO_GUEST)) }
    }

    /**
     * Best-effort goodbye, exactly once: the socket may already be dead (that is usually why we are
     * here), and a failure to say so must not replace the reason the session ended.
     */
    private suspend fun endQuietly() {
        endLock.withLock {
            if (ended) return
            ended = true
        }
        runQuietly { send(ShareFrame.End) }
        runQuietly { channel.close() }
    }

    private suspend fun runQuietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The relay socket is already gone; teardown has nothing left to do over it.
        }
    }
}

/**
 * Output frames the share pump may hold before it starts dropping the oldest. Roughly a screenful
 * of bursty output; past it a relay is too far behind for what it would deliver to still be the
 * screen the viewers are looking at.
 */
private const val SHARE_PUMP_FRAMES = 256

/** Viewer sockets whose sequence numbers are tracked (the relay's own cap is 16 viewers). */
private const val MAX_TRACKED_SENDERS = 64

/**
 * Sender ids one account may hold at once. A member reconnecting takes a new one each time, so this
 * is how many reconnects are remembered between two changes of the viewer list — and, read the
 * other way, all a member can ever spend of a table shared with their colleagues.
 */
private const val MAX_SENDERS_PER_ACCOUNT = 4

/** One viewer socket's place in the replay window: who the relay said it was, and how far it got. */
private class SenderSeq(val account: String?, var seq: Long)
