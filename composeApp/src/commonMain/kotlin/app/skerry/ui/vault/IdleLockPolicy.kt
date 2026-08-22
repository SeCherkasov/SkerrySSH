package app.skerry.ui.vault

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/** Floor under [IdleLockPolicy.tickMs]: a shorter tick would poll for nothing. */
private const val MIN_TICK_MS = 1_000L

/** [IdleLockPolicy.tickMs] is this fraction of the threshold, so the lock is at most 10% late. */
private const val TICKS_PER_WINDOW = 10

/**
 * How long work in flight may hold the lock off with nobody at the keyboard, counted in idle
 * windows. Proportional rather than flat, because the threshold *is* the user's answer to how long
 * this desk may stand unattended and unlocked: a flat allowance would turn "After 1 minute" into an
 * hour, and the setting would be off by sixty.
 */
private const val MAX_DEFERRAL_WINDOWS = 10

/**
 * Absolute ceiling on that allowance, whatever the threshold. Long enough for the transfers and
 * procedures this exists for, short enough that it cannot be forever: what "in flight" means is
 * decided at the far end of the wire, and a host that never finishes a step — or never answers a
 * read — must not be able to keep a vault open on an unattended desk all night.
 */
private const val MAX_DEFERRAL_MS = 60 * 60_000L

/**
 * When an unlocked vault may lock itself again: the idle auto-lock rule, apart from the gate that
 * runs it ([VaultGate]).
 *
 * Driven by ticks rather than by a wall clock. [touch] is called from input handlers — a pointer
 * moving across the window produces hundreds of events a second — and a timer restarted per event
 * would cancel and relaunch a coroutine just as often, or worse, recompose. Here an event is one
 * boolean write and the timer is a plain loop of [tickMs] delays. The cost is quantization: the
 * lock lands within one tick of the threshold, which is why [tickMs] is a fraction of it rather
 * than a fixed interval.
 *
 * The policy, in full — what the Settings → Security caption has to match:
 *  - any user input (keys, pointer movement, wheel, presses, text from a soft keyboard) restarts
 *    the countdown;
 *  - unattended work the user started — an SFTP transfer, a runbook step — defers the lock while it
 *    runs, because locking closes sessions and would take the work with it, but for at most
 *    [MAX_DEFERRAL_WINDOWS] idle windows of continuous absence, and never past [MAX_DEFERRAL_MS].
 *    Worst case is therefore one countdown plus the allowance — eleven intervals, not ten — which
 *    is why the Settings caption states the bound instead of leaving the threshold to imply it;
 *  - nothing else holds the lock off. Output streaming from a session, a framebuffer repainting or
 *    a tunnel carrying traffic are the remote end being busy, not the user being present.
 */
class IdleLockPolicy(private val idleMs: Long) {

    init {
        require(idleMs > 0) { "idle threshold must be positive; 'no auto-lock' is a null policy, not a zero one" }
    }

    /** How often [onTick] must be called. */
    val tickMs: Long = (idleMs / TICKS_PER_WINDOW).coerceIn(minOf(MIN_TICK_MS, idleMs), idleMs)

    /** Input seen since the last tick. Plain field: written from input handlers, read on the tick. */
    private var touched = false

    private var quietMs = 0L

    /**
     * How long work in flight may hold the lock off for this threshold: [MAX_DEFERRAL_WINDOWS] of
     * the user's own windows, and never past the absolute [MAX_DEFERRAL_MS].
     */
    private val maxDeferralMs: Long = (idleMs * MAX_DEFERRAL_WINDOWS).coerceAtMost(MAX_DEFERRAL_MS)

    /** How long work in flight has already held the lock off since the last sign of the user. */
    private var deferredMs = 0L

    /**
     * Start the countdown from now. Called when the timer starts running rather than on every tick:
     * a policy outlives the unlocked session it was made for (the threshold it was built from hasn't
     * changed), and one that locked once already stands at the threshold — without this, the vault
     * unlocked again would lock a tick later, mid-password-still-warm.
     */
    fun restart() {
        touched = false
        quietMs = 0
        deferredMs = 0
    }

    /** Record user activity — restarts the countdown at the next tick. */
    fun touch() {
        touched = true
    }

    /**
     * One step of the countdown.
     *
     * @param workInFlight whether unattended work the user started is running right now.
     * @return whether the vault must lock now.
     */
    fun onTick(workInFlight: Boolean): Boolean {
        if (touched) {
            touched = false
            quietMs = 0
            deferredMs = 0
            return false
        }
        quietMs += tickMs
        if (quietMs < idleMs) return false
        // Deferred, not cancelled: the countdown starts over, so the vault locks a full idle window
        // after the work ends rather than the instant it does. Bounded by [maxDeferralMs], since
        // "still working" is a claim made by the other end of the connection.
        if (workInFlight && deferredMs < maxDeferralMs) {
            deferredMs += quietMs
            quietMs = 0
            return false
        }
        return true
    }
}

/**
 * Tells pointer events made by a hand from the ones Compose makes up.
 *
 * After a relayout Compose re-sends the last mouse event as a [PointerEventType.Move] at its
 * unchanged position, so that a control sliding under a resting cursor still gets its hover
 * (`SyntheticEventSender.updatePointerPosition`). A session printing output relayouts on every
 * batch: counting those would let a chatty host answer "still here" for an empty desk, and the
 * vault would never lock again.
 *
 * The re-sent move is recognised by its position — it carries the one the previous event had.
 * `positionChanged()` cannot be used for this: a pointer with no button down is not tracked between
 * events (`PointerInputChangeEventProducer.produce`), so every hover move reports its own position
 * as the previous one and claims nothing moved. Only mouse pointers are re-sent, so only they are
 * compared; a finger reporting the same position twice is a finger held still on the glass, which
 * is a person.
 */
internal class PointerActivity {

    private var lastMouseAt: Offset? = null

    fun isFromUser(event: PointerEvent): Boolean {
        // Nothing but a lone mouse pointer is ever re-sent, and the remembered position survives
        // everything else: forgetting it on a stray touch event would hand the next re-sent move
        // through as a person.
        val at = event.changes.singleOrNull()?.takeIf { it.type == PointerType.Mouse }?.position ?: return true
        val resent = event.type == PointerEventType.Move && at == lastMouseAt
        lastMouseAt = at
        return !resent
    }
}

/**
 * Feeds user input in this subtree to [policy] — keys on the way down, and pointer events (press,
 * movement, wheel) observed on [PointerEventPass.Initial] without consuming them, so children still
 * receive both.
 *
 * Presses alone are not activity: typing into a terminal for five minutes without touching the
 * pointer used to be indistinguishable from an empty desk, and the vault locked on top of a live
 * session (issue #291). A soft keyboard sends neither — that input arrives through
 * [app.skerry.ui.app.LocalUserActivity].
 *
 * [policy] `null` (auto-lock off) makes this a no-op modifier.
 */
fun Modifier.idleActivity(policy: IdleLockPolicy?): Modifier =
    if (policy == null) {
        this
    } else {
        this
            .onPreviewKeyEvent { policy.touch(); false }
            .pointerInput(policy) {
                val pointer = PointerActivity()
                awaitPointerEventScope {
                    while (true) {
                        if (pointer.isFromUser(awaitPointerEvent(PointerEventPass.Initial))) policy.touch()
                    }
                }
            }
    }
