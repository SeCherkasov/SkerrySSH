package app.skerry.shared.ssh

/**
 * One importable entry parsed from an OpenSSH `ssh_config` file. [alias] is the `Host` name (the
 * label the user typed to connect); [hostName] is the resolved dial target (`HostName`, or the alias
 * itself when absent, matching OpenSSH). [user]/[proxyJump]/[identityFile] are `null` when the file
 * doesn't specify them. [proxyJump] holds only the first-hop host token (user/port stripped) — the
 * importer resolves it against the other imported aliases.
 */
data class SshConfigHost(
    val alias: String,
    val hostName: String,
    val port: Int,
    val user: String?,
    val proxyJump: String?,
    val identityFile: String?,
)

/**
 * Outcome of parsing an `ssh_config` file: the importable [hosts] and human-readable [warnings] for
 * directives Skerry can't honour (e.g. `Include`, `Match`), so the UI can tell the user what was
 * skipped rather than pretending it imported everything.
 */
data class SshConfigParseResult(
    val hosts: List<SshConfigHost>,
    val warnings: List<String>,
)

/**
 * Parser for OpenSSH client config (`~/.ssh/config`). Pure and platform-independent — the caller
 * supplies the file text (read via the platform file picker) so it stays testable in commonTest.
 *
 * Scope is deliberately a pragmatic subset (v1): `Host` blocks with `HostName`, `Port`, `User`,
 * `ProxyJump`, `IdentityFile`. Wildcard patterns (`*`, `?`) and negations (`!`) are honoured for
 * option matching but never become hosts themselves. `Match` blocks and `Include` are skipped with a
 * warning — evaluating them needs a live host/connection context the importer doesn't have.
 *
 * Option resolution follows OpenSSH's "first obtained value wins" rule: for a given alias each
 * keyword takes the value from the first (file-order) block whose pattern list matches it, so a
 * trailing `Host *` acts as a fallback and a specific block earlier in the file overrides it.
 */
object SshConfigParser {

    /**
     * Upper bound on parsed `Host`/`Match` blocks. Far above any real `~/.ssh/config` (hundreds of
     * hosts at most).
     */
    private const val MAX_BLOCKS = 4000

    /**
     * Upper bound on total `Host` patterns across the whole file. [MAX_BLOCKS] caps the number of
     * `Host` *lines*, but a single line (`Host a b c … zN`) can carry any number of aliases, so the
     * block count alone does not bound resolution cost. Since every alias is resolved against every
     * block, this cap is what keeps an untrusted file from forcing an O(aliases × patterns) blow-up.
     */
    private const val MAX_PATTERNS = 8000

    /** A real host alias/pattern is short; anything longer is dropped so it can't inflate the
     *  glob matcher's per-call cost (the two-pointer matcher is O(pattern × value) worst-case). */
    private const val MAX_PATTERN_LEN = 256

    /** Hard ceiling on pattern comparisons during resolution — a deterministic backstop so no crafted
     *  combination of blocks and aliases can peg the import thread regardless of the caps above. */
    private const val MAX_RESOLVE_STEPS = 10_000_000L

    private class Block(val patterns: List<String>) {
        // First value wins per keyword (OpenSSH semantics); keys are lowercased.
        val options = LinkedHashMap<String, String>()

        // Precomputed once per block: re-filtering these on every matches() call was both O(patterns)
        // per call and a large temporary allocation (an OOM risk on a crafted many-pattern line).
        private val positive = patterns.filter { !it.startsWith("!") }
        private val negative = patterns.filter { it.startsWith("!") }.map { it.substring(1) }

        fun matches(alias: String): Boolean =
            positive.any { globMatches(it, alias) } && negative.none { globMatches(it, alias) }
    }

