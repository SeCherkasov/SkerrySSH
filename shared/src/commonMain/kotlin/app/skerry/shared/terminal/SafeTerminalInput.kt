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
