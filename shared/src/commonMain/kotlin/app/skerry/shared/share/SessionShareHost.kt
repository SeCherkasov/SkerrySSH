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
    private val onControlRequest: (String) -> Unit = {},
) {
    // Serializes writes to the socket: the output pump, geometry announcements and the closing End
    // frame all reach it from different coroutines, and a half-interleaved frame is not a frame.
    private val writeLock = Mutex()

    // Guards the one-shot teardown separately from [writeLock] — endQuietly writes while holding it,
    // and the lock is not reentrant.
    private val endLock = Mutex()
    private var ended = false

    private var lastViewers = 0

    // Highest sequence number seen from each viewer's socket. The relay sees every ciphertext it
    // forwards and could hand back a captured keystroke frame: it re-authenticates under the team
    // key, so freshness — not the AEAD — is what stops it being typed into the shell twice. Bounded
    // so a relay inventing sender ids cannot grow the map without limit.
    private val lastInputSeq = mutableMapOf<Long, Long>()

    // Sender id -> the account that socket named itself with ([ShareFrame.Hello]). Only used to put
    // a name on a hint; an unnamed viewer simply produces no name, never a wrong one.
    private val viewerNames = mutableMapOf<Long, String>()

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
                        is ShareFrame.Input -> if (allowInput() && isFresh(frame)) {
                            toShell(frame.bytes)
                            viewerNames[frame.sender]?.let(onTyping)
                        }
                        is ShareFrame.Hello -> if (viewerNames.size < MAX_TRACKED_SENDERS) {
                            viewerNames[frame.sender] = frame.accountId
                        }
                        is ShareFrame.ControlRequest -> viewerNames[frame.sender]?.let(onControlRequest)
                        // Output/Resize/End travel the other way; a viewer sending one is either an
                        // older client or the relay making things up.
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * Whether this keystroke frame is new rather than one the relay is replaying: sequence numbers
     * are per viewer socket and strictly increasing. An unknown sender is accepted (a viewer that
     * just joined), unless the table is already full of them.
     */
    private fun isFresh(frame: ShareFrame.Input): Boolean {
        val last = lastInputSeq[frame.sender]
        if (last == null && lastInputSeq.size >= MAX_TRACKED_SENDERS) return false
        if (last != null && frame.seq <= last) return false
        lastInputSeq[frame.sender] = frame.seq
        return true
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
