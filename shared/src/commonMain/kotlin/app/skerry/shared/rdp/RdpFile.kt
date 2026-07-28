package app.skerry.shared.rdp

/**
 * One setting of an `.rdp` file: a lowercased [key], the declared [type] (`s` text, `i` integer,
 * `b` binary blob) and the raw [value]. The type is kept rather than resolved because the file is
 * the only place that says what a setting is — reading `server port` as text would silently accept
 * a file that declares it as something else.
 */
data class RdpFileEntry(val key: String, val type: Char, val value: String)

/** Settings of an `.rdp` file, by lowercased key. */
data class RdpFile(val entries: Map<String, RdpFileEntry>) {

    /** Value of the text setting [key], or `null` when it is absent or declared with another type. */
    fun string(key: String): String? = entries[key.lowercase()]?.takeIf { it.type == TYPE_STRING }?.value

    /** Value of the integer setting [key], or `null` when it is absent or declared with another type. */
    fun int(key: String): Int? = entries[key.lowercase()]?.takeIf { it.type == TYPE_INT }?.value?.toIntOrNull()

    /** An integer setting read as a flag: mstsc writes toggles as `:i:0` / `:i:1`. */
    fun bool(key: String): Boolean? = int(key)?.let { it != 0 }

    companion object {
        const val TYPE_STRING = 's'
        const val TYPE_INT = 'i'
        const val TYPE_BINARY = 'b'
    }
}

/**
 * Outcome of parsing an `.rdp` file: its [file] settings and human-readable [warnings] about what
 * was skipped, so the import screen can say what it did not understand instead of quietly dropping
 * half the file.
 */
data class RdpFileParseResult(val file: RdpFile, val warnings: List<String>)

/**
 * Parser for the Remote Desktop Connection file format (`.rdp`): one `key:type:value` per line,
 * where the type is a single character and the value runs to the end of the line (it may contain
 * colons — `loadbalanceinfo` holds a `tsv://…` URL).
 *
 * Pure and platform-independent, like [app.skerry.shared.ssh.SshConfigParser]: the caller supplies
 * the text read through the file picker. The file comes from outside the app, so line count and
 * value length are capped — a picked file must not be able to turn an import into an allocation
 * storm.
 */
object RdpFileParser {

    /** Far above any real `.rdp` (mstsc writes a few dozen lines; signed farm files, a hundred). */
    const val MAX_LINES = 2000

    /**
     * Longest value kept. Legitimate settings are short; the outlier is `signature`, a base64 blob
     * of several kilobytes we have no use for, which this drops along with anything else oversized.
     */
    const val MAX_VALUE_LENGTH = 4096

    /**
     * Parse [text] into settings. Lines that are not `key:type:value` are skipped silently (comments
     * and blanks are ordinary in these files); a line that looks like a setting but can't be used
     * produces a warning.
     */
    fun parse(text: String): RdpFileParseResult {
        val entries = LinkedHashMap<String, RdpFileEntry>()
        val warnings = mutableListOf<String>()
        var truncated = false

        for (raw in normalize(text).lineSequence()) {
            if (entries.size >= MAX_LINES) {
                truncated = true
                break
            }
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

            val keyEnd = line.indexOf(':')
            if (keyEnd <= 0 || line.length < keyEnd + 3 || line[keyEnd + 2] != ':') continue
            val key = line.substring(0, keyEnd).trim().lowercase()
            val type = line[keyEnd + 1].lowercaseChar()
            if (key.isEmpty() || type !in TYPES) continue
            val value = line.substring(keyEnd + 3)

            if (value.length > MAX_VALUE_LENGTH) {
                // The publisher signature and the DPAPI password blob are expected to be huge and
                // are of no use to us; warning about them would make every signed farm file look
                // like it lost something.
                if (key !in BULKY) warnings += "$key: value longer than $MAX_VALUE_LENGTH characters"
                continue
            }
            if (type == RdpFile.TYPE_INT && value.trim().toIntOrNull() == null) {
                warnings += "$key: '$value' is not a number"
                continue
            }
            // First occurrence wins: mstsc writes each setting once, and a file that repeats one is
            // more likely appending than correcting.
            entries.getOrPut(key) {
                RdpFileEntry(key, type, if (type == RdpFile.TYPE_INT) value.trim() else value)
            }
        }
        if (truncated) warnings += "file longer than $MAX_LINES settings, the rest was skipped"
        return RdpFileParseResult(RdpFile(entries), warnings)
    }

    /**
     * Drop the byte-order mark and the NUL filler a UTF-16 file leaves behind. mstsc saves `.rdp` as
     * UTF-16LE, but the picker reads text as UTF-8 (every other file we import is UTF-8), which
     * turns the BOM into a replacement character and every ASCII character into itself followed by a
     * NUL. Nothing in this format legitimately contains either, so removing them costs nothing and
     * is what makes a file straight out of Windows importable.
     */
    private fun normalize(text: String): String = text.filterNot { it in NOISE }

    /** NUL filler, byte-order mark and the replacement character a mis-decoded BOM leaves. */
    private val NOISE = charArrayOf(Char(0), Char(0xFEFF), Char(0xFFFD))

    /** Settings that are legitimately over the size cap and that nothing here reads. */
    private val BULKY = setOf("signature", "signscope", "password 51")

    private val TYPES = charArrayOf(RdpFile.TYPE_STRING, RdpFile.TYPE_INT, RdpFile.TYPE_BINARY)
}
