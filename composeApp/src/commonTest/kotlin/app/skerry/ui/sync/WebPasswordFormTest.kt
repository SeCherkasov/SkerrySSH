package app.skerry.ui.sync

import app.skerry.shared.sync.MAX_WEB_PASSWORD_LENGTH
import app.skerry.shared.sync.MIN_WEB_PASSWORD_LENGTH
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Web access form's gate. The server answers 400 outside its length bounds and the app would
 * have to render that as a protocol error, so the bounds are enforced before the call — and the
 * repeat field exists because a typo here locks the account zone out of reach with no way to notice
 * except by failing to sign in.
 */
class WebPasswordFormTest {

    private fun form(password: String, confirm: String = password) = WebPasswordForm(password, confirm)

    @Test
    fun `nothing typed yet is not an error`() {
        val empty = WebPasswordForm()
        assertFalse(empty.tooShort)
        assertFalse(empty.mismatch)
        assertFalse(empty.canSubmit)
    }

    @Test
    fun `the server's bounds are the form's bounds`() {
        val short = "p".repeat(MIN_WEB_PASSWORD_LENGTH - 1)
        assertTrue(form(short).tooShort)
        assertFalse(form(short).canSubmit)

        val atFloor = "p".repeat(MIN_WEB_PASSWORD_LENGTH)
        assertFalse(form(atFloor).tooShort)
        assertTrue(form(atFloor).canSubmit)

        val atCeiling = "p".repeat(MAX_WEB_PASSWORD_LENGTH)
        assertFalse(form(atCeiling).tooLong)
        assertTrue(form(atCeiling).canSubmit)

        val past = "p".repeat(MAX_WEB_PASSWORD_LENGTH + 1)
        assertTrue(form(past).tooLong)
        assertFalse(form(past).canSubmit)
    }

    @Test
    fun `the repeat has to agree, once it is typed`() {
        // An untouched repeat field is not yet a mismatch — the message would appear on the first
        // keystroke of the password field, before the user has had a chance to disagree with anything.
        assertFalse(WebPasswordForm(password = "web-pw-123", confirm = "").mismatch)
        assertFalse(WebPasswordForm(password = "web-pw-123", confirm = "").canSubmit)

        assertTrue(WebPasswordForm(password = "web-pw-123", confirm = "web-pw-124").mismatch)
        assertFalse(WebPasswordForm(password = "web-pw-123", confirm = "web-pw-124").canSubmit)
    }

    @Test
    fun `whitespace is a character like any other`() {
        // Unlike the master password, this one is typed into a browser and may legitimately be a
        // passphrase with spaces; only the length is the server's rule, and it counts them.
        assertTrue(form("pass word 1").canSubmit)
    }
}
