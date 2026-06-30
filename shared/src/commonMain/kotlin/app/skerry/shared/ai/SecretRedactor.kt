package app.skerry.shared.ai

/**
 * Грубая, но полезная зачистка очевидных секретов из текста ПЕРЕД отправкой в облачный AI
 * (политики [AiPolicy.Strict]/[AiPolicy.Balanced]). Это не полноценный DLP: цель — не пропустить
 * типовые пароли/токены/ключи, случайно попавшие в промпт, а не гарантировать отсутствие любых секретов.
 *
 * Заменяет найденное на [MASK], сохраняя структуру строки (ключ виден, значение скрыто).
 */
object SecretRedactor {

    const val MASK = "«redacted»"

    private val PEM = Regex(
        "-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // Заголовок авторизации / bearer-токен целиком (в т.ч. `Authorization: Bearer <token>`).
    private val BEARER = Regex("(?i)(authorization\\s*[:=]\\s*)?bearer\\s+\\S+")

    // key=value / key: value, где ключ намекает на секрет. Ключ ловится даже как часть составного
    // идентификатора (`DB_PASSWORD`, `client_secret`, `api-key`) — `\b` не годится, т.к. `_` — словесный
    // символ; поэтому вокруг ключевого слова допускаем `[\w.-]*`. Разделитель строго `:`/`=` (не пробел —
    // иначе «password for prod» ложно маскировало бы «for», а реальный секрет уехал бы дальше в тексте).
    private val KEYED = Regex(
        "(?i)\\b([\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|auth(?:orization)?)[\\w.-]*)" +
            "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)",
    )

    // Длинные «энтропийные» строки (hex/base64/JWT) — вероятные токены.
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
