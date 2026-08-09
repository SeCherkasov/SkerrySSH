package app.skerry.shared.terminal

/**
 * Whether a character is allowed in a single line of terminal input built from untrusted text
 * (AI-suggested commands, snippet variable values). Beyond control bytes (< 0x20 except tab,
 * DEL and the C1 range — DEL is interpreted by the remote line discipline and would erase
 * already-sent characters, diverging from the previewed line), also rejects Unicode bidi/format
 * characters (RTL/LTR override and isolate, zero-width, BOM, soft hyphen) — otherwise a
 * Trojan-Source string could render one way in a confirmation UI and execute differently in
 * the PTY.
 */
fun isSafeTerminalInputChar(c: Char): Boolean {
    if (c != '\t' && c.code < 0x20) return false
    if (c.code == 0x7F || c.code in 0x80..0x9F) return false
    val code = c.code
    val unsafeFormat = code == 0x00AD ||          // soft hyphen
        code == 0x061C ||                          // arabic letter mark
        code in 0x200B..0x200F ||                  // ZWSP/ZWNJ/ZWJ/LRM/RLM
        code == 0x2028 || code == 0x2029 ||        // line/paragraph separator
        code == 0x2060 ||                          // word joiner
        code in 0x202A..0x202E ||                  // bidi embeddings/overrides
        code in 0x2066..0x2069 ||                  // bidi isolates
        code == 0xFEFF                             // ZWNBSP / BOM
    return !unsafeFormat
}

/**
 * Whether a character is allowed in untrusted text the app only ever *shows* — an assistant reply,
 * a model's explanation. More permissive than [isSafeTerminalInputChar], deliberately.
 *
 * Rejected: control bytes, DEL and C1, and the characters that can render a line in the reverse of
 * its bytes — the bidi embeddings and overrides (U+202A..U+202E), the isolates (U+2066..U+2069), the
 * line and paragraph separators, and the Arabic letter mark. Those are the Trojan Source set, and
 * this text is one copy away from a shell.
 *
 * Kept: the zero-width joiner and non-joiner, without which a family emoji falls apart into three
 * people and Persian loses its joins; the zero-width space, word joiner, BOM and soft hyphen, which
 * reorder nothing and only ever make a pasted command fail rather than retarget it; and the left-
 * and right-to-left marks (U+200E/U+200F). The marks are not free — they reorder neutrals and digits
 * around them — but they cannot move letters, which is what a disguise needs, and they are how
 * mixed-direction prose is written correctly. `HostMetrics` strips them from host-supplied metric
 * text because nothing there is ever prose.
 *
 * Text that is offered for execution keeps the stricter predicate: there the displayed string and
 * the string sent to the PTY have to be the same bytes.
 */
fun isSafeDisplayChar(c: Char): Boolean {
    if (c != '\t' && c.code < 0x20) return false
    if (c.code == 0x7F || c.code in 0x80..0x9F) return false
    val code = c.code
    val reordering = code == 0x061C ||                 // arabic letter mark
        code == 0x2028 || code == 0x2029 ||            // line/paragraph separator
        code in 0x202A..0x202E ||                      // bidi embeddings/overrides
        code in 0x2066..0x2069                         // bidi isolates
    return !reordering
}
