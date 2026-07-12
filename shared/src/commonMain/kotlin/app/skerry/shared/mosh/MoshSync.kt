package app.skerry.shared.mosh

/** Minimum interval between outgoing sends (mosh's SEND_MINDELAY): coalesces bursts. */
const val MOSH_SEND_MINDELAY_MS: Long = 8

/** Idle heartbeat interval: an empty ack keeps NAT bindings alive (mosh's ACK_INTERVAL). */
const val MOSH_ACK_INTERVAL_MS: Long = 3000

/**
 * Client side of mosh's State Synchronization Protocol for the outgoing user-input stream.
 *
 * Pure state machine: time is passed in, the caller (the UDP loop) decides when to call
 * [poll] and sleeps until [nextWakeMs]. Simplification vs. mosh: diffs are always taken
 * from the last *acknowledged* state instead of an optimistically assumed one — on loss we
 * retransmit a slightly larger diff, in exchange for no assumed-state bookkeeping.
 *
 * [startMs] anchors the internal send clock; the first send is allowed from
 * `startMs` onward (callers pass their monotonic "now" at construction).
 */
class MoshOutgoing(private val rtt: MoshRtt, startMs: Long = MOSH_SEND_MINDELAY_MS) {

    private class PendingEvent(val num: ULong, val event: MoshUserEvent)

    private val pending = ArrayDeque<PendingEvent>()
    private var currentNum = 0uL
    private var ackedNum = 0uL
    private var lastSentNum = 0uL
    private var lastSendAt = startMs - MOSH_SEND_MINDELAY_MS
    private var shutdown = false

    /** Number of shutdown instructions sent so far; the channel gives up after a few. */
    var shutdownSends = 0
        private set

    fun addKeystroke(keys: ByteArray) = add(MoshUserEvent.Keystroke(keys))

    fun addResize(cols: Int, rows: Int) = add(MoshUserEvent.Resize(cols, rows))

    private fun add(event: MoshUserEvent) {
        currentNum++
        pending.addLast(PendingEvent(currentNum, event))
    }

    fun onAck(ackNum: ULong) {
        if (ackNum <= ackedNum || ackNum > currentNum) return
        ackedNum = ackNum
        while (pending.isNotEmpty() && pending.first().num <= ackNum) pending.removeFirst()
    }

    fun startShutdown() {
        shutdown = true
    }

    /** True while events added after the last send are still waiting to go out. */
    fun hasUnsentData(): Boolean = !shutdown && currentNum > lastSentNum

    /**
     * Returns the instruction to send now, or null if nothing is due. [serverAckNum] is the
     * latest applied server state (piggybacked ack); [forceAck] flushes an outstanding ack
     * of a received state regardless of our own send schedule.
     */
    fun poll(nowMs: Long, serverAckNum: ULong, forceAck: Boolean): MoshInstruction? {
        if (shutdown) {
            if (shutdownSends > 0 && nowMs - lastSendAt < rtt.rto()) return null
            shutdownSends++
            lastSendAt = nowMs
            return MoshInstruction(
                oldNum = ackedNum,
                newNum = ULong.MAX_VALUE,
                ackNum = serverAckNum,
                throwawayNum = ackedNum,
                diff = ByteArray(0),
            )
        }
        val freshDue = currentNum > lastSentNum && nowMs - lastSendAt >= MOSH_SEND_MINDELAY_MS
        val retransmitDue = ackedNum < currentNum && nowMs - lastSendAt >= rtt.rto()
        val heartbeatDue = nowMs - lastSendAt >= MOSH_ACK_INTERVAL_MS
        if (!forceAck && !freshDue && !retransmitDue && !heartbeatDue) return null
        lastSendAt = nowMs
        lastSentNum = currentNum
        return MoshInstruction(
            oldNum = ackedNum,
            newNum = currentNum,
            ackNum = serverAckNum,
            throwawayNum = ackedNum,
            diff = if (pending.isEmpty()) {
                ByteArray(0)
            } else {
                encodeUserMessage(pending.map { it.event })
            },
        )
    }

    /** Milliseconds until [poll] could have something to send; the UDP loop sleeps at most this. */
    fun nextWakeMs(nowMs: Long): Long {
        var wake = lastSendAt + MOSH_ACK_INTERVAL_MS
        if (shutdown) wake = minOf(wake, lastSendAt + rtt.rto())
        if (ackedNum < currentNum) wake = minOf(wake, lastSendAt + rtt.rto())
        if (currentNum > lastSentNum) wake = minOf(wake, lastSendAt + MOSH_SEND_MINDELAY_MS)
        return (wake - nowMs).coerceAtLeast(0)
    }
}

/**
 * Client side of SSP for incoming terminal states. Only the latest applied state is kept
 * (no state history): a diff against anything else is dropped and the server falls back to
 * diffing from the last state we acknowledged. Convergent, at the cost of an extra
 * round-trip after heavy loss — same trade-off mosh itself makes when its history misses.
 */
class MoshIncoming {

    /** Latest applied server state — goes out as `ack_num` on the next send. */
    var ackNum = 0uL
        private set

    /** True while an applied state still awaits an ack flush by the send loop. */
    var ackOutstanding = false
        private set

    fun clearAckOutstanding() {
        ackOutstanding = false
    }

    /**
     * Applies a server instruction. Returns the host updates to feed into the terminal,
     * an empty list for a duplicate (re-ack only), or null when the diff base is unknown
     * and the instruction must be ignored.
     */
    fun onInstruction(instruction: MoshInstruction): List<MoshHostUpdate>? {
        if (instruction.newNum <= ackNum) {
            // The server hasn't seen our ack yet; repeat it rather than re-apply the diff.
            ackOutstanding = true
            return emptyList()
        }
        if (instruction.oldNum != ackNum) return null
        val updates = try {
            decodeHostMessage(instruction.diff)
        } catch (e: MoshWireException) {
            throw MoshWireException("undecodable host diff for state ${instruction.newNum}: ${e.message}")
        }
        ackNum = instruction.newNum
        ackOutstanding = true
        return updates
    }
}
