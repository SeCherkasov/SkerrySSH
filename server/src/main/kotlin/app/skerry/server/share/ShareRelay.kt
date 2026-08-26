package app.skerry.server.share

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory relay for shared terminal sessions (`/teams/{id}/shares/...`). Zero-knowledge like the
 * rest of the server: a frame is an opaque blob sealed under the team key, so the relay forwards
 * bytes it cannot read and stores nothing on disk.
 *
 * A share exists exactly as long as its host's socket does — the registry is the set of live host
 * sessions, so a crashed client leaves nothing behind to clean up. Single-instance model, like
 * [app.skerry.server.sync.ChangeNotifier]: horizontal scaling would need an external bus.
 *
 * Every bound here exists because both ends are remote and untrusted: [maxSharesPerTeam] and
 * [maxGuestsPerShare] cap how much a team can hold open, [replayBytes] caps the catch-up buffer a
 * host's output fills, and [guestQueueFrames] caps a single viewer's backlog — a viewer that stops
 * reading is dropped rather than allowed to stall the host's shell (broadcast never suspends).
 *
 * The catch-up buffer and a viewer's queue are two bounds on the same frames, so they are counted
 * in the same unit as well: the buffer is capped by bytes AND by half a viewer's queue
 * ([HostShareSession.replayFrames]), leaving the other half as live headroom. Counted only in
 * bytes, a history of interactive-sized frames overflowed the queue of the viewer it was replayed
 * into — the surplus was dropped in silence and the next broadcast detached the viewer that had
 * just joined (#310).
 */
class ShareRelay(
    private val maxSharesPerTeam: Int = 8,
    private val maxGuestsPerShare: Int = 16,
    private val replayBytes: Int = 64 * 1024,
    private val guestQueueFrames: Int = 256,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Live host sessions by team; the map itself is the registry of what is being shared. */
    private val shares = ConcurrentHashMap<String, ConcurrentHashMap<String, HostShareSession>>()

    /** Opens a share, or refuses: the id is already live, or the team is at its cap. */
    fun open(teamId: String, shareId: String, hostAccountId: String, meta: String): ShareOpen {
        while (true) {
            val teamShares = shares.computeIfAbsent(teamId) { ConcurrentHashMap() }
            val session = HostShareSession(
                relay = this,
                teamId = teamId,
                shareId = shareId,
                hostAccountId = hostAccountId,
                meta = meta,
                startedAt = now(),
                replayBytes = replayBytes,
                maxGuests = maxGuestsPerShare,
                guestQueueFrames = guestQueueFrames,
            )
            // putIfAbsent first: two hosts racing on the same id must not both believe they own it.
            if (teamShares.putIfAbsent(shareId, session) != null) return ShareOpen.Taken
            if (teamShares.size > maxSharesPerTeam) {
                teamShares.remove(shareId, session)
                return ShareOpen.TooMany
            }
            // The bucket may have been dropped meanwhile: [forget] removes an empty one, and the
            // last share of this team closing between the lookup above and the insert leaves this
            // session in a map no longer reachable from `shares` — so the host believes it is
            // sharing while `list` and `join` can never see it. Take the bucket again.
            if (shares[teamId] === teamShares) return ShareOpen.Started(session)
            teamShares.remove(shareId, session)
        }
    }

    /** Live shares of [teamId] — what the team's members see offered to join. */
    fun list(teamId: String): List<ShareInfo> =
        shares[teamId]?.values.orEmpty()
            .map { ShareInfo(it.shareId, it.hostAccountId, it.meta, it.startedAt, it.viewers) }
            .sortedBy { it.startedAt }

    /** Attaches a viewer to a live share, or refuses: no such share in this team, or it is full. */
    fun join(teamId: String, shareId: String, accountId: String): ShareJoin {
        val session = shares[teamId]?.get(shareId) ?: return ShareJoin.NoShare
        return session.addGuest(accountId)
    }

    internal fun forget(session: HostShareSession) {
        val teamShares = shares[session.teamId] ?: return
        teamShares.remove(session.shareId, session)
        // Drop the team's empty bucket so a long-lived server doesn't accumulate one map per team
        // that ever shared anything. Emptiness and removal have to be ONE step: tested and then
        // removed, a share opened in between is evicted with the bucket and becomes invisible to
        // `list` and `join` for its whole life.
        shares.computeIfPresent(session.teamId) { _, bucket -> bucket.takeIf { it.isNotEmpty() } }
    }
}

/** What a team's members see about a live share; [meta] is a blob only members can read. */
data class ShareInfo(
    val shareId: String,
    val hostAccountId: String,
    val meta: String,
    val startedAt: Long,
    val viewers: Int,
)

/** Outcome of [ShareRelay.open]. */
sealed interface ShareOpen {
    class Started(val session: HostShareSession) : ShareOpen
    data object Taken : ShareOpen
    data object TooMany : ShareOpen
}

/** Outcome of [ShareRelay.join]. */
sealed interface ShareJoin {
    class Joined(val session: GuestShareSession) : ShareJoin
    data object NoShare : ShareJoin
    data object Full : ShareJoin
}

/**
 * The host's end of one share: broadcasts its terminal frames to the viewers, receives their
 * keystrokes and viewer-count changes. Ends when the host's socket closes ([end]) — idempotent, and
 * the only way a share leaves the registry.
 */
class HostShareSession internal constructor(
    private val relay: ShareRelay,
    val teamId: String,
    val shareId: String,
    val hostAccountId: String,
    val meta: String,
    val startedAt: Long,
    private val replayBytes: Int,
    private val maxGuests: Int,
    private val guestQueueFrames: Int,
) {
    private val guestIds = AtomicLong(0)
    private val guests = ConcurrentHashMap<Long, GuestShareSession>()

    // Frames from the viewers, merged into one stream for the host. CONFLATED would lose
    // keystrokes, so this is a bounded buffer: past it a flooding viewer's frames are dropped
    // rather than allowed to grow the server's heap.
    private val input = Channel<GuestFrame>(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST)

    // Who is watching, in join order. CONFLATED on purpose: only the current set matters, and the
    // host must never block on delivering it. Accounts, not counts: the host's UI names the
    // colleagues on its session, and membership already tells every member who the others are.
    private val viewerCounts = Channel<List<String>>(Channel.CONFLATED)

    /**
     * Frames of catch-up a joining viewer is sent: half its queue, so what it is caught up with
     * always fits and the other half stays free for the live stream that follows (#310). The byte
     * cap still applies — whichever bound is reached first evicts the oldest frame.
     *
     * A one-frame queue is the degenerate case: half of it rounds to nothing, so the catch-up is
     * that single frame and nothing is left over. [init] is what keeps that the worst case.
     */
    private val replayFrames = (guestQueueFrames / 2).coerceAtLeast(1)

    init {
        // The whole catch-up buffer must fit into a fresh guest's queue, or [addGuest]'s offer drops
        // a frame with no trace and the next broadcast detaches the viewer — issue #310 itself. The
        // arithmetic above guarantees it today; this is what makes a future change to it fail loudly
        // at startup instead of quietly at a join.
        require(replayFrames <= guestQueueFrames) { "replay $replayFrames exceeds guest queue $guestQueueFrames" }
    }

    /** Catch-up buffer: the newest [replayBytes] / [replayFrames] of host output, whichever is less. */
    private val replay = ArrayDeque<ByteArray>()
    private var replaySize = 0
    private val replayLock = Any()

    @Volatile
    private var ended = false

    /** How many viewers are attached right now. */
    val viewers: Int get() = guests.size

    /** Sends one sealed frame to every viewer. Never suspends — see the class doc on slow viewers. */
    fun broadcast(frame: ByteArray) {
        if (ended) return
        // Remembering the frame and picking who is live for it happen under one lock: a join landing
        // between the two would replay this frame out of the buffer and then be offered it again as
        // a live frame, and the viewer would render the same output twice.
        val live = synchronized(replayLock) {
            rememberForReplay(frame)
            guests.values.toList()
        }
        live.forEach { guest ->
            if (!guest.offer(frame)) dropGuest(guest)
        }
    }

    /** The next keystroke frame from any viewer, or `null` once the share is over. */
    suspend fun receiveInput(): GuestFrame? = input.receiveCatching().getOrNull()

    /** The accounts watching right now, or `null` once the share is over. */
    suspend fun receiveViewers(): List<String>? = viewerCounts.receiveCatching().getOrNull()

    /** Ends the share: viewers' streams close and the share leaves the registry. Idempotent. */
    fun end() {
        if (ended) return
        ended = true
        relay.forget(this)
        input.close()
        viewerCounts.close()
        guests.values.forEach { it.closeStream() }
        guests.clear()
        synchronized(replayLock) {
            replay.clear()
            replaySize = 0
        }
    }

    internal fun addGuest(accountId: String): ShareJoin {
        if (ended) return ShareJoin.NoShare
        val guest = GuestShareSession(this, guestIds.incrementAndGet(), accountId, guestQueueFrames)
        synchronized(replayLock) {
            // Under the replay lock so a broadcast racing the join can't interleave: the viewer sees
            // the catch-up frames and then the live ones, never a live frame before its history and
            // never the same frame as both.
            if (guests.size >= maxGuests) return ShareJoin.Full
            // The buffer is bounded to at most the queue this viewer was just given (see [init]), so
            // every frame of it fits; eviction keeps the newest, so a history too long to deliver
            // whole reaches the viewer as its newest tail rather than a head that renders as garbage.
            replay.forEach { guest.offer(it) }
            guests[guest.id] = guest
            publishViewers()
        }
        return ShareJoin.Joined(guest)
    }

    internal fun submitInput(from: String, frame: ByteArray): Boolean {
        if (ended) return false
        return input.trySend(GuestFrame(from, frame)).isSuccess
    }

    internal fun removeGuest(guest: GuestShareSession) {
        synchronized(replayLock) {
            if (guests.remove(guest.id) == null) return
            publishViewers()
        }
    }

    private fun dropGuest(guest: GuestShareSession) {
        guest.closeStream()
        removeGuest(guest)
    }

    /**
     * Publishes who is watching. Called under [replayLock], with the change that caused it: the
     * channel is CONFLATED, so a read taken outside the lock can be published after a later one and
     * leave the host's panel showing a viewer list that never existed — an empty one, permanently,
     * while somebody is still on the shell.
     */
    private fun publishViewers() {
        if (!ended) viewerCounts.trySend(guests.values.sortedBy { it.id }.map { it.accountId })
    }

    private fun rememberForReplay(frame: ByteArray) {
        if (frame.size > replayBytes) {
            // A single frame larger than the whole buffer would evict everything and still not fit.
            synchronized(replayLock) { replay.clear(); replaySize = 0 }
            return
        }
        synchronized(replayLock) {
            replay.addLast(frame)
            replaySize += frame.size
            while ((replaySize > replayBytes || replay.size > replayFrames) && replay.isNotEmpty()) {
                replaySize -= replay.removeFirst().size
            }
        }
    }

    internal val isEnded: Boolean get() = ended
}

/**
 * One sealed frame on its way to the host, with the account of the socket it arrived on.
 *
 * [from] is the relay's own answer, taken from the JWT the socket authenticated with — not from
 * anything inside the frame. The frame's payload is sealed under the team key, which every member
 * holds, so a member can compose one that says whatever it likes about who sent it; this is the one
 * part of it a viewer cannot write (#312).
 */
class GuestFrame(val from: String, val bytes: ByteArray)

/**
 * One viewer's end of a share: reads the host's frames (starting with the catch-up buffer) and
 * sends keystrokes back. The host decides whether those keystrokes are applied — the relay only
 * carries them.
 */
class GuestShareSession internal constructor(
    private val host: HostShareSession,
    internal val id: Long,
    /** The account watching through this socket; the host sees it beside the session. */
    internal val accountId: String,
    queueFrames: Int,
) {
    private val stream = Channel<ByteArray>(capacity = queueFrames)

    /** The next frame from the host, or `null` when the share ended or this viewer fell behind. */
    suspend fun receive(): ByteArray? = stream.receiveCatching().getOrNull()

    /** Sends a keystroke frame to the host; `false` once the share is over or this viewer is gone. */
    fun sendToHost(frame: ByteArray): Boolean {
        if (gone) return false
        return host.submitInput(accountId, frame)
    }

    /** Detaches this viewer (socket closed / left). Idempotent. */
    fun leave() {
        closeStream()
        host.removeGuest(this)
    }

    internal fun offer(frame: ByteArray): Boolean = stream.trySend(frame).isSuccess

    internal fun closeStream() {
        gone = true
        stream.close()
    }

    @Volatile
    private var gone = false
}
