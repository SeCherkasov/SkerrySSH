package app.skerry.shared.text

/**
 * What draws as nothing, and what only looks as if it does.
 *
 * One rule for the whole app, because there are two questions and they must not be answered by two
 * hand-written lists. A filter over a value that is *compared* — a tag, a label, a file name —
 * drops what draws as nothing, so two strings that render alike are one string. A quote over a
 * command *spells it out* instead, because there the drawn text has to account for every byte that
 * will run.
 */

/**
 * Characters that draw as nothing but count as letters (Hangul fillers U+115F/U+1160/U+3164/U+FFA0,
 * Braille blank U+2800), so the format-category filters keep them: two strings differing only by
 * one of these render identically while executing differently. Never legitimate in a command or a
 * spliced value, so both filters below drop them. Public because the UI's untrusted-label filter
 * rejects exactly this set — one definition, not two hand-synced copies. Escapes, not the raw
 * glyphs: an invisible character in source is unreviewable.
 */
val INVISIBLE_LETTERS: Set<Char> = setOf('\u115F', '\u1160', '\u3164', '\uFFA0', '\u2800')

/**
 * Astral code points that draw as nothing and name no category a per-char walk can see: both halves
 * of the surrogate pair classify as SURROGATE, so a filter that walks chars keeps the whole range.
 * One definition for the app — the quote spells these out, the label filters drop them.
 */
val INVISIBLE_ASTRAL: List<IntRange> = listOf(
    0x110BD..0x110BD, // Kaithi number sign
    0x110CD..0x110CD, // Kaithi number sign above
    0x13430..0x1343F, // Egyptian hieroglyph format controls
    0x1BCA0..0x1BCA3, // shorthand format controls
    0x16FE4..0x16FE4, // Khitan small script filler
    0x1D173..0x1D17A, // musical symbol beams and streams
    0xE0000..0xE0FFF, // tags, variation selectors supplement, and the reserved rest of the plane
)

/**
 * Whether a character contributes anything a reader can see.
 *
 * The whole format category rather than a list of the characters seen so far — a list is one
 * Unicode release behind by construction. The line and paragraph separators are not in it but end a
 * line wherever the text is laid out. Then the rest of Unicode's `Default_Ignorable_Code_Point`,
 * which is the property the shaper actually hides ([OTHER_DEFAULT_IGNORABLE]). A surrogate on its
 * own is half a character — it draws as the replacement glyph, and two orphans left either side of
 * a dropped pair would join into the very code point that was dropped.
 */
fun drawsAsSomething(ch: Char): Boolean = when {
    ch.isSurrogate() -> false
    ch.category == CharCategory.FORMAT -> false
    ch in INVISIBLE_LETTERS -> false
    ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR -> false
    ch in OTHER_DEFAULT_IGNORABLE -> false
    else -> !ch.isISOControl()
}

private const val LINE_SEPARATOR = '\u2028'
private const val PARAGRAPH_SEPARATOR = '\u2029'

/**
 * The part of `Default_Ignorable_Code_Point` no category names. The variation selectors, the
 * combining grapheme joiner, the Mongolian free variation selectors and the Khmer inherent vowels
 * are `Mn`; U+2065 and U+FFF0..U+FFF8 are unassigned, reserved as ignorable. HarfBuzz — the shaper
 * under Skia, so under Compose on both targets — gives every one of them a zero advance, so a rule
 * written per category keeps characters the screen never shows. The invisible letters of the same
 * property have their own set ([INVISIBLE_LETTERS]), because the quote spells those out by name.
 */
private val OTHER_DEFAULT_IGNORABLE: Set<Char> = (
    ('\uFE00'..'\uFE0F') + ('\u180B'..'\u180F') + ('\u17B4'..'\u17B5') +
        ('\uFFF0'..'\uFFF8') + '\u034F' + '\u2065'
    ).toSet()

/**
 * Text with everything that draws as nothing removed — the whole format category, the invisible
 * letters and the astral format ranges, by code point.
 *
 * For a value that is compared as well as drawn: a tag decides whether the production guard fires,
 * so a tag stored with a zero-width character in it would read as `prod` on screen and match
 * nothing. Unlike [stripUnsafeFormatChars] this is a category rule rather than a list of the
 * characters a terminal must not receive, and it keeps no control bytes.
 */
fun stripInvisible(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val low = if (ch.isHighSurrogate() && i + 1 < text.length) text[i + 1] else null
        if (low?.isLowSurrogate() == true) {
            val code = 0x10000 + ((ch.code - 0xD800) shl 10) + (low.code - 0xDC00)
            if (INVISIBLE_ASTRAL.none { code in it }) append(ch).append(low)
            i += 2
            continue
        }
        if (drawsAsSomething(ch)) append(ch)
        i++
    }
}
