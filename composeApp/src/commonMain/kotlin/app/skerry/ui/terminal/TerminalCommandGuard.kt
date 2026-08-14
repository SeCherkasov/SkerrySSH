package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.terminal.AutocompleteEngine
import app.skerry.shared.terminal.isSafeTerminalInputChar
import app.skerry.ui.snippet.maskSecrets

/** Ctrl-O — accept-line-and-down-history: it runs the line and recalls the next entry into it. */
internal const val ACCEPT_LINE_AND_DOWN = '\u000F'

/**
 * What can make the shell run the line it is holding: Enter in both forms, plus readline's
 * accept-line-and-down-history (Ctrl-O), which the mobile keybar reaches as ctrl + "/".
 */
internal val RUN_LINE_CONTROLS = charArrayOf('\r', '\n', ACCEPT_LINE_AND_DOWN)

/**
 * The terminal-specific half of the production guard, next to [ProductionGuardHold] — which owns
 * the hold/confirm/dismiss rules and knows nothing about terminals. What lives here is what a
 * terminal contributes to the question: which candidates one input block offers, what may be
 * quoted for it, what the screen adds, and what a block sent past the typed path did to the
 * tracked line. It needs only the tracked line (the engine), the cursor row and the alt-screen
 * flag; everything else — replaying held input, secrets, history — stays with the session.
 */
