package app.skerry.ui.design
import app.skerry.shared.text.INVISIBLE_ASTRAL
import app.skerry.shared.text.drawsAsSomething
import app.skerry.shared.text.stripInvisible

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
 *
 * What it costs: a variation selector goes with the rest, so `report❤️.txt` draws as `report❤` —
 * the text presentation of the same character. Deliberate. This filter answers "are these two names
 * the same name", and two names that draw alike have to be one; the command quote answers the other
 * question and keeps them (see `drawsAsItself`).
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
internal fun sanitizeServerText(text: String, maxChars: Int, allowNewlines: Boolean): String =
    sanitized(text, maxChars, allowNewlines).text

/**
 * Whether [sanitizeServerText] draws [text] whole at [maxChars] — for the surfaces that say when a
 * note was shortened.
 *
 * The drawn string's own length cannot answer, with or without a character of slack: the walk stops
 * on the budget, and the trailing trim then takes the separator it stopped on away, so a note that
 * really was cut comes back under the cap and reads as whole. The walk knows; this asks it.
 */
internal fun sanitizedFits(text: String, maxChars: Int, allowNewlines: Boolean = false): Boolean =
    sanitized(text, maxChars, allowNewlines).whole

/** The filtered text and whether the walk reached the end of what it was given. */
private class Sanitized(val text: String, val whole: Boolean)

private fun sanitized(text: String, maxChars: Int, allowNewlines: Boolean): Sanitized {
    // The loop below stops on what it has *kept*, so a text made of nothing but the characters it
    // drops would be scanned in full however long it is — the cap has to bound the input too, or
    // padding a name with a million zero-width spaces is a scan the far side gets for free. The
    // factor leaves room for ordinary text carrying formatting and still bounds a flood.
    val bounded = if (text.length > maxChars * SCAN_FACTOR) text.take(maxChars * SCAN_FACTOR) else text
    var read = false
    val cleaned = buildString(minOf(bounded.length, maxChars)) {
        read = appendSanitized(bounded, maxChars, allowNewlines)
    }
    // The cap counts UTF-16 units, so it can fall between the halves of an astral character and
    // leave an orphan that draws as U+FFFD — in the caption and in the field's name alike. Same
    // cut, same treatment as the terminal title's and the team label's.
    return Sanitized(cleaned.dropLastWhile { it.isHighSurrogate() }.trim(), read && bounded.length == text.length)
}

/**
 * The scan itself, kept apart from the caps around it. `false` when it stopped on the budget
 * rather than on the end of the text — which is the only honest answer to "was this cut".
 *
 * By code point, not by char: an astral formatting character is a surrogate pair, and both halves
 * classify as SURROGATE — walked per char, the whole astral format range would be kept, and two
 * names differing only by one of them would draw as one.
 */
private fun StringBuilder.appendSanitized(text: String, maxChars: Int, allowNewlines: Boolean): Boolean {
    var i = 0
    while (i < text.length) {
        if (length >= maxChars) return false
        val ch = text[i]
        val pair = text.astralPairAt(i)
        if (pair != null) {
            if (!appendPair(text, i, pair, maxChars)) return false
            i += 2
            continue
        }
        appendClassified(ch, allowNewlines)
        i++
    }
    return true
}

/**
 * One astral character. Appended whole or not at all — half of a pair draws as the replacement
 * glyph, which is neither the character nor a cut anyone can read. A pair that draws nothing spends
 * none of the budget, so the text after it still fits. `false` when the budget is spent.
 */
private fun StringBuilder.appendPair(text: String, index: Int, code: Int, maxChars: Int): Boolean {
    if (!astralDrawsAsSomething(code)) return true
    if (length + 2 > maxChars) return false
    append(text, index, index + 2)
    return true
}

/** One basic-plane character, as the classifier decided to draw it. */
private fun StringBuilder.appendClassified(ch: Char, allowNewlines: Boolean) {
    when (classifyServerChar(ch, allowNewlines)) {
        ServerChar.Keep -> append(ch)
        // Runs collapse: a server padding with blank lines or tabs gets one separator.
        ServerChar.Break -> if (isNotEmpty() && last() != '\n') append('\n')
        ServerChar.Space -> if (isNotEmpty() && last() != ' ') append(' ')
        ServerChar.Drop -> Unit
    }
}

/** The code point at [index] when a surrogate pair starts there, `null` otherwise. */
private fun String.astralPairAt(index: Int): Int? {
    val high = this[index]
    if (!high.isHighSurrogate() || index + 1 >= length) return null
    val low = this[index + 1]
    if (!low.isLowSurrogate()) return null
    return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
}

/** Whether an astral code point draws at all — the format ranges draw as nothing. */
private fun astralDrawsAsSomething(code: Int): Boolean = INVISIBLE_ASTRAL.none { code in it }

/** How much input [sanitizeServerText] is willing to walk for each character it may keep. */
private const val SCAN_FACTOR = 8

/** What [sanitizeServerText] does with one character of server text. */
private enum class ServerChar { Keep, Break, Space, Drop }

private fun classifyServerChar(ch: Char, allowNewlines: Boolean): ServerChar = when {
    ch == '\n' && allowNewlines -> ServerChar.Break
    // Where newlines survive, a carriage return is the line it ends, not a space before one: the
    // run-collapse below folds the CR of a CRLF into the newline that follows it, and a CR on its
    // own still draws as the break it is. Dropped, two commands a server put on one clipboard
    // would draw as a single line while a paste of them submits both.
    ch == '\r' && allowNewlines -> ServerChar.Break
    // Single-line sink: fold rather than drop, or the words either side are glued together
    // ("Accessdeniedby policy"), which reads worse than the wrapped original.
    ch == '\n' || ch == '\r' || ch == '\t' -> ServerChar.Space
    !drawsAsSomething(ch) -> ServerChar.Drop
    else -> ServerChar.Keep
}

/**
 * A tag as a chip draws it: the `#` the model value does not carry, and nothing that draws as
 * nothing. Filtered because a tag arrives with the record it belongs to and a record written by an
 * older client was never canonicalized — a chip could read `#prod` while the tag it filters by is
 * something else. One definition: the host chips and the snippet chips are the same chip.
 */
fun tagChipLabel(tag: String): String = "#" + stripInvisible(tag)
