package app.skerry.shared.ai

/** Risk level of a proposed AI shell command. */
enum class CommandRisk { None, Warn, Danger }

/**
 * Command assessment: level plus a human-readable reason ([reason] is `null` only for [CommandRisk.None]).
 * [destructive] marks commands that lose/overwrite data (delete, overwrite, format): the UI colors it
 * red even at [CommandRisk.Warn] (non-destructive warns like sudo/kill stay amber).
 */
data class CommandAssessment(val risk: CommandRisk, val reason: String?, val destructive: Boolean = false) {
    companion object {
        val SAFE = CommandAssessment(CommandRisk.None, null)
    }
}

/**
 * Heuristic classifier for potentially dangerous commands in the terminal AI bar.
 *
 * "AI under policy" principle: model output is untrusted, so destructive commands are flagged
 * before confirmation. [CommandRisk.Danger] requires a separate arm-confirmation in the UI
 * ("Run anyway" -> "Confirm run"), [CommandRisk.Warn] shows a warning with a reason. Danger is
 * checked before Warn; first match wins.
 *
 * Not a sandbox or a full shell parser — a deliberately conservative heuristic catching classic
 * footguns. The user always has final say; false positives are preferred over misses.
 */
object CommandRiskClassifier {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // All regexes are compiled once at object init: assess() is called on every AI suggestion/input,
    // recompiling ~25 patterns per call would be pure waste.

    // ---------- DANGER ----------
    private val FORK_BOMB_ANON = rx(""":\s*\(\s*\)\s*\{[^}]*\|[^}]*&""")
    // A bare `}` outside a char class is tolerated by java.util.regex but throws
    // PatternSyntaxException on Android's ICU engine — must stay escaped.
    private val FORK_BOMB_NAMED = rx("""\b(\w+)\s*\(\s*\)\s*\{[^}]*\|[^}]*&[^}]*\}\s*;\s*\1\b""")
    private val RM_WORD = rx("""\brm\b""")
    private val RM_RECURSIVE = rx("""\s-\w*r|\s--recursive""")
    private val RM_FORCE = rx("""\s-\w*f|\s--force""")
    private val RM_BROAD_TARGET = rx("""\s(/|/\*|~|~/\*|${'$'}home|\*)(\s|;|&|\||$)""")
    private val DD_TO_DEVICE = rx("""\bdd\b.*\bof=/dev/""")
    private val DISK_TOOLS = rx("""\b(mkfs\S*|wipefs|fdisk|parted|shred|blkdiscard)\b""")
    private val REDIRECT_TO_DISK = rx(""">\s*/dev/(sd|nvme|vd|hd|mmcblk|disk|xvd)""")
    private val DOWNLOAD_TO_SHELL = rx("""\b(curl|wget|fetch)\b.*\|\s*(sudo\s+)?(sh|bash|zsh|ksh|dash|python\d?|perl|ruby|node)\b""")
    private val PIPE_TO_INTERPRETER = rx("""\|\s*(sudo\s+)?(sh|bash|zsh|ksh|dash|python\d?|perl|ruby|node)\b""")
    private val RSYNC_DELETE = rx("""\brsync\b.*(--delete|--del)\b""")
    private val POWER_OFF = rx("""\b(shutdown|reboot|poweroff|halt)\b""")
    private val INIT_0_6 = rx("""\binit\s+[06]\b""")
    private val SYSTEMCTL_POWER = rx("""\bsystemctl\s+(poweroff|reboot|halt)\b""")
    private val CHMOD_CHOWN_RECURSIVE = rx("""\bch(mod|own)\b.*\s-\w*r""")
    private val SYSTEM_PATH = rx("""\s(/|~|${'$'}home)(\s|$)""")
    private val REDIRECT_TO_SECURITY_FILE = rx(""">\s*\S*authorized_keys|>\s*/etc/(passwd|shadow|sudoers)""")
    private val TEE_TO_SECURITY_FILE = rx("""\btee\b\s+\S*(authorized_keys|/etc/(passwd|shadow|sudoers))""")
    private val FIREWALL_FLUSH = rx("""\biptables\b.*\s(-f|--flush)\b|\bufw\s+disable\b|\bnft\s+flush\b""")

