package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import app.skerry.shared.guard.GuardedCommand
import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.ui.design.MAX_DRAWN_COMMAND_CHARS

/** Where a held input block came from — it is replayed exactly the way that path would send it. */
enum class HeldInputSource { Typed, Command, Paste }

/** An input block that was held back, ready to be replayed by the path it came from. */
data class HeldInput(val text: String, val from: HeldInputSource)

/**
 * What the client only guesses is on the shell's line, when it knows it holds the beginning of it.
 *
 * [classify] is the closest thing to what will run — what the client tracked joined to whatever this
 * input adds — and exists to find a reason. [onLine] is what the shell really has as far as this
 * knows, and is the only half a dialog may draw: the join is a string neither side holds, and
 * captioning it "already on the line" would state that the shell holds text nobody put there.
 *
 * [onLine] is null once the two have parted company — the shell completed the line and something was
 * typed onto it afterwards, so what is tracked is neither the shell's line nor a beginning of it.
 * The danger is still in it and it is still classified; there is simply nothing truthful to draw.
 */
data class PartialGuess(val classify: String, val onLine: String?)

/**
 * A line drawn beside the quote because the quote does not carry it: what the shell's line already
 * holds ([onLine]), or a line of the input itself from further in than the dialog can draw.
 *
 * [length] is what it was before the classifier's own cut — a shell line on a wide terminal can be
 * longer than [app.skerry.shared.guard.MAX_GUARDED_COMMAND_LENGTH], and drawing a prefix of it as if
 * it were whole is the failure this dialog exists to prevent.
 */
