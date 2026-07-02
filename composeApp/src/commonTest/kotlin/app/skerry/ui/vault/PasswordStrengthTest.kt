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
    fun short_password_is_weak_regardless_of_variety() {
        // < 8 символов — всегда Weak, даже со всеми классами символов.
        assertEquals(PasswordStrength.Weak, passwordStrength("aB1!"))
        assertEquals(PasswordStrength.Weak, passwordStrength("abcdefg"))
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
}
