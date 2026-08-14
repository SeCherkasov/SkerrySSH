package app.skerry.shared.guard

import app.skerry.shared.ai.CommandAssessment
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.ai.CommandRiskClassifier
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.tag.PROD_TAG

/**
 * One candidate for the classifier: the text it may read — already cut to
 * [MAX_GUARDED_COMMAND_LENGTH] — and how long the line really is. Carried separately because the
 * dialog states the length, and measuring after the cut called a 900-character line "512 chars".
 */
data class GuardCandidate(val command: String, val fullLength: Int)

/**
 * Longest command text the guard inspects. A candidate can come from a screen row, which may hold
 * program output rather than a command; truncating keeps a pathological line out of the regex
 * engine. Long enough for any realistic one-liner.
 */
const val MAX_GUARDED_COMMAND_LENGTH = 512

/**
 * How many candidates one inspection classifies. A paste can carry a whole log file, and every line
 * of it would otherwise run through ~30 regexes on the caller's thread. Anything past this many
 * lines is not a command someone meant to run — it is a document that landed in a terminal.
 */
const val MAX_GUARDED_CANDIDATES = 200

/** A command that needs confirmation on a production host, with the reason to show the user. */
data class GuardedCommand(
    val command: String,
    val assessment: CommandAssessment,
    /**
     * How long the candidate was before [MAX_GUARDED_COMMAND_LENGTH] cut it for the classifier. A
     * dialog quoting [command] states this instead of what it drew: a shell line on a wide terminal
     * can be longer than the classifier reads, and a partial command presented as whole is the one
     * thing the confirmation exists to prevent.
     */
    val fullLength: Int = command.length,
)

/**
 * What the guard asks about in one session.
 *
 * [production] — the host carries [PROD_TAG]; everything else is off without it.
 * [confirmWarnings] — the user opted into confirming [CommandRisk.Warn] as well (Settings →
 * Terminal). Off by default: `sudo` is a Warn, and on a production box it is half the commands
 * typed — a dialog that frequent gets clicked through without reading, which is worse than no
 * dialog at all.
 * [rootLogin] — the session logs in as root. That flips two things: `sudo` stops meaning anything
 * (nobody types it, so the rule is pure noise), while a destructive Warn — `rm`, `git reset
 * --hard`, `find -delete` — has no sudo step in front of it any more, so it is confirmed even
 * when [confirmWarnings] is off.
 */
data class ProductionGuardPolicy(
    val production: Boolean = false,
    val confirmWarnings: Boolean = false,
    val rootLogin: Boolean = false,
) {
    companion object {
        /** No guard at all — a non-production session. */
        val Off = ProductionGuardPolicy()
    }
}

/**
 * Production guard: on a host tagged [PROD_TAG] a risky command is confirmed before it reaches the
 * shell, connecting asks first, and the session is marked red.
 *
 * Reuses [CommandRiskClassifier] (static rules, no AI). Threshold here is lower than in the AI bar:
 * anything above [CommandRisk.None] — including Warn (`sudo`, `systemctl stop`, `kill`) — is
 * confirmed, because on production the cost of a wrong command outweighs the friction of one extra
 * keypress.
 *
 * Not a security boundary: the guard sees what the client sends, so shell aliases, scripts, and
 * variable indirection pass through. It is a "wrong window" guard — the classic footgun is a
 * command typed into the production tab that was meant for staging.
 */
object ProductionGuard {

    /**
     * Characters that end a shell prompt — `$`/`#`/`%`/`>` and the usual powerline/starship arrows. Exposed because the syntax highlighter needs the same set
     * for its allocation-free prescan: a second hand-written copy would silently drift from this one
     * the day a prompt glyph is added, and rows the guard cuts would stop being highlighted.
     */
    const val PROMPT_TERMINATORS = "$#%>❯➜»›"

    private val PROMPT_TERMINATOR = Regex("[$PROMPT_TERMINATORS]\\s")

    /**
     * Index just past the prompt in [line], or 0 when it doesn't look like a prompt at all. Shared
     * with the syntax highlighter, which needs the *position* of the cut rather than the text after
     * it — one heuristic, so the guard and the highlighter never disagree on where a command starts.
     *
     * The cut is at the FIRST terminator, not the last: `cat img > /dev/sda` contains a `>` of its
     * own, and cutting there would leave `/dev/sda` and lose the very command worth stopping.
     */
    fun promptEnd(line: String): Int = PROMPT_TERMINATOR.find(line)?.let { it.range.last + 1 } ?: 0

    /** Whether [tags] make a host production (carries [PROD_TAG]). */
    fun isProduction(tags: List<String>): Boolean = PROD_TAG in tags

    /**
     * The riskiest of [candidates] that needs confirmation, or `null` if none does. Several
     * candidates exist because the client only guesses at the command: the locally tracked typed
     * line and the line read off the screen can disagree (history recall, remote line editing), and
     * missing a dangerous command is worse than confirming a harmless one — so the worst wins.
     *
     * Ties go to the shortest candidate: the same command shows up with and without its prompt
     * prefix, and the dialog should quote what was run, not `root@host:~# …`.
     */
    fun inspect(candidates: List<String>, policy: ProductionGuardPolicy): GuardedCommand? =
        inspectCandidates(
            candidates.map { candidate ->
                val whole = candidate.trim()
                GuardCandidate(whole.take(MAX_GUARDED_COMMAND_LENGTH), fullLength = whole.length)
            },
            policy,
        )

