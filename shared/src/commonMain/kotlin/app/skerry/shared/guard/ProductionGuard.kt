package app.skerry.shared.guard

import app.skerry.shared.ai.CommandAssessment
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.ai.CommandRiskClassifier
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.tag.PROD_TAG

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
data class GuardedCommand(val command: String, val assessment: CommandAssessment)

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
     * Prompt terminator: the `$`/`#`/`%`/`>` (and the usual powerline/starship arrows) followed by a
     * space that separates a shell prompt from what was typed after it.
     */
    /**
     * Characters that end a shell prompt. Exposed because the syntax highlighter needs the same set
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
    fun inspect(candidates: List<String>, policy: ProductionGuardPolicy): GuardedCommand? {
        if (!policy.production) return null
        var worst: GuardedCommand? = null
        for (candidate in candidates.take(MAX_GUARDED_CANDIDATES)) {
            val command = candidate.trim().take(MAX_GUARDED_COMMAND_LENGTH)
            if (command.isEmpty()) continue
            val assessment = CommandRiskClassifier.assess(command)
            if (!needsConfirmation(assessment, policy)) continue
            val current = worst
            val better = current == null ||
                assessment.risk.ordinal > current.assessment.risk.ordinal ||
                (assessment.risk == current.assessment.risk && command.length < current.command.length)
            if (better) worst = GuardedCommand(command, assessment)
        }
        return worst
    }

    /** Single-candidate form: broadcast lines and snippets are known verbatim. */
    fun inspect(command: String, policy: ProductionGuardPolicy): GuardedCommand? =
        inspect(listOf(command), policy)

    /**
     * Candidates from one input block (a paste, an IME commit, a ready-made command). Both caps are
     * applied while splitting, not after: a multi-megabyte paste would otherwise materialize a
     * String per line before [inspect] ever gets to drop them.
     */
    fun candidatesOf(text: String): List<String> =
        text.lineSequence()
            .take(MAX_GUARDED_CANDIDATES)
            .map { it.take(MAX_GUARDED_COMMAND_LENGTH) }
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
        val line = rawLine.trim().take(MAX_GUARDED_COMMAND_LENGTH)
        if (line.isEmpty()) return emptyList()
        val cut = promptEnd(line)
        if (cut == 0) return listOf(line)
        val tail = line.substring(cut).trim()
        return if (tail.isEmpty() || tail == line) listOf(line) else listOf(line, tail)
    }
}
