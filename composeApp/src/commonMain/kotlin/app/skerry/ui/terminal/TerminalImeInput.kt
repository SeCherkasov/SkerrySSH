package app.skerry.ui.terminal

/**
 * Anchor value of the hidden IME field. The soft keyboard (Android) doesn't send key events to
 * [mapTerminalKey], so input is captured via an invisible `BasicTextField` that resets to this value
 * after every change. Non-empty so Backspace on an empty terminal still produces a change (anchor
 * deletion) — otherwise there's nothing for the IME to delete and onValueChange never fires.
 * Zero-width space (U+200B): invisible, not echoed by the shell, doesn't break alignment. Given as a
 * code point rather than a literal so it isn't invisible in the source (Read/grep).
 */
val ANCHOR: String = Char(0x200b).toString()

/** DEL — shell Backspace; CR — Enter. Given as code points so they aren't invisible in Read/grep. */
private val DEL: Char = Char(0x7f)
private val CR: Char = Char(0x0d)

/**
 * Converts a change in the hidden IME field's value into PTY bytes. The field always resets to
 * [anchor], so [value] = anchor ± user edits. Diffed by common prefix:
 *  - anything removed relative to the anchor (shorter tail) → one [DEL] per char (Backspace);
 *  - anything appended past the common prefix → sent as-is, with `\n` mapped to [CR] (Enter).
 *
 * Pure and testable; the UI just feeds (anchor, value) and resets the field to [anchor].
 */
fun imeDeltaToPty(anchor: String, value: String): String {
    var common = 0
    val max = minOf(anchor.length, value.length)
    while (common < max && anchor[common] == value[common]) common++

    val deletions = anchor.length - common
    val added = value.substring(common)

    return buildString {
        repeat(deletions) { append(DEL) }
        for (ch in added) append(if (ch == '\n') CR else ch)
    }
}