    // ---------- WARN ----------
    private val RM_ANY = rx("""\brm\b\s+.*-\w*[rf]|\brm\b\s+[^-]""")
    private val KILL_PROCESS = rx("""\b(kill|killall|pkill)\b""")
    private val PKG_REMOVE = rx("""\b(apt|apt-get|dnf|yum|pacman|zypper|brew|pip\d?|npm)\b.*\b(remove|uninstall|purge|erase|autoremove)\b""")
    private val PACMAN_REMOVE = rx("""\bpacman\s+-r\w*""")
    private val GIT_DESTRUCTIVE = rx("""\bgit\b.*\b(reset\s+--hard|clean\s+-\w*f|push\s+.*(--force|-f)\b)""")
    private val CHMOD_777 = rx("""\bchmod\s+(-\w+\s+)?[0-7]*777\b""")
    private val SERVICE_STOP = rx("""\bsystemctl\s+(stop|disable|mask)\b|\bservice\s+\S+\s+stop\b""")
    private val FIND_DELETE = rx("""\bfind\b.*-delete|\bfind\b.*-exec\s+rm""")
    private val TRUNCATE_CONTENT = rx("""\btruncate\b|\bmv\b.*\s/dev/null\b|:\s*>\s*\S""")
    private val ELEVATED = rx("""\bsudo\b|\bsu\s""")

    // ---------- normalize ----------
    private val ESCAPED_CHAR = Regex("""\\(.)""")
    private val WHITESPACE_RUN = Regex("""\s+""")