internal class TerminalCommandGuard(
    private val engine: AutocompleteEngine,
    private val altScreen: () -> Boolean,
    /** Visible cursor row up to the cursor column — the shell line as the user sees it. */
    private val lineToCursor: () -> String,
    /** The whole visible cursor row, trailing blanks trimmed. */
    private val rowText: () -> String,
    /** Whether the cursor row carries text at or right of the cursor — the line continues past it. */
    private val lineContinues: () -> Boolean,
) {
    /** Hold/confirm/dismiss state; the dialog reads its `pending*` fields through the session. */
    val hold = ProductionGuardHold()

    /**
     * Holds a typed input block when it would run a risky command; `true` means nothing may be sent.
     *
     * Only an input block containing Enter can run something, so that is the only thing held —
     * typing itself stays live (a half-typed line the user is still editing must keep echoing).
     * Alt-screen is exempt: inside vim/htop there is no shell line, and Enter is not "run this".
     */
    fun holdTyped(text: String): Boolean {
        if (altScreen()) return false
        if (text.none { it in RUN_LINE_CONTROLS }) return false
        return holdInput(text, HeldInputSource.Typed)
    }

    /**
     * Holds a ready-made command (snippet, palette, an AI-confirmed line). Unlike typed input the
     * command is known verbatim, but it still lands on whatever the line already holds — which is
     * what [lineGuess] covers. [secrets] are the resolved vault values the input carries: they are
     * masked in everything the dialog draws, and in nothing that is classified or replayed.
     */
    fun holdCommand(text: String, secrets: List<String> = emptyList()): Boolean =
        holdInput(text, HeldInputSource.Command, secrets)

    /**
     * Holds a paste that would run: one carrying a newline runs the moment it lands, while one
     * without only fills the shell line — the Enter that would run it is guarded as typed input.
     */
    fun holdPaste(text: String): Boolean {
        if (!hold.policy.production) return false
        if (text.none { it == '\n' || it == '\r' }) return false
        return holdInput(text, HeldInputSource.Paste)
    }

    private fun holdInput(text: String, from: HeldInputSource, secrets: List<String> = emptyList()): Boolean =
        hold.hold(
            text,
            from,
            quote = quotedInput(text),
            present = maskOf(secrets),
            screenGuesses = { screenCandidates() },
            screenLineCut = { lineContinues() },
            partialGuess = { lineGuess(text) },
        ) { policy -> ProductionGuard.inspectCandidates(ProductionGuard.candidatesOf(text), policy) }

    /**
     * What the client only *guesses* an input will run, as opposed to the lines it carries itself:
     * the shell's line as the screen draws it, and the tracked line joined to the block's first
     * line. Classified beside the block, never inside its budget — sharing one cap let a guess push
     * the last line of a full-length paste out of the classification, and the end of a script is
     * where its cleanup lives.
     *
     * Nothing off the alternate screen: inside vim or htop the cursor row is a line of a file, not a
     * command, and classifying it holds a paste against text the user is only looking at.
     *
     * The join exists because a snippet fired onto a half-typed `rm -rf ` runs `rm -rf /srv`, and
     * because the classifier's patterns are word-anchored — a tracked line ending in a word joins
     * into `xyzrm -rf /srv` and reads as harmless, so the block's own first line is offered too
     * ([ProductionGuard.candidatesOf]). The join is classified whatever the client still believes
     * about the line; what belief decides is whether any of it may be *drawn* — see [PartialGuess].
     * A line that wrapped onto another row is beyond all of this.
     */
    private fun lineGuess(text: String): PartialGuess? {
        if (altScreen()) return null
        val onLine = engine.currentLine
        // Whatever the client tracked, trusted or not: the classifier needs the join either way — a
        // snippet fired onto a half-typed `rm -rf ` runs `rm -rf /srv`, and the block's own lines
        // say nothing about that. What being trusted decides is only whether the quote can claim it;
        // a line it cannot claim is drawn beside the quote instead, never as it.
        if (onLine.isEmpty()) return null
        // The controls that run a line are cut off the end, as [quotedInput] cuts them: a candidate
        // carrying one is a candidate no quote can contain, and the dialog would draw it as a second
        // line with the control spelled out.
        val first = text.lineSequence().firstOrNull().orEmpty()
        val join = (onLine + first).trimEnd { it in RUN_LINE_CONTROLS }
        // A bare Enter over a completed line leaves the prefix as the whole candidate, and a prefix
        // the screen already carries says nothing the row does not — it would say it worse, since
        // candidates tie on risk by length and the prefix would win and be quoted: `rm -rf /sr` for
        // a line reading `rm -rf /srv/prod-db`. A block that carries text of its own is a different
        // thing: the join is then the only candidate equal to what will run.
        if (join == onLine && screenCandidates().any { it.contains(onLine) }) return null
        // Drawable only while the shell has merely appended to it: once something is typed onto a
        // completed line the two have parted company, and what is tracked is a string neither holds.
        return PartialGuess(classify = join, onLine = onLine.takeIf { !engine.lineSuspect || engine.linePartial })
    }

    /**
     * What the screen says is on the shell's line: the row up to the cursor, and the whole line —
     * soft-wrap joined — as well. The whole line is what actually runs after a history recall with
     * the cursor stepped back inside it (reading only up to the cursor confirmed `…prod-` while
     * `…prod-db` ran), and after a recall that wrapped, where the head of the line sits on the row
     * ABOVE the cursor and the cursor row alone never carries the risk. Nothing off the alternate
     * screen: inside vim or htop the cursor row is a line of a file, not a command.
     */
    private fun screenCandidates(): List<String> {
        if (altScreen()) return emptyList()
        val toCursor = ProductionGuard.promptCandidates(lineToCursor())
        return (toCursor + ProductionGuard.promptCandidates(rowText())).distinct()
    }

    /**
     * Follows what [text] — sent to the PTY without passing through the typed path — did to the
     * shell's line, in the caller's own order: the guard reads the tracked line on the way to the
     * next send.
     *
     * A block carrying a run-line control ran: what is left on the line is the tail after the last
     * one, and nothing at all after Ctrl-O, which pulls the next history entry in. Anything else
     * merely landed on the line — the assistant's Edit, the key panel's Esc and arrows — and the
     * engine models those bytes better than this can: it clears on Esc, backs up on a backspace,
     * and marks the line suspect for the edits it cannot follow.
     */
    fun trackSent(text: String) {
        val ran = text.indexOfLast { it in RUN_LINE_CONTROLS }
        when {
            ran >= 0 && text[ran] == ACCEPT_LINE_AND_DOWN -> engine.lineRanElsewhere(null)
            ran >= 0 -> engine.lineRanElsewhere(text.substring(ran + 1))
            else -> engine.onUserInput(text.encodeToByteArray())
        }
    }

    /**
     * The same thing to quote in the confirmation, with the controls that make it run cut off the
     * end — a `\r` or the mobile keybar's Ctrl-O is what sends the line, not a character of it.
     *
     * Only the prefix is dropped when the tracked line is known to be stale — a control byte the
     * engine cannot replay (Ctrl-W, Ctrl-K) leaves it holding text the shell no longer has. What
     * arrived now is still known verbatim and still runs, so it is still quoted; for a typed block
     * that is a bare `\r` and the quote comes out empty, which is what makes the guard fall back to
     * the line it tripped on.
     */
    private fun quotedInput(text: String): () -> String {
        // The line is read here, beside [lineGuess]'s own read, and joined only if the guard finds
        // something. Reading it again after the classification would let the two disagree — the
        // dialog would explain a reason found for one line while quoting another — and joining it
        // eagerly would build two copies of a multi-megabyte paste that turns out to be harmless.
        val onLine = if (engine.lineSuspect) "" else engine.currentLine
        return { (onLine + text).trimEnd { c -> c in RUN_LINE_CONTROLS } }
    }

    /**
     * The command the shell finished for a line this only holds the beginning of. Read off the
     * screen because that is the only place it exists, and taken only when it starts with what was
     * actually typed: the row is the host's to draw, and a line that wrapped leaves nothing but its
     * tail on it. Recording nothing is what the engine would have done; recording the row itself
     * would put a command nobody ran into the host's stored history and offer it back as a
     * suggestion, on this host and in the palette across all of them — which draws what it stores,
     * so a row carrying a bidi override would read there as a command it is not.
     */
    fun completedLine(typed: String): String? {
        // Same on the alternate screen: the cursor row is a line of a file, and a file's line is not
        // a command that ran.
        if (typed.isEmpty() || altScreen()) return null
        return ProductionGuard.promptCandidates(lineToCursor())
            .lastOrNull()
            // Strictly longer: a Tab whose completion has not echoed leaves the row reading exactly
            // what was typed, and that is the prefix rather than a command anyone ran.
            ?.takeIf { it.length > typed.length && it.startsWith(typed) && it.all(::isSafeTerminalInputChar) }
    }

    /**
     * How input carrying resolved vault [secrets] may be drawn: every secret span replaced with the
     * same mask the snippet dialog previewed one step earlier. Applied only to what the dialog
     * shows — the classifier reads the real text, and Confirm replays the real bytes.
     */
    private fun maskOf(secrets: List<String>): (String) -> String {
        if (secrets.none { it.isNotBlank() }) return { it }
        return { text -> maskSecrets(text, secrets) }
    }
}
