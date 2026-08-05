package app.skerry.shared.terminal

/**
 * Swallows the echo of text the client itself sent, so protocol the user never typed doesn't show
 * up on their screen.
 *
 * A runbook step is typed into the user's own shell (that is the whole point — `cd`, `sudo` and
 * exported variables have to carry over), and a PTY echoes what it is given. The step's status
 * probes are therefore echoed too, and they are pure protocol: `printf '\033]…'` around the command
 * the operator actually wrote. This filter is told the exact fragments to expect and drops them from
 * the stream between the PTY and the screen.
 *
 * Both fragments are searched for in the stream rather than expected at a fixed position: the
 * shell's next prompt (or the tail of the previous step) arrives between the moment a step is
 * declared and the moment its echo comes back, and the operator's own command sits between the two
 * fragments. What makes the search safe is the step token inside each fragment — unique to this run
 * and this step, so nothing else in the stream looks like it. Text that only partly matches is held
 * back and drawn, in order, the moment the match breaks.
 *
 * Not a security boundary and not a rewrite of history: what it hides is bytes this client sent
 * moments earlier, verbatim. The terminal buffer keeps the command itself, and a fragment that
 * doesn't match is simply drawn.
 */
internal class TerminalEchoFilter {

    private var fragments = ArrayDeque<String>()

    /** How much of the fragment in hand has matched so far — also the length held in [pending]. */
    private var matched = 0

    /** Whether anything is being filtered at all — the fast path for an ordinary session. */
    val active: Boolean get() = fragments.isNotEmpty()

    /** Bytes held back while a fragment is partially matched; drawn if the match breaks. */
    private val pending = StringBuilder()

    /**
     * Starts filtering [expected] fragments, in the order the PTY will echo them. An empty list (or
     * [stop]) switches the filter off and drops whatever was held back — the caller is telling it
     * the echo is over.
     */
    fun expect(expected: List<String>) {
        fragments = ArrayDeque(expected.filter { it.isNotEmpty() })
        matched = 0
        pending.clear()
    }

    /**
     * Gives back a partial match that something unfilterable interrupted (an escape sequence, a
     * non-ASCII character), so the held text is drawn instead of vanishing. The search goes on: the
     * echo may well continue after whatever the shell just did.
     */
    fun flushPartial(): String {
        val held = pending.toString()
        pending.clear()
        matched = 0
        return held
    }

    /** Switches the filter off; anything still held back is returned so it isn't lost. */
    fun stop(): String {
        val held = pending.toString()
        expect(emptyList())
        return held
    }

    /**
     * Offers the character [ch] the terminal is about to print. Returns what should actually be
     * printed: empty while a fragment is being matched (or when it completes), the held-back text
     * plus [ch] when a partial match breaks, and [ch] itself when nothing is being matched.
     *
     * Callers must feed every printed character while [active], including the ones they got back —
     * the returned text is already resolved and must not be offered again.
     */
    fun filter(ch: Char): String {
        val fragment = fragments.firstOrNull() ?: return ch.toString()
        if (ch == fragment[matched]) {
            pending.append(ch)
            matched++
            if (matched == fragment.length) swallowed()
            return ""
        }
        return realign(fragment, ch)
    }

    /**
     * The fragment in hand is fully matched and dropped. The next one is searched for in the stream
     * rather than expected right away: the operator's own command sits between the two.
     */
    private fun swallowed() {
        fragments.removeFirst()
        matched = 0
        pending.clear()
    }

    /**
     * A partial match broke on [ch]. What was held back may still *end* in the start of [fragment] —
     * `;;` against `;; end` is one `;` of output followed by a match that begins one character
     * later — so the longest such suffix is kept and only the text before it is printed. Without
     * this the second attempt would be missed and the probe would show up on screen.
     */
    private fun realign(fragment: String, ch: Char): String {
        val buffer = pending.toString() + ch
        for (skip in 1 until buffer.length) {
            val suffix = buffer.substring(skip)
            if (!fragment.startsWith(suffix)) continue
            matched = suffix.length
            pending.clear()
            pending.append(suffix)
            if (matched == fragment.length) swallowed()
            return buffer.substring(0, skip)
        }
        matched = 0
        pending.clear()
        return buffer
    }
}
