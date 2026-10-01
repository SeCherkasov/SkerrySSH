package app.skerry.shared.terminal

/**
 * Shell integration: OSC 133 (semantic prompts — FinalTerm/VS Code/iTerm2) brackets every command
 * the shell runs, and OSC 7 reports its working directory.
 *
 * What the marks say is what the host's shell said. A compromised host can emit marks of its own
 * choosing, so they are evidence about the session, not proof of it — the same standing the printed
 * prompt always had. Both parsers are bounded and rejecting: an untrusted shell must not park a
 * megabyte of anything here, and a control character must not ride into the SFTP panel through a
 * path (issue #227 precedent).
 */

/** Longest working directory accepted from OSC 7 — an untrusted shell must not park a novel here. */
const val MAX_CWD_CHARS = 4096

/** How many command marks are kept; older ones leave with the front of the scrollback anyway. */
const val MAX_COMMAND_MARKS = 500

/**
 * Longest exit status accepted in a `133;D` mark. A shell's `$?` is 0..255; anything past nine
 * digits is not one, and the cap keeps a hostile host from making the parser scan a megabyte of
 * digits per mark (the same bound [MAX_STEP_MARK_STATUS] puts on OSC 8375).
 */
const val MAX_MARK_STATUS = 9

/**
 * One command as the shell's OSC 133 marks bracket it, in the buffer's absolute row coordinates
 * (scrollback + screen — the same rows [TerminalEmulator.lines] addresses). Anchors follow the
 * buffer: scrollback trimming shifts them, a resize re-maps them onto the re-split rows.
 *
 * `null` anchors are the shell's omission, not a state: [inputRow] without a `B` mark,
 * [outputRow] without a `C`. [endRow] is `null` while the command runs (or ran into the alternate
 * screen, where a cursor position belongs to a TUI, not to this command), and [exitCode] is `null`
 * when the closing `D` never came or carried nothing readable — which is not the same as `0`.
 */
data class ShellCommandMark(
    val promptRow: Int,
    val promptCol: Int,
    val inputRow: Int?,
    val inputCol: Int?,
    val outputRow: Int?,
    val outputCol: Int?,
    val endRow: Int?,
    val endCol: Int?,
    val exitCode: Int?,
)

/** One parsed OSC 133 payload. */
internal sealed interface ShellMarkEvent {
    data object PromptStart : ShellMarkEvent
    data object InputStart : ShellMarkEvent
    data object OutputStart : ShellMarkEvent
    data class CommandEnd(val exitCode: Int?) : ShellMarkEvent
}

/**
 * The mutable twin the emulator books marks in. Every anchor is a [ReflowAnchor] so a resize can
 * move them in place (see [TerminalReflow]); the published [ShellCommandMark] is a snapshot.
 */
internal class MutableShellMark(val prompt: ReflowAnchor) {
    var input: ReflowAnchor? = null
    var output: ReflowAnchor? = null
    var end: ReflowAnchor? = null
    var exitCode: Int? = null

    /** All anchors set so far — the flat list a resize re-maps. */
    fun anchors(): List<ReflowAnchor> = listOfNotNull(prompt, input, output, end)

    /**
     * Rows trimmed off the front of history shift every anchor down. A mark whose prompt row went
     * with them is dropped whole — the gutter marker and the jump list key off the prompt, and a
     * mark that cannot point at its own prompt row would point at someone else's text.
     */
    fun shiftUp(dropped: Int): Boolean {
        if (prompt.row - dropped < 0) return false
        for (a in anchors()) {
            a.row -= dropped
            if (a.row < 0) a.col = 0 // this anchor's row is gone: the survivor starts at its own beginning
        }
        return true
    }

    fun snapshot() = ShellCommandMark(
        promptRow = prompt.row,
        promptCol = prompt.col,
        inputRow = input?.row,
        inputCol = input?.col,
        outputRow = output?.row,
        outputCol = output?.col,
        endRow = end?.row,
        endCol = end?.col,
        exitCode = exitCode,
    )
}

