package app.skerry.ui.design

/**
 * One line of text this client did not write, made fit to draw: a remote host's file name, a
 * profile a team member shared, a team space's name.
 *
 * Such a string reaches the screen exactly as its author typed it — the server never sees a shared
 * record (it travels inside the sealed envelope) and an SFTP listing is whatever the far side
 * answers. A bidi override reverses the tail of the line in every layout, so a name ending in
 * `.exe` can draw as one ending in `.png`; a zero-width formatter makes two different names draw
 * as one.
 *
 * The whole format category goes, not just the reordering characters that [app.skerry.shared.terminal.isSafeDisplayChar]
 * rejects. That predicate is right for prose the app only shows — an assistant's answer, where
 * dropping a joiner breaks an emoji or a Persian word and no identity rides on the string. A label
 * is the opposite case: it is what tells one host, one file, one space from another, so a
 * character that renders as nothing is exactly the one that must not survive. Same choice the
 * keyboard-interactive prompt already makes, and this is that same filter.
 */
internal fun untrustedLabel(raw: String, maxChars: Int = MAX_UNTRUSTED_LABEL_CHARS): String =
    sanitizeServerText(raw, maxChars, allowNewlines = false)

/**
 * A name someone else wrote, made fit to draw and to announce, with something to draw when nothing of it
 * survives the filter.
 *
 * The one shape for every list whose rows are named by a peer or a server — team spaces, hosts, linked
 * devices. The label is drawn AND used as an accessible name, so a bidi override in it would make one row
 * announce as another; and a name made only of characters the sanitizer drops leaves a blank row the user
 * cannot tell from any other, which is what [fallback] is for. Pass an id, filtered too — a fallback that
 * skips the filter just moves the hole.
 */
internal fun spaceLabel(raw: String, fallback: String): String = untrustedLabel(raw).ifBlank { fallback }

/**
 * How much of an id stands in for a name that filtered away to nothing — enough to tell two rows
 * apart, short enough to read as an id rather than as a name.
 */
internal const val SHORT_ID_CHARS = 8

/**
 * Cap on a label of untrusted origin. Generous — a row shows a fraction of it and ellipsizes the
 * rest — but bounded: the string arrives from somewhere with no length limit of its own.
 */
internal const val MAX_UNTRUSTED_LABEL_CHARS = 120

/**
 * Makes server-supplied text safe to render: drops control characters (including the escape
 * sequences a terminal would act on and the bidi overrides that could reorder the sentence), keeps
 * newlines only inside longer blocks, collapses runs of blank lines and caps the length.
 *
 * Truncation is silent rather than marked with an ellipsis — the cap is generous enough that only a
 * server trying to flood the dialog reaches it. It bounds the scan as well as the result: text past
 * [SCAN_FACTOR] times the cap is never looked at, so a flood of characters this drops costs no more
 * than a flood of characters it keeps.
 */
internal fun sanitizeServerText(text: String, maxChars: Int, allowNewlines: Boolean): String {
    // The loop below stops on what it has *kept*, so a text made of nothing but the characters it
    // drops would be scanned in full however long it is — the cap has to bound the input too, or
    // padding a name with a million zero-width spaces is a scan the far side gets for free. The
    // factor leaves room for ordinary text carrying formatting and still bounds a flood.
    val bounded = if (text.length > maxChars * SCAN_FACTOR) text.take(maxChars * SCAN_FACTOR) else text
    val cleaned = buildString(minOf(bounded.length, maxChars)) {
        for (ch in bounded) {
            if (length >= maxChars) break
            when (classifyServerChar(ch, allowNewlines)) {
                ServerChar.Keep -> append(ch)
                // Runs collapse: a server padding with blank lines or tabs gets one separator.
                ServerChar.Break -> if (isNotEmpty() && last() != '\n') append('\n')
                ServerChar.Space -> if (isNotEmpty() && last() != ' ') append(' ')
                ServerChar.Drop -> Unit
            }
        }
    }
    // The cap counts UTF-16 units, so it can fall between the halves of an astral character and
    // leave an orphan that draws as U+FFFD — in the caption and in the field's name alike. Same
    // cut, same treatment as the terminal title's and the team label's.
    return cleaned.dropLastWhile { it.isHighSurrogate() }.trim()
}

/** How much input [sanitizeServerText] is willing to walk for each character it may keep. */
private const val SCAN_FACTOR = 8

/** What [sanitizeServerText] does with one character of server text. */
private const val LINE_SEPARATOR = '\u2028'
private const val PARAGRAPH_SEPARATOR = '\u2029'

private enum class ServerChar { Keep, Break, Space, Drop }

/**
 * Letters and a symbol that draw nothing at all: the Hangul fillers (choseong, jungseong, the
 * compatibility one and its halfwidth form) and the blank braille pattern. They are `Lo`/`So`, not
 * format characters, so nothing else drops them — a name made of them passes `isBlank()`, and a
 * quoted `curl\u2800evil.sh` reads as two words and runs as one.
 */
internal val INVISIBLE_LETTERS = charArrayOf('\u115F', '\u1160', '\u3164', '\uFFA0', '\u2800')

private fun classifyServerChar(ch: Char, allowNewlines: Boolean): ServerChar = when {
    ch == '\n' && allowNewlines -> ServerChar.Break
    // Where newlines survive, the carriage return of a CRLF must not become a space before every
    // one of them; it carries nothing the newline does not already say.
    ch == '\r' && allowNewlines -> ServerChar.Drop
    // Single-line sink: fold rather than drop, or the words either side are glued together
    // ("Accessdeniedby policy"), which reads worse than the wrapped original.
    ch == '\n' || ch == '\r' || ch == '\t' -> ServerChar.Space
    ch.isISOControl() -> ServerChar.Drop
    // The whole format category rather than the bidi overrides alone: the marks (LRM/RLM/ALM) reorder
    // the neutral runs of a prompt the user reads before typing a secret, and the zero-width set lets
    // one carry content nothing renders. Naming ranges left both classes in, and would leave whatever
    // Unicode adds next. Covers the BOM too, which used to have a branch of its own.
    ch.category == CharCategory.FORMAT -> ServerChar.Drop
    // Not in that category, but they end a line wherever the text is laid out — a single-line
    // caption is exactly what a server would use them to turn into several.
    ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR -> ServerChar.Drop
    // Letters and a symbol by category, nothing at all on screen: a name made only of these is not
    // blank to `isBlank()`, so it would slip past the stand-in a row falls back to and draw as an
    // empty line. Five code points, not a confusables table — homoglyphs are a different problem
    // and not one a client can close.
    ch in INVISIBLE_LETTERS -> ServerChar.Drop
    else -> ServerChar.Keep
}
