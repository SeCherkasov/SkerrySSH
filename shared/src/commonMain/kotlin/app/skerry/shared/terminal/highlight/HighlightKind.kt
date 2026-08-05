package app.skerry.shared.terminal.highlight

import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle

/**
 * Category a piece of terminal text was recognized as, by the client's own highlighting (the shell
 * and the commands themselves know nothing about it). Two families:
 *  - command line: [Command]..[Comment] — what the user is typing at the prompt;
 *  - output: [LevelError]..[Timestamp] — what a command printed without colors of its own.
 *
 * A category carries no color: it maps into a [TermStyle] via [applyTo], which uses palette indices
 * only, so the result follows the active terminal theme (including light ones) and any OSC 4 override.
 */
enum class HighlightKind(
    /** Palette index for the text, or `null` to keep the cell's own color. */
    internal val paletteIndex: Int?,
    internal val bold: Boolean = false,
    internal val dim: Boolean = false,
) {
    None(null),

    // Command line. Colors follow fish/zsh-syntax-highlighting so the mapping is already familiar.
    Command(2, bold = true),
    Subcommand(2),
    Option(6),
    StringLit(3),
    PathLit(4),
    Operator(5, bold = true),
    Variable(13),
    Comment(null, dim = true),

    // Output.
    LevelError(1, bold = true),
    LevelWarn(3, bold = true),
    LevelInfo(4),
    LevelDebug(null, dim = true),
    LevelOk(2),
    Address(6),
    Timestamp(null, dim = true),
}

/** A [kind] recognized over `[start, endExclusive)` of a line — string indices, never columns. */
data class HighlightSpan(val start: Int, val endExclusive: Int, val kind: HighlightKind)

/**
 * The style a cell gets under this category: [base] with the palette color (and weight) of the
 * category laid over it. [None] returns [base] itself — the no-highlight path allocates nothing.
 *
 * Colors are palette indices, never literals: on a light theme index 2 is that theme's green, the
 * same one `ls --color` is already read against there, so contrast is the theme's property and not
 * something guessed here. [Comment], [LevelDebug] and [Timestamp] only dim, which is safe on any
 * background.
 */
fun HighlightKind.applyTo(base: TermStyle): TermStyle {
    if (this == HighlightKind.None) return base
    return base.copy(
        fg = paletteIndex?.let { TermColor.Indexed(it) } ?: base.fg,
        bold = base.bold || bold,
        dim = base.dim || dim,
    )
}