/**
 * Parses the payload of OSC 133 — `A`, `B`, `C`, `D[;exit][;…]`. Unknown kinds and anything longer
 * than one character are not marks. `A`'s parameters (Ghostty's `A;cl` clickable-line and whatever
 * comes next) ride along uninterpreted; `D`'s second parameter (WezTerm's duration) is dropped.
 */
internal fun parseShellMark(rest: String): ShellMarkEvent? {
    val kind = rest.substringBefore(';')
    if (kind.length != 1) return null
    return when (kind[0]) {
        'A' -> ShellMarkEvent.PromptStart
        'B' -> ShellMarkEvent.InputStart
        'C' -> ShellMarkEvent.OutputStart
        'D' -> ShellMarkEvent.CommandEnd(parseMarkExitCode(rest.substringAfter(';', "").substringBefore(';')))
        else -> null
    }
}

/**
 * The status of a `D` mark: ASCII digits only, at most [MAX_MARK_STATUS] of them — an exit code is
 * what the shell printed, not what a locale can parse (`toIntOrNull` would take a Devanagari digit
 * too). Everything else, including an absent status, reads as unknown, never as `0`.
 */
private fun parseMarkExitCode(status: String): Int? {
    if (status.isEmpty() || status.length > MAX_MARK_STATUS || status.any { it !in '0'..'9' }) return null
    return status.toIntOrNull()
}

/**
 * Parses the payload of OSC 7 — the shell's working directory as `file://host/path` (kitty, zsh
 * integration) or a plain absolute path. Relative paths, URIs without a path, oversized or
 * control-carrying payloads are rejected outright: the value feeds the SFTP panel, and a listing
 * request built from hostile bytes must not exist to begin with.
 */
internal fun parseWorkingDirectory(rest: String): String? {
    if (rest.isEmpty() || rest.length > MAX_CWD_CHARS) return null
    val path = if (rest.startsWith("file://")) {
        val noScheme = rest.removePrefix("file://")
        val slash = noScheme.indexOf('/')
        if (slash < 0) return null // `file://host` — an authority with no path names no directory
        noScheme.substring(slash)
    } else {
        rest
    }
    if (!path.startsWith("/")) return null
    val decoded = percentDecode(path) ?: return null
    if (decoded.isEmpty() || decoded.length > MAX_CWD_CHARS) return null
    // The same untrusted-text rules as a title or a peer name (issue #227 class): no reordering
    // or invisible characters — the SFTP path bar draws this string, and a bidi override would
    // spoof the location it names. Percent-decoding must not launder one past the check.
    if (decoded.any { !isSafeDisplayChar(it) || it.category == CharCategory.FORMAT }) return null
    return decoded
}

/**
 * RFC 3986 `%XX` decoding as the emitters mean it: they percent-encode the path's UTF-8 *bytes*
 * (iTerm2/kitty/zsh integration), so escapes and raw characters alike are assembled into bytes
 * and decoded as UTF-8. An ESCAPE sequence that is not valid UTF-8 rejects the whole path
 * (`null`) — `String(bytes)` would silently replace it with U+FFFD, and a path of replacement
 * characters names no directory the host has. (Raw invalid bytes never get this far as bytes:
 * the OSC payload was decoded to chars upstream, replacements included — the character filter
 * is what rejects what remains dangerous there.) The re-encode roundtrip is the strictness
 * check: replacement chars produced by decoding (a genuine U+FFFD roundtrips to its own bytes)
 * do not.
 */
private fun percentDecode(s: String): String? {
    if ('%' !in s) return s
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 3 <= s.length) {
            val hex = s.substring(i + 1, i + 3)
            if (hex.all { it in "0123456789abcdefABCDEF" }) {
                out.add(hex.toInt(16).toByte())
                i += 3
                continue
            }
        }
        for (b in c.toString().encodeToByteArray()) out.add(b)
        i++
    }
    val bytes = out.toByteArray()
    val decoded = String(bytes)
    return if (decoded.encodeToByteArray().contentEquals(bytes)) decoded else null
}
