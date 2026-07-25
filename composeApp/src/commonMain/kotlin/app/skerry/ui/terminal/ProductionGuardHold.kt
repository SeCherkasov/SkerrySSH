package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.guard.GuardedCommand
import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.guard.ProductionGuardPolicy

/** Where a held input block came from — it is replayed exactly the way that path would send it. */
enum class HeldInputSource { Typed, Command, Paste }

/** An input block that was held back, ready to be replayed by the path it came from. */
data class HeldInput(val text: String, val from: HeldInputSource)

/**
 * The production guard's hold/confirm/dismiss state for one terminal: what is waiting for the user's
 * answer, and the input to replay once they give it.
 *
 * Split out of [TerminalScreenState] because every input path has to obey the same two rules, and
 * they are easy to get subtly wrong when spelled out three times:
 *
 * 1. **One at a time.** While something is held, NOTHING else runs — not a second risky command, not
 *    a harmless one. Confirming a dialog that shows command A must never run command B, and the user
 *    is answering a question about A. What arrives meanwhile is dropped, not queued: a command that
 *    runs minutes later, after the dialog is gone, is its own kind of surprise.
 * 2. **Classification is the last step.** The rules above are decided before a candidate is ever
 *    classified, so a path can't leak an input block just because it happened to look harmless.
 *
 * The caller owns everything terminal-specific (what the candidates are, how to replay them); this
 * only decides whether the input is held.
 */
@Stable
class ProductionGuardHold {

    /** What the guard asks about in this session. [ProductionGuardPolicy.Off] — no guard at all. */
    var policy: ProductionGuardPolicy by mutableStateOf(ProductionGuardPolicy.Off)

    /** Command awaiting the user's confirmation; `null` when nothing is pending. */
    var pending: GuardedCommand? by mutableStateOf(null)
        private set

    private var held: HeldInput? = null

    /**
     * Whether [text] is held back instead of being sent. [candidates] is evaluated only when it can
     * matter — it reads the screen and the tracked line, which is wasted work on a session with no
     * guard.
     *
     * `true` means the caller must send nothing: either the input is now waiting for confirmation,
     * or it was dropped because something else already is.
     */
    fun hold(text: String, from: HeldInputSource, candidates: () -> List<String>): Boolean {
        if (!policy.production) return false
        if (pending != null) return true
        val guarded = ProductionGuard.inspect(candidates(), policy) ?: return false
        pending = guarded
        held = HeldInput(text, from)
        return true
    }

    /**
     * Take the held input to replay it, clearing the hold in the same step. `null` when nothing is
     * held — a confirmation that arrives twice (double click, a stray Enter) replays nothing.
     */
    fun take(): HeldInput? {
        val current = held ?: return null
        pending = null
        held = null
        return current
    }

    /** Drop what is held: the user said no, or the session is being torn down. */
    fun dismiss() {
        pending = null
        held = null
    }
}