    /**
     * [inspect] for candidates that already know their uncut length — what [candidatesOf] returns.
     * A separate name, not an overload: both take a `List`, and the JVM cannot tell the two apart.
     */
    fun inspectCandidates(candidates: List<GuardCandidate>, policy: ProductionGuardPolicy): GuardedCommand? {
        if (!policy.production) return null
        var worst: GuardedCommand? = null
        for (candidate in candidates.take(MAX_GUARDED_CANDIDATES)) {
            val command = candidate.command
            if (command.isEmpty()) continue
            val assessment = CommandRiskClassifier.assess(command)
            if (!needsConfirmation(assessment, policy)) continue
            worst = worse(worst, GuardedCommand(command, assessment, fullLength = candidate.fullLength))
        }
        return worst
    }

    /**
     * The finding for input the classifier could not fully read: the first non-blank line past the
     * candidate cap ([MAX_GUARDED_CANDIDATES]), or the first line longer than what is classified
     * ([MAX_GUARDED_COMMAND_LENGTH]). On a production host, exceeding what the classifier reads is
     * itself worth a question — text past the caps used to run with no dialog at all. Consulted only
     * when no rule found a reason ([ProductionGuardHold]): a real finding explains more than "part
     * of this was not read", and one confirmation is asked either way.
     *
     * The command is the offending line as far as a dialog may quote it; the assessment carries no
     * rule's reason, because no rule ever saw the text — which is exactly what the user is asked
     * about.
     */
    fun overflow(text: String, policy: ProductionGuardPolicy): GuardedCommand? {
        if (!policy.production) return null
        var index = 0
        for (line in text.lineSequence()) {
            index++
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // The scan is bounded, not only the finding: a run of blank lines would otherwise
                // turn this into a second full pass over a multi-megabyte paste on the caller's
                // thread. Past the bound the tail is unread by definition — the very thing this
                // asks about — so it errs toward asking, with nothing to quote.
                if (index > MAX_OVERFLOW_SCAN) return GuardedCommand("", BEYOND_INSPECTION, fullLength = 0)
                continue
            }
            if (index > MAX_GUARDED_CANDIDATES || trimmed.length > MAX_GUARDED_COMMAND_LENGTH) {
                return GuardedCommand(
                    trimmed.take(MAX_GUARDED_COMMAND_LENGTH),
                    BEYOND_INSPECTION,
                    fullLength = trimmed.length,
                )
            }
        }
        return null
    }

    /** How far [overflow] reads before giving up on finding a non-blank line to quote. */
    private const val MAX_OVERFLOW_SCAN = MAX_GUARDED_CANDIDATES * 2

    /**
     * Danger, because a block nobody could read is confirmed whatever the settings say —
     * [needsConfirmation] never sees it, so the level must clear every bar on its own.
     */
    private val BEYOND_INSPECTION =
        CommandAssessment(CommandRisk.Danger, CommandRiskReason.BeyondInspection)

    /**
     * The riskier of two findings by the same rule [inspect] ranks its candidates with. Public
     * because candidates come from lists that must not share one cap — what a block will run is
     * capped for the classifier's sake, and a guess read off the screen may not spend that budget.
     */
    fun worse(current: GuardedCommand?, other: GuardedCommand?): GuardedCommand? {
        if (other == null) return current
        if (current == null) return other
        val better = other.assessment.risk.ordinal > current.assessment.risk.ordinal ||
            (other.assessment.risk == current.assessment.risk && other.command.length < current.command.length)
        return if (better) other else current
    }

    /** Single-candidate form: broadcast lines and snippets are known verbatim. */
    fun inspect(command: String, policy: ProductionGuardPolicy): GuardedCommand? =
        inspect(listOf(command), policy)

    /**
     * Candidates from one input block (a paste, an IME commit, a ready-made command). Capped in
     * number while splitting rather than after — a multi-megabyte paste would otherwise materialize
     * a String per line before [inspectCandidates] ever gets to drop them. Capped in length here as
     * well: a paste is not bounded by anything, and holding it a second time in full to keep the
     * text past the cut for a line nobody quotes from is the wrong trade. What survives the cut is
     * the line's real length, carried in [GuardCandidate.fullLength] so the dialog's count is not an
     * invention; what the screen holds is bounded by the terminal and stays uncut in
     * [promptCandidates].
     */
    fun candidatesOf(text: String): List<GuardCandidate> =
        text.lineSequence()
            .take(MAX_GUARDED_CANDIDATES)
            .map { line ->
                val whole = line.trim()
                GuardCandidate(whole.take(MAX_GUARDED_COMMAND_LENGTH), fullLength = whole.length)
            }
            .toList()

    /**
     * Whether [assessment] crosses the bar set by [policy]. Danger always does. Warn does when the
     * user asked for it, or when the session is root and the command destroys data — see
     * [ProductionGuardPolicy] for why root changes the answer. `sudo` under root is dropped
     * entirely: it says nothing about a session that is already root.
     */
    private fun needsConfirmation(assessment: CommandAssessment, policy: ProductionGuardPolicy): Boolean =
        when (assessment.risk) {
            CommandRisk.None -> false
            CommandRisk.Danger -> true
            CommandRisk.Warn -> when {
                policy.rootLogin && assessment.reason == CommandRiskReason.Elevated -> false
                policy.confirmWarnings -> true
                else -> policy.rootLogin && assessment.destructive
            }
        }

    /**
     * Command candidates from a raw screen line: the line itself plus, when it looks like a prompt,
     * the tail after the prompt ([promptEnd]). Both are kept — cutting is a guess, and the uncut
     * line still holds the command.
     */
    fun promptCandidates(rawLine: String): List<String> {
        val line = rawLine.trim()
        if (line.isEmpty()) return emptyList()
        val cut = promptEnd(line)
        if (cut == 0) return listOf(line)
        val tail = line.substring(cut).trim()
        return if (tail.isEmpty() || tail == line) listOf(line) else listOf(line, tail)
    }
}
