package app.skerry.ui.vault

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_password_blank
import app.skerry.ui.generated.resources.shell_password_min_length
import org.jetbrains.compose.resources.stringResource

/** Master password strength rating for the vault-creation screen indicator. */
enum class PasswordStrength { Weak, Fair, Good, Strong }

/** Why the master password can't be accepted yet, for the create-screen gate and hint. */
enum class MasterPasswordIssue { TooShort, Blank }

/**
 * The reason master-password creation is blocked, or `null` when acceptable. Empty input is also
 * `null`: nothing typed yet is not an error, so the hint stays hidden. Shared by the desktop and
 * mobile create screens so the gate and its wording can't drift between platforms
 * ([VaultGateController] re-checks length on create). Distinguishes a blank (all-whitespace)
 * password from a short one: telling the user to "use at least N characters" when 12 spaces are
 * already typed is a dead end no amount of extra spaces fixes.
 */
fun masterPasswordIssue(password: String): MasterPasswordIssue? = when {
    password.isEmpty() -> null
    password.isBlank() -> MasterPasswordIssue.Blank
    password.length < MIN_MASTER_PASSWORD_LENGTH -> MasterPasswordIssue.TooShort
    else -> null
}

/** Localized create-screen hint for [MasterPasswordIssue], shared by both platforms. */
@Composable
fun masterPasswordHint(issue: MasterPasswordIssue): String = when (issue) {
    MasterPasswordIssue.TooShort -> stringResource(Res.string.shell_password_min_length, MIN_MASTER_PASSWORD_LENGTH)
    MasterPasswordIssue.Blank -> stringResource(Res.string.shell_password_blank)
}

/**
 * Rough password strength heuristic based on length and character-class count (lower/upper case,
 * digits, other). Not a cryptographic entropy metric, only a UX hint. Empty input returns `null`
 * (indicator hidden); anything below [MIN_MASTER_PASSWORD_LENGTH] is always [PasswordStrength.Weak]
 * so the meter never reads "Good" while vault creation is still blocked on length. Pure function,
 * covered by [PasswordStrengthTest].
 */
fun passwordStrength(password: String): PasswordStrength? {
    if (password.isEmpty()) return null
    // Whitespace-only password has no real strength; don't rate it above Weak.
    if (password.isBlank()) return PasswordStrength.Weak
    val len = password.length
    if (len < MIN_MASTER_PASSWORD_LENGTH) return PasswordStrength.Weak

    var classes = 0
    if (password.any { it.isLowerCase() }) classes++
    if (password.any { it.isUpperCase() }) classes++
    if (password.any { it.isDigit() }) classes++
    if (password.any { !it.isLetterOrDigit() }) classes++

    var score = 2 // length already >= MIN_MASTER_PASSWORD_LENGTH
    if (len >= 16) score++
    if (classes >= 2) score++
    if (classes >= 3) score++

    return when {
        score <= 2 -> PasswordStrength.Fair
        score == 3 -> PasswordStrength.Good
        else -> PasswordStrength.Strong
    }
}
