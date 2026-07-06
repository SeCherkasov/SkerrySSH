package app.skerry.ui.vault

/** Master password strength rating for the vault-creation screen indicator. */
enum class PasswordStrength { Weak, Fair, Good, Strong }

/**
 * Rough password strength heuristic based on length and character-class count (lower/upper case,
 * digits, other). Not a cryptographic entropy metric, only a UX hint. Empty input returns `null`
 * (indicator hidden); under 8 chars is always [PasswordStrength.Weak]. Pure function, covered by
 * [PasswordStrengthTest].
 */
fun passwordStrength(password: String): PasswordStrength? {
    if (password.isEmpty()) return null
    // Whitespace-only password has no real strength; don't rate it above Weak.
    if (password.isBlank()) return PasswordStrength.Weak
    val len = password.length
    if (len < 8) return PasswordStrength.Weak

    var classes = 0
    if (password.any { it.isLowerCase() }) classes++
    if (password.any { it.isUpperCase() }) classes++
    if (password.any { it.isDigit() }) classes++
    if (password.any { !it.isLetterOrDigit() }) classes++

    var score = 1 // length already >= 8
    if (len >= 12) score++
    if (len >= 16) score++
    if (classes >= 2) score++
    if (classes >= 3) score++

    return when {
        score <= 2 -> PasswordStrength.Fair
        score == 3 -> PasswordStrength.Good
        else -> PasswordStrength.Strong
    }
}
