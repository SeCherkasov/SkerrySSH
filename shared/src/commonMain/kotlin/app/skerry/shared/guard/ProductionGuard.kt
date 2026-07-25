package app.skerry.shared.guard

import app.skerry.shared.ai.CommandAssessment
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.ai.CommandRiskClassifier
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
    private val PROMPT_TERMINATOR = Regex("""[$#%>❯➜»›]\s""")

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
    fun inspect(candidates: List<String>): GuardedCommand? {
        var worst: GuardedCommand? = null
        for (candidate in candidates.take(MAX_GUARDED_CANDIDATES)) {
            val command = candidate.trim().take(MAX_GUARDED_COMMAND_LENGTH)
            if (command.isEmpty()) continue
            val assessment = CommandRiskClassifier.assess(command)
            if (assessment.risk == CommandRisk.None) continue
            val current = worst
            val better = current == null ||
                assessment.risk.ordinal > current.assessment.risk.ordinal ||
                (assessment.risk == current.assessment.risk && command.length < current.command.length)
            if (better) worst = GuardedCommand(command, assessment)
        }
        return worst
    }

    /** Single-candidate form: broadcast lines and snippets are known verbatim. */
    fun inspect(command: String): GuardedCommand? = inspect(listOf(command))

    /**
     * Command candidates from a raw screen line: the line itself plus, when it looks like a prompt,
     * the tail after the prompt. Both are kept — cutting is a guess, and the uncut line still holds
     * the command.
     *
     * The cut is at the FIRST prompt terminator, not the last: `cat img > /dev/sda` contains a `>`
     * of its own, and cutting there would leave `/dev/sda` and lose the very command worth stopping.
     */
    fun promptCandidates(rawLine: String): List<String> {
        val line = rawLine.trim().take(MAX_GUARDED_COMMAND_LENGTH)
        if (line.isEmpty()) return emptyList()
        val match = PROMPT_TERMINATOR.find(line) ?: return listOf(line)
        val tail = line.substring(match.range.last + 1).trim()
        return if (tail.isEmpty() || tail == line) listOf(line) else listOf(line, tail)
    }
}
