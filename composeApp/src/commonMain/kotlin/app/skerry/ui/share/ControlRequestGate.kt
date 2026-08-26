package app.skerry.ui.share

import androidx.compose.runtime.Stable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * How often a viewer may put "wants control" in front of the host (#343).
 *
 * `ShareFrame.ControlRequest` carries no sequence number, so the host cannot tell a fresh request
 * from one the relay is replaying — the ciphertext re-authenticates under the team key exactly as
 * the original did. And joining a live share needs team membership only, not the host's approval,
 * so a member can send the frame in a loop. Without this the host denies, the Grant/Deny row
 * disappears, and the next frame puts it straight back over the shell they are working in.
 *
 * The rule is the host's decision, not the wire's: one question at a time, a denial stands for
 * [CONTROL_REASK_WINDOW] against the account that asked, and no two questions land closer together
 * than [CONTROL_PROMPT_FLOOR] whoever they name. That last one is what holds against the relay
 * rather than against a member: the account is the relay's own stamp (`ShareEvent.Data.from`), so a
 * relay replaying one captured ciphertext under a fresh invented name misses the per-account window
 * every time and only the floor is left. Granting clears the refusals — input is allowed for every
 * viewer at that point, and after the host takes it back asking must work again.
 *
 * A question nobody answers ages out after [CONTROL_REASK_WINDOW]: the Grant/Deny row lives in the
 * share popup, so a host who never opens it would otherwise hold the one slot for the life of the
 * share and every other viewer's request would be dropped in silence.
 */
@Stable
class ControlRequestGate(private val source: TimeSource = TimeSource.Monotonic) {

    // [admits] is called from the share socket coroutine (Dispatchers.Default) while [answered] and
    // [reset] come off the UI thread's Grant/Deny buttons and the input toggle. Unguarded, the two
    // structurally modify `refused` at once, and `pending` is a plain field the worker can go on
    // reading as taken long after the host cleared it — the gate wedged shut for the whole share.
    private val lock = SynchronizedObject()

    /** The asker whose question is on screen, or null when the host has nothing to answer. */
    private var pending: String? = null

    /** When [pending] was raised, so an unanswered question can age out. */
    private var pendingSince: TimeMark? = null

    /** When any question was last raised — the share-wide floor, whoever it named. */
    private var lastRaised: TimeMark? = null

    /** When each account was last refused; insertion-ordered, so the oldest is the one to drop. */
    private val refused = LinkedHashMap<String, TimeMark>()

    /**
     * Whether this request should be raised. [account] is the relay's stamp on the socket the frame
     * arrived on (#312) — null from a relay older than that protocol, and those share one bucket
     * because nothing separates them.
     */
    fun admits(account: String?): Boolean = synchronized(lock) {
        if (questionIsLive()) return@synchronized false
        if (lastRaised.within(CONTROL_PROMPT_FLOOR)) return@synchronized false
        val asker = account ?: UNNAMED_ASKER
        if (refused[asker].within(CONTROL_REASK_WINDOW)) return@synchronized false
        refused.remove(asker)
        pending = asker
        pendingSince = source.markNow()
        lastRaised = pendingSince
        true
    }

    /**
     * The host answered — through the Grant/Deny row or the input toggle, which is the same decision
     * reached another way.
     */
    fun answered(granted: Boolean): Unit = synchronized(lock) {
        val asker = pending
        pending = null
        pendingSince = null
        when {
            granted -> refused.clear()
            asker != null -> record(asker)
        }
    }

    /**
     * The question is gone rather than answered — the viewer who asked it stopped watching. The slot
     * is freed at once, so the next colleague to ask is not dropped behind a question nobody can
     * answer any more; the account that asked is charged its window all the same. Leaving is the
     * viewer's own decision, and a free withdrawal would be the way around the per-account window:
     * ask, drop the socket, rejoin, ask again every [CONTROL_PROMPT_FLOOR].
     */
    fun withdrawn(): Unit = synchronized(lock) {
        val asker = pending
        pending = null
        pendingSince = null
        asker?.let { record(it) }
    }

    /** A new share: nothing has been asked in it, and no refusal from the last one carries over. */
    fun reset(): Unit = synchronized(lock) {
        pending = null
        pendingSince = null
        lastRaised = null
        refused.clear()
    }

    /** Whether a question is still standing in front of the host, dropping it once it has aged out. */
    private fun questionIsLive(): Boolean {
        if (pending == null) return false
        if (pendingSince.within(CONTROL_REASK_WINDOW)) return true
        pending = null
        pendingSince = null
        return false
    }

    private fun record(asker: String) {
        // Expired rows are refusals in name only, and a table nothing trims grows for as long as the
        // share is up. What is left after the trim cannot outgrow [CONTROL_REASK_WINDOW] divided by
        // [CONTROL_PROMPT_FLOOR]: a refusal is only ever recorded for a question that was raised,
        // and no two questions are raised closer together than the floor — so the accounts a relay
        // can invent buy it rows it has to wait for, not a map it can fill.
        refused.entries.removeAll { !it.value.within(CONTROL_REASK_WINDOW) }
        while (refused.size >= MAX_TRACKED_REFUSALS) {
            refused.remove(refused.keys.first())
        }
        refused[asker] = source.markNow()
    }

    private fun TimeMark?.within(window: Duration): Boolean =
        this != null && elapsedNow() < window
}

/**
 * How long a denial holds. Long enough that a loop of requests — a member's or the relay's — costs
 * the host one prompt a minute instead of one per frame, short enough that a colleague who was told
 * no and then genuinely needs the keyboard can ask again in the same session.
 */
internal val CONTROL_REASK_WINDOW = 60.seconds

/**
 * The least time between two questions, whoever they name. The per-account window is keyed on a
 * label the relay writes, so this is the part of the bound that survives a relay inventing a fresh
 * account per replay; kept short because it also delays a second colleague who genuinely wants the
 * keyboard right after the first was answered.
 */
internal val CONTROL_PROMPT_FLOOR = 10.seconds

/**
 * A hard ceiling on the refusal table, above the ration the floor already leaves: a row is recorded
 * only for a question that was raised, and no two questions are raised closer than
 * [CONTROL_PROMPT_FLOOR], so [CONTROL_REASK_WINDOW] can never hold more than seven of them. Nothing
 * reaches this today — it is here so that shortening the floor cannot quietly turn a map keyed on
 * relay-supplied strings back into an unbounded one.
 */
internal const val MAX_TRACKED_REFUSALS = 8

/** Bucket for requests the relay did not name — one for all of them, since nothing tells them apart. */
private const val UNNAMED_ASKER = ""
