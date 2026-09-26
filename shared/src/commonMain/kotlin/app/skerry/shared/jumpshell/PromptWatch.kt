package app.skerry.shared.jumpshell

/**
 * Follows a shell's output and says whether it now stands at a prompt: the text since the last line
 * break, with escape sequences taken out, ends in `$`, `#` or `%`. Stateful across [feed] calls,
 * since an escape sequence or a line may be split between two reads.
 *
 * A heuristic, and meant to be a cautious one. A line with a URL in it is never a prompt — a
 * browser login is exactly the kind of text a jump host prints while it waits, and typing into it
 * would answer a question nobody read. `>` is left out for the same reason: it ends menus and
 * one-time-code questions (`OTP>`) far more often than a login shell's prompt.
 *
 * Works on bytes, since everything it looks for is ASCII; anything else counts as an ordinary
 * character. Only the tail of the line is kept.
 */
internal class PromptWatch {
    private val tail = StringBuilder()
    private var urlInLine = false
    private var escape = Escape.None

    private enum class Escape { None, Start, Csi, Osc, OscEnd }

    fun feed(chunk: ByteArray) {
        for (byte in chunk) {
            val c = byte.toInt() and 0xff
            escape = when (escape) {
                Escape.None -> plain(c)
                Escape.Start -> when (c) {
                    '['.code -> Escape.Csi
                    ']'.code -> Escape.Osc
                    else -> Escape.None
                }
                Escape.Csi -> if (c in CSI_FINAL) Escape.None else Escape.Csi
                Escape.Osc -> when (c) {
                    BEL -> Escape.None
                    ESC -> Escape.OscEnd
                    else -> Escape.Osc
                }
                Escape.OscEnd -> Escape.None
            }
        }
    }

    private fun plain(c: Int): Escape {
        when {
            c == ESC -> return Escape.Start
            c == LF || c == CR -> {
                tail.clear()
                urlInLine = false
            }
            c == BS -> if (tail.isNotEmpty()) tail.setLength(tail.length - 1)
            c < SPACE || c == DEL -> Unit
            else -> append(if (c < ASCII_END) c.toChar() else OTHER)
        }
        return Escape.None
    }

    private fun append(c: Char) {
        tail.append(c)
        if (tail.endsWith("://")) urlInLine = true
        // Trimmed in one go once it doubles rather than per character: a long line stays linear.
        if (tail.length >= 2 * TAIL) tail.deleteRange(0, tail.length - TAIL)
    }

    /** Whether the current line reads as a shell waiting for a command. */
    fun atPrompt(): Boolean {
        if (urlInLine) return false
        var i = tail.length - 1
        while (i >= 0 && tail[i] == ' ') i--
        return i >= 0 && tail[i] in PROMPT_ENDS
    }

    private companion object {
        const val ESC = 0x1b
        const val BEL = 0x07
        const val BS = 0x08
        const val LF = 0x0a
        const val CR = 0x0d
        const val SPACE = 0x20
        const val DEL = 0x7f
        const val ASCII_END = 0x80
        const val OTHER = '?'
        const val TAIL = 256
        val CSI_FINAL = 0x40..0x7e
        val PROMPT_ENDS = setOf('$', '#', '%')
    }
}