    fun assess(command: String): CommandAssessment {
        val raw = command.trim()
        if (raw.isEmpty()) return CommandAssessment.SAFE
        // Normalize to how the shell would see it, closing the "regex != parser" bypass class:
        // strip escaping backslashes (r\m -\r\f -> rm -rf), expand $IFS/${'$'}{IFS} to a space
        // (rm${'$'}{IFS}-rf -> rm -rf), and drop quotes (rm -r "/" == rm -r /).
        val cmd = normalize(raw)
        val lower = cmd.lowercase()
        val compact = cmd.replace(" ", "")

        // ---------- DANGER ----------

        // Fork bomb :(){ :|:& };: — including a renamed variant (self-referencing function).
        if (compact.contains(":(){:|:&};:") ||
            FORK_BOMB_ANON.containsMatchIn(cmd) ||
            FORK_BOMB_NAMED.containsMatchIn(cmd)
        ) {
            return danger("Fork bomb — will exhaust system resources.")
        }

        // rm: recursive + force, or recursive on a broad target (/, ~, $HOME, *)
        if (RM_WORD.containsMatchIn(lower)) {
            val recursive = RM_RECURSIVE.containsMatchIn(lower)
            val force = RM_FORCE.containsMatchIn(lower)
            val broadTarget = RM_BROAD_TARGET.containsMatchIn(lower)
            if (recursive && force) return danger("Recursive force delete — irreversible data loss.")
            if (recursive && broadTarget) return danger("Recursive delete of a broad path — irreversible data loss.")
        }

        // Direct write to a disk device / formatting
        if (DD_TO_DEVICE.containsMatchIn(lower) ||
            DISK_TOOLS.containsMatchIn(lower) ||
            REDIRECT_TO_DISK.containsMatchIn(lower)
        ) {
            return danger("Writes directly to a disk device — can destroy the filesystem.")
        }

        // Download and pipe straight into a shell (unverified remote code)
        if (DOWNLOAD_TO_SHELL.containsMatchIn(lower)) {
            return danger("Pipes a downloaded script straight into a shell — runs unverified remote code.")
        }

        // Any pipe into an interpreter (shell or python/perl/ruby/node, e.g. base64 -d | python3) —
        // runs constructed/decoded code. Interpreter list matches curl|... above.
        if (PIPE_TO_INTERPRETER.containsMatchIn(lower)) {
            return danger("Pipes output into an interpreter — runs constructed or remote code.")
        }

        // rsync --delete — mirror delete, wipes destination files absent from the source
        if (RSYNC_DELETE.containsMatchIn(lower)) {
            return danger("Mirror delete — wipes files in the destination that are absent from the source.")
        }

        // Power off / reboot
        if (POWER_OFF.containsMatchIn(lower) ||
            INIT_0_6.containsMatchIn(lower) ||
            SYSTEMCTL_POWER.containsMatchIn(lower)
        ) {
            return danger("Powers off or reboots the machine.")
        }

        // Recursive permission/ownership change on a system path
        if (CHMOD_CHOWN_RECURSIVE.containsMatchIn(lower) &&
            SYSTEM_PATH.containsMatchIn(lower)
        ) {
            return danger("Recursive permission/ownership change on a system path.")
        }

        // Overwrite of security-critical files (via > or tee)
        if (REDIRECT_TO_SECURITY_FILE.containsMatchIn(lower) ||
            TEE_TO_SECURITY_FILE.containsMatchIn(lower)
        ) {
            return danger("Overwrites a security-critical file.")
        }

        // Firewall flush — can cut off own access
        if (FIREWALL_FLUSH.containsMatchIn(lower)) {
            return danger("Flushes firewall rules — may cut off remote access.")
        }

        // ---------- WARN ----------

        if (RM_ANY.containsMatchIn(lower)) {
            return warn("Deletes files.", destructive = true)
        }
        if (KILL_PROCESS.containsMatchIn(lower)) {
            return warn("Terminates running processes.")
        }
        if (PKG_REMOVE.containsMatchIn(lower) ||
            PACMAN_REMOVE.containsMatchIn(lower)
        ) {
            return warn("Uninstalls packages.")
        }
        if (GIT_DESTRUCTIVE.containsMatchIn(lower)) {
            return warn("Discards local changes or rewrites history.", destructive = true)
        }
        if (CHMOD_777.containsMatchIn(lower)) {
            return warn("Grants world-writable permissions.")
        }
        if (SERVICE_STOP.containsMatchIn(lower)) {
            return warn("Stops or disables a service.")
        }
        if (FIND_DELETE.containsMatchIn(lower)) {
            return warn("Bulk-deletes matched files.", destructive = true)
        }
        if (TRUNCATE_CONTENT.containsMatchIn(lower)) {
            return warn("Discards file contents.", destructive = true)
        }
        if (ELEVATED.containsMatchIn(lower)) {
            return warn("Runs with elevated privileges.")
        }

        return CommandAssessment.SAFE
    }

    /**
     * Normalizes a command to how the shell would parse it, so simple escaping can't smuggle a
     * dangerous command past the literal regexes. Known uncovered bypass: indirection via variables
     * (`T=/dev/sda; dd of=$T`) would need assignment tracking; deliberately not caught here (the
     * main gate is user confirmation before execution).
     */
    private fun normalize(command: String): String {
        var s = command
        // The shell expands $IFS / ${'$'}{IFS} to a separator — substitute a space.
        s = s.replace("${'$'}{IFS}", " ").replace("${'$'}IFS", " ")
        // Strip single escaping backslashes: \x -> x (as the shell does outside quotes).
        s = s.replace(ESCAPED_CHAR) { it.groupValues[1] }
        // Quotes don't change command/path execution — strip them so `rm -r "/"` == `rm -r /`.
        s = s.replace("\"", "").replace("'", "")
        // Collapse repeated spaces (there can be many after $IFS expansion).
        return s.replace(WHITESPACE_RUN, " ").trim()
    }

    private fun danger(reason: String) = CommandAssessment(CommandRisk.Danger, reason, destructive = true)
    private fun warn(reason: String, destructive: Boolean = false) = CommandAssessment(CommandRisk.Warn, reason, destructive)
}
