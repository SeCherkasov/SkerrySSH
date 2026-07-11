package app.skerry.ui.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasswordStrengthTest {

    @Test
    fun empty_password_has_no_strength() {
        assertNull(passwordStrength(""))
    }

    @Test
    fun blank_password_is_weak_not_fair() {
        assertEquals(PasswordStrength.Weak, passwordStrength("            "))
    }

    @Test
    fun below_minimum_length_is_weak_regardless_of_variety() {
        // Anything shorter than MIN_MASTER_PASSWORD_LENGTH is always Weak, even with every
        // character class present — the meter must not read "Good" while the create button is
        // still disabled for being too short.
        assertEquals(PasswordStrength.Weak, passwordStrength("aB1!"))
        assertEquals(PasswordStrength.Weak, passwordStrength("abcdefg"))
        assertEquals(PasswordStrength.Weak, passwordStrength("aB1!aB1!")) // 8 chars, 4 classes
        assertEquals(PasswordStrength.Weak, passwordStrength("aB1!aB1!ab")) // 10 chars
        assertEquals(PasswordStrength.Weak, passwordStrength("aB1!aB1!abc")) // 11 chars, one short
    }

    @Test
    fun exactly_minimum_length_is_at_least_fair() {
        // Reaching the minimum length flips the meter off Weak, matching the create button.
        assertEquals(PasswordStrength.Fair, passwordStrength("aaaaaaaaaaaa")) // 12 chars, 1 class
    }

    @Test
    fun long_single_class_password_is_fair() {
        assertEquals(PasswordStrength.Fair, passwordStrength("passwordword"))
    }

    @Test
    fun medium_length_two_classes_is_good() {
        assertEquals(PasswordStrength.Good, passwordStrength("password1234"))
    }

    @Test
    fun long_diverse_password_is_strong() {
        assertEquals(PasswordStrength.Strong, passwordStrength("P@ssw0rd!Harbor7"))
    }

    // masterPasswordIssue: the shared create-screen gate and its hint reason.

    @Test
    fun empty_password_has_no_issue_yet() {
        // Nothing typed is not an error: the hint stays hidden until the user starts typing.
        assertNull(masterPasswordIssue(""))
    }

    @Test
    fun short_password_reports_too_short() {
        assertEquals(MasterPasswordIssue.TooShort, masterPasswordIssue("aB1!aB1!abc"))
    }

    @Test
    fun blank_password_reports_blank_even_when_long_enough() {
        // 12+ spaces satisfies the length rule, so a "use at least N characters" hint would be
        // wrong and unfixable by adding more spaces; the reason must be Blank.
        assertEquals(MasterPasswordIssue.Blank, masterPasswordIssue("            "))
        assertEquals(MasterPasswordIssue.Blank, masterPasswordIssue("      "))
    }

    @Test
    fun acceptable_password_has_no_issue() {
        assertNull(masterPasswordIssue("aaaaaaaaaaaa"))
        assertNull(masterPasswordIssue("correct horse battery")) // inner spaces are fine
    }
}
