package app.skerry.shared.ai

/**
 * Best-effort redaction of obvious secrets from text before sending to a cloud AI
 * ([AiPolicy.Strict]/[AiPolicy.Balanced] policies). Not a full DLP: catches typical
 * passwords/tokens/keys accidentally present in a prompt, not a guarantee against all secrets.
 *
 * Replaces matches with [MASK], preserving line structure (key visible, value hidden).
 */
object SecretRedactor {

    const val MASK = "«redacted»"

    private val PEM = Regex(
        "-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // Authorization header / bearer token in full (including `Authorization: Bearer <token>`).
    private val BEARER = Regex("(?i)(authorization\\s*[:=]\\s*)?bearer\\s+\\S+")

    // key=value / key: value where the key hints at a secret. Matches the key even inside a
    // compound identifier (`DB_PASSWORD`, `client_secret`, `api-key`) — `\b` doesn't work since `_`
    // is a word character, so `[\w.-]*` is allowed around the keyword. Separator is strictly `:`/`=`
    // (not whitespace, or "password for prod" would mask "for" and leak the real secret further on).
    // An unquoted value is captured to end of line (`[^\r\n]*\S`), not a single token `\S+`: otherwise
    // a multi-word secret (`secret = my long passphrase`) or two-word `Authorization: Basic <base64>`
    // would only mask the first word and leak the rest to the cloud.
    private val KEYED = Regex(
        "(?i)\\b([\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|auth(?:orization)?)[\\w.-]*)" +
            "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\r\\n]*\\S)",
    )

    // Long "high-entropy" strings (hex/base64/JWT) — likely tokens.
    private val LONG_TOKEN = Regex("\\b[A-Za-z0-9+/_-]{32,}={0,2}\\b")

    fun redact(text: String): String {
        var out = PEM.replace(text, MASK)
        out = BEARER.replace(out, MASK)
        out = KEYED.replace(out) { m ->
            val key = m.groupValues[1]
            val sep = m.groupValues[2]
            "$key$sep$MASK"
        }
        out = LONG_TOKEN.replace(out, MASK)
        return out
    }
}