    fun parse(text: String): SshConfigParseResult {
        val blocks = mutableListOf<Block>()
        val warnings = LinkedHashSet<String>()
        var current: Block? = null
        var ignoringMatch = false
        var totalPatterns = 0

        for (rawLine in text.lineSequence()) {
            if (blocks.size >= MAX_BLOCKS || totalPatterns >= MAX_PATTERNS) {
                warnings.add("Too many entries — not all lines were parsed")
                break
            }
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val (keyword, args) = splitKeyword(trimmed) ?: continue
            when (keyword.lowercase()) {
                "host" -> {
                    ignoringMatch = false
                    val tokens = tokenize(args)
                    val patterns = tokens.filter { it.length <= MAX_PATTERN_LEN }
                    if (patterns.size < tokens.size) warnings.add("Some host patterns were too long and skipped")
                    val capped = patterns.take(MAX_PATTERNS - totalPatterns)
                    if (capped.size < patterns.size) warnings.add("Too many entries — not all lines were parsed")
                    if (capped.isEmpty()) {
                        current = null
                    } else {
                        totalPatterns += capped.size
                        current = Block(capped).also { blocks.add(it) }
                    }
                }
                "match" -> {
                    ignoringMatch = true
                    current = null
                    warnings.add("Match blocks are ignored")
                }
                "include" -> warnings.add("Include is not supported")
                else -> {
                    if (ignoringMatch) continue
                    val value = tokenize(args).firstOrNull() ?: continue
                    // Options before the first Host apply globally (OpenSSH) — model as a `*` block.
                    val block = current ?: Block(listOf("*")).also { blocks.add(it); current = it; totalPatterns++ }
                    block.options.putIfAbsent(keyword.lowercase(), value)
                }
            }
        }

        val aliases = LinkedHashSet<String>()
        for (block in blocks) {
            for (pattern in block.patterns) {
                if (!pattern.startsWith("!") && !pattern.contains('*') && !pattern.contains('?')) {
                    aliases.add(pattern)
                }
            }
        }

        var steps = 0L
        val hosts = mutableListOf<SshConfigHost>()
        for (alias in aliases) {
            if (steps > MAX_RESOLVE_STEPS) {
                warnings.add("Too many entries — not all hosts were imported")
                break
            }
            fun resolve(key: String): String? {
                for (block in blocks) {
                    steps += block.patterns.size
                    if (block.matches(alias)) block.options[key]?.let { return it }
                }
                return null
            }
            hosts.add(
                SshConfigHost(
                    alias = alias,
                    hostName = resolve("hostname") ?: alias,
                    port = resolve("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                    user = resolve("user"),
                    proxyJump = resolve("proxyjump")?.let { firstJumpHop(it) },
                    identityFile = resolve("identityfile"),
                )
            )
        }

        return SshConfigParseResult(hosts, warnings.toList())
    }

    /** Splits a config line into keyword and the remaining argument text, honouring `key value` and
     *  `key=value` (with optional spaces around `=`). Returns null for a keyword-only garbage line. */
    private fun splitKeyword(line: String): Pair<String, String>? {
        var i = 0
        while (i < line.length && !line[i].isWhitespace() && line[i] != '=') i++
        if (i == 0) return null
        val keyword = line.substring(0, i)
        var j = i
        while (j < line.length && line[j].isWhitespace()) j++
        if (j < line.length && line[j] == '=') {
            j++
            while (j < line.length && line[j].isWhitespace()) j++
        }
        return keyword to line.substring(j).trim()
    }

    /**
     * Whitespace-splits arguments, treating a double-quoted run as one token (for names with spaces).
     * An unquoted `#` at a token boundary starts an inline comment and ends the line — so
     * `Host web  # prod` yields just `web`, not the phantom aliases `web`, `#`, `prod`. A `#` inside a
     * token (e.g. `id_rsa#1`) stays literal.
     */
    private fun tokenize(s: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var inQuotes = false
        var started = false
        for (c in s) {
            when {
                c == '"' -> { inQuotes = !inQuotes; started = true }
                c == '#' && !inQuotes && !started -> break
                c.isWhitespace() && !inQuotes -> {
                    if (started) { tokens.add(token.toString()); token.clear(); started = false }
                }
                else -> { token.append(c); started = true }
            }
        }
        if (started) tokens.add(token.toString())
        return tokens
    }

    /** `ProxyJump` can be `none`, a comma-separated chain, and each hop may carry `user@` / `:port`.
     *  Returns the bare host of the first hop, or null for `none`. */
    private fun firstJumpHop(value: String): String? {
        if (value.equals("none", ignoreCase = true)) return null
        val hop = value.substringBefore(",").trim()
        val afterUser = hop.substringAfterLast("@")
        val host = if (afterUser.startsWith("[")) {
            afterUser.substringAfter("[").substringBefore("]")
        } else {
            afterUser.substringBefore(":")
        }
        return host.ifBlank { null }
    }

    /**
     * OpenSSH glob: `*` matches any run (including empty), `?` matches exactly one character;
     * everything else is literal. Deliberately a two-pointer matcher rather than a translated regex:
     * patterns come from an untrusted file, and a regex built from many `*` (`.*.*.*…`) is the classic
     * catastrophic-backtracking (ReDoS) shape on the JVM engine. This algorithm backtracks only the
     * last `*`, so many wildcards can't blow up; its remaining worst case (a `*` followed by a long
     * literal that keeps failing) is O(pattern × value), which is why callers cap the pattern length
     * (MAX_PATTERN_LEN) and the total resolution steps (MAX_RESOLVE_STEPS).
     */
    private fun globMatches(pattern: String, value: String): Boolean {
        var p = 0
        var v = 0
        var star = -1
        var afterStar = 0
        while (v < value.length) {
            when {
                p < pattern.length && (pattern[p] == '?' || pattern[p] == value[v]) -> { p++; v++ }
                p < pattern.length && pattern[p] == '*' -> { star = p; afterStar = v; p++ }
                star != -1 -> { p = star + 1; afterStar++; v = afterStar }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
