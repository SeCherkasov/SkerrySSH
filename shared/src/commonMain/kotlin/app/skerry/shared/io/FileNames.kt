package app.skerry.shared.io

import app.skerry.shared.snippet.INVISIBLE_LETTERS

/**
 * Turns user data — a host label, a secret's name — into the stem of a file name a Save-As dialog
 * can be handed on any platform. A label may hold anything a person can type, including the two
 * spellings that stop being a name and start being a location: a separator and `..`.
 *
 * Everything that isn't a letter, digit, dash or underscore collapses to a dash — as do the letters
 * that draw as nothing, or two exports could offer names a Save-As dialog cannot tell apart; dots survive only
 * when [keepDots] (a host name is mostly dots), and never two in a row. The result is trimmed of
 * leading/trailing separators, cut to [maxLength], trimmed again — the cut can land on a separator —
 * lowercased when [lowercase], and falls back to [fallback] when nothing printable is left.
 */
fun safeFileStem(
    raw: String,
    fallback: String,
    keepDots: Boolean = false,
    lowercase: Boolean = false,
    maxLength: Int = 48,
): String {
    val mapped = raw.map { ch ->
        when {
            // The invisible letters pass isLetterOrDigit and draw as nothing, so two exports of
            // different secrets could offer names a Save-As dialog cannot tell apart.
            ch in INVISIBLE_LETTERS -> '-'
            ch.isLetterOrDigit() || ch == '-' || ch == '_' -> ch
            keepDots && ch == '.' -> ch
            else -> '-'
        }
    }.joinToString("")
    val stem = mapped
        .replace("..", "-")
        .replace(DASH_RUN, "-")
        .trim('-', '.')
        .take(maxLength)
        .trim('-', '.')
        .let { if (lowercase) it.lowercase() else it }
        .ifEmpty { fallback }
    // On Windows these are devices, not names: writing to "aux.pem" succeeds and produces no file.
    return if (stem.substringBefore('.').lowercase() in WINDOWS_DEVICES) "$stem-$fallback" else stem
}

private val DASH_RUN = Regex("-+")

/** Reserved DOS device names, still special in every Windows path today. */
private val WINDOWS_DEVICES = setOf(
    "con", "prn", "aux", "nul",
    "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
)