data class GuardAside(
    val line: String,
    /** How long the line is in all — null when it is known to be a beginning and nothing knows of what. */
    val length: Int?,
    val onLine: Boolean,
)

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

    /**
     * What the confirmation has to quote: everything this input will run, up to what a dialog can
     * draw. [pending] is the single riskiest line of it, which is the right thing to explain a
     * reason about and the wrong thing to show on its own — a two-line paste held on one risky line
     * replayed both, and only one was ever on screen.
     *
     * Not always the whole input, and deliberately: a typed block is keystrokes, and what they run
     * is whatever the shell's line already held. The client only guesses at that (history recall and
     * remote line editing never reach the tracked line), so a tripped line this cannot carry goes to
     * [pendingAside], beside the quote rather than in place of it — and where there was nothing to
     * quote at all, that line becomes the quote, because then it really is everything that runs.
     * Showing a harmless command under a danger reason is the failure this change exists to remove.
     */
    var pendingQuote: String by mutableStateOf("")
        private set

    /**
     * How long the held input really is, which is what the dialog states when it cannot show all of
     * it. [pendingQuote] stops at [MAX_DRAWN_COMMAND_CHARS]; this does not. Null when the quote is
     * known to be partial and nothing knows by how much — a line the shell completed past what the
     * client tracked, where a count would be an invented one.
     */
    var pendingQuoteLength: Int? by mutableStateOf(0)
        private set

    /**
     * The line that tripped the guard, when the quote does not carry it — drawn beside the quote,
     * never in its place. Null when the quote already shows it, which is the common case.
     *
     * Substituting it was the shape this had to stop being: a screen row is the host's to draw and
     * may never run at all, and a line from further into a block drew one short command in full
     * under a count of ten thousand characters, which reads as a cut line rather than as the four
     * hundred lines nobody saw.
     */
    var pendingAside: GuardAside? by mutableStateOf(null)
        private set

    // Volatile, unlike the four above: it is not snapshot state, so the batch that publishes them
    // does not carry it, and a hold made on a runbook's dispatcher has to be visible to the thread
    // that confirms it — [take] returning null there would leave the dialog on screen with a Confirm
    // that does nothing.
    @Volatile
    private var held: HeldInput? = null

    /**
     * One claim at a time. A runbook step holds from its own dispatcher while the user answers on
     * the UI thread: without this both can pass the "nothing pending" check and publish, leaving the
     * dialog describing one block and [take] replaying the other — and two snapshot applies over the
     * same state can conflict outright.
     */
    private val claim = SynchronizedObject()

    /**
     * Whether [text] is held back instead of being sent. Every lambda is evaluated only when it can
     * matter — [screenGuesses] reads the screen, which is wasted work on a session with no guard.
     *
     * [runLines] is what this input will actually run, line by line, and is what gets classified.
     * [partialGuess] is a line the client knows it holds only the beginning of — the shell completed
     * it. It is classified like the rest and may stand in for a blank quote, but the length then goes
     * out as null: what is drawn is a prefix, and no count over it would be true.
     *
     * [screenGuesses] is classified too and never becomes the quote: it is what the client only
     * *guesses* is on the shell line already — a row the host drew, or the tracked line joined to
     * this input — so building the quote from it would put a command in the dialog that this input
     * is not going to run. When a guess is what tripped the guard it is published as
     * [pendingAside] and drawn beside the quote as its own statement.
     *
     * [quote] is what to show — [text] itself for anything ready-made, and for keystrokes whatever
     * the caller knows the shell line already holds. It is not built from [runLines]: those are
     * capped in number to bound the classifier's work, so a quote built from them would drop the
     * lines past the cap, while
     * [take] replays every byte.
     *
     * `true` means the caller must send nothing: either the input is now waiting for confirmation,
     * or it was dropped because something else already is.
     */
    fun hold(
        text: String,
        from: HeldInputSource,
        quote: () -> String = { text },
        screenGuesses: () -> List<String> = { emptyList() },
        partialGuess: () -> PartialGuess? = { null },
        runLines: () -> List<String>,
    ): Boolean {
        if (!policy.production) return false
        // The claim and the publish are one step: two callers that both passed the "nothing pending"
        // check would leave the dialog describing one block and [take] replaying the other.
        return synchronized(claim) {
            if (pending != null) return@synchronized true
            claimAndPublish(text, from, quote, Guesses(screenGuesses(), partialGuess()), runLines())
        }
    }

    private fun claimAndPublish(
        text: String,
        from: HeldInputSource,
        quote: () -> String,
        guesses: Guesses,
        runLines: List<String>,
    ): Boolean {
        // Two lists, two classifications: [MAX_GUARDED_CANDIDATES] bounds the work per list, and
        // concatenating them let a prompt row on screen push the last line of a full-length paste out
        // of the classification — the end of a script is where its cleanup lives.
        val own = ProductionGuard.inspect(runLines, policy)
        val seen = ProductionGuard.inspect(guesses.screen, policy)
        // A prefix of the shell's line is classified beside what was read off the screen, and kept
        // apart from it: it is text the client tracked, not text the host drew, and it is by
        // definition not all of the line.
        val partial = guesses.partial
        val prefix = ProductionGuard.inspect(listOfNotNull(partial?.classify), policy)
        val guessed = ProductionGuard.worse(seen, prefix)
        val guarded = ProductionGuard.worse(own, guessed) ?: return false
        // A guess that won is drawn as the line itself, never as the join it was classified by. With
        // no drawable form there is nothing to put here: standing another finding in its place would
        // pair one line with another's reason, which is the shape this whole change removes.
        val found = Found(
            text = if (guarded === prefix) partial?.onLine else guarded.command,
            fromPrefix = guarded === prefix,
            fromScreen = guarded === guessed,
        )
        // Before the publish, not after: a Confirm that arrives between the two finds the question
        // and the block it is about together, rather than a dialog with nothing behind it.
        held = HeldInput(text, from)
        publish(guarded, willRun = quote().trimEnd(), found = found, partial = partial)
        return true
    }

    /**
     * The line that tripped the guard as it may be *drawn*, and which of the three sources it came
     * from. Null when nothing may be: the classifier found its reason in a join of what the client
     * tracked and what this input adds, and that string is one neither the shell nor the input
     * holds. A dialog then quotes what is being sent and nothing else — a reason with no line under
     * it is a poor dialog, and a line nobody will run is a wrong one.
     */
    private data class Found(val text: String?, val fromPrefix: Boolean, val fromScreen: Boolean)

    /** What the client only guesses about the shell's line: what the screen shows, and what it tracked. */
    private data class Guesses(val screen: List<String>, val partial: PartialGuess?)

    /**
     * The four facts the dialog reads, published together. A recomposition that saw [pending] set
     * before the quote was written would draw the dialog with nothing to quote.
     *
     * [found] is the line that tripped the guard as it may be *drawn* — for a guess about a line the
     * client only holds the beginning of, that is the beginning and not the join it was classified
     * by. A quote that cannot carry it gets it beside itself instead of in place of it.
     */
    private fun publish(guarded: GuardedCommand, willRun: String, found: Found, partial: PartialGuess?) {
        val shown = willRun.take(MAX_DRAWN_COMMAND_CHARS)
        // One case, and one only: nothing to quote. A bare Enter over a line the client never saw
        // runs what the shell holds, so that line is the quote and there is nothing else to show.
        val standIn = found.text?.takeIf { shown.isBlank() }
        // What the shell already holds comes first, whoever tripped the guard: the quote cannot claim
        // a line the client is only guessing at, and leaving it out of the dialog draws a service
        // restart over an `rm -rf` that runs before it. It carries no length — the client knows it
        // holds a beginning, not how much more there is.
        val onLine = partial?.onLine
        val aside = when {
            standIn != null -> null
            // The line the reason is about comes first: there is one block, and a dialog explaining a
            // recursive delete beside an unrelated fragment names neither.
            found.text != null && !shown.contains(found.text) ->
                GuardAside(found.text, guarded.fullLength.takeIf { !found.fromPrefix }, found.fromScreen)
            onLine != null && !shown.contains(onLine) -> GuardAside(onLine, null, onLine = true)
            else -> null
        }
        // One snapshot for all four: a recomposition that saw `pending` before the quote was written
        // would draw the dialog with nothing in it.
        Snapshot.withMutableSnapshot {
            pending = guarded
            // Blank is empty here: whitespace is not something to read, and the dialog decides what
            // to draw on the same question this decides the length on.
            pendingQuote = standIn ?: shown.takeUnless { it.isBlank() }.orEmpty()
            pendingAside = aside
            // A blank quote is a bare Enter over a line the client never saw, and then the tripped
            // line really is all there is — counting the input's own length would name characters the
            // user is not being told about. A prefix has no count at all behind it.
            pendingQuoteLength = when {
                shown.isNotBlank() -> willRun.length
                found.fromPrefix -> null
                else -> guarded.fullLength
            }
        }
    }

    /**
     * Take the held input to replay it, clearing the hold in the same step. `null` when nothing is
     * held — a confirmation that arrives twice (double click, a stray Enter) replays nothing.
     */
    fun take(): HeldInput? = synchronized(claim) {
        val current = held ?: return@synchronized null
        pending = null
        pendingQuote = ""
        pendingQuoteLength = 0
        pendingAside = null
        held = null
        current
    }

    /** Drop what is held: the user said no, or the session is being torn down. */
    fun dismiss() = synchronized(claim) {
        pending = null
        pendingQuote = ""
        pendingQuoteLength = 0
        pendingAside = null
        held = null
    }
}
