package app.skerry.shared.text

import app.skerry.shared.host.Host
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotesTest {

    @Test
    fun trims_and_maps_blank_to_null() {
        assertEquals("root password is in 1Password", normalizeNotes("  root password is in 1Password  "))
        assertNull(normalizeNotes(""))
        assertNull(normalizeNotes("   \n\t "))
    }

    @Test
    fun truncates_to_max_length() {
        val long = "a".repeat(MAX_NOTES_LENGTH + 25)
        assertEquals("a".repeat(MAX_NOTES_LENGTH), normalizeNotes(long))
    }

    @Test
    fun trims_before_truncating_so_padding_does_not_eat_the_budget() {
        val padded = "   " + "b".repeat(MAX_NOTES_LENGTH) + "   "
        assertEquals("b".repeat(MAX_NOTES_LENGTH), normalizeNotes(padded))
    }

    @Test
    fun truncation_does_not_split_a_surrogate_pair() {
        // An emoji straddling the limit: cutting between its halves would leave a lone surrogate,
        // which the UTF-8 encoder later replaces with U+FFFD — corruption, not truncation.
        val emoji = "🚀" // rocket, one code point / two UTF-16 units
        val straddling = "a".repeat(MAX_NOTES_LENGTH - 1) + emoji
        val capped = normalizeNotes(straddling)
        assertEquals("a".repeat(MAX_NOTES_LENGTH - 1), capped)
        assertEquals(false, capped?.lastOrNull()?.isHighSurrogate())

        // A pair that ends exactly on the limit is kept whole.
        val fitting = "a".repeat(MAX_NOTES_LENGTH - 2) + emoji
        assertEquals(fitting, normalizeNotes(fitting))
    }

    @Test
    fun keeps_inner_line_breaks() {
        assertEquals("reboot window: Sun 03:00\nask ops first", normalizeNotes("reboot window: Sun 03:00\nask ops first\n"))
    }

    @Test
    fun host_notes_default_to_null_for_profiles_saved_before_the_field_existed() {
        assertNull(Host(id = "1", label = "web", address = "10.0.0.1", username = "root").notes)
    }

    @Test
    fun credential_notes_default_to_null_for_secrets_saved_before_the_field_existed() {
        assertNull(Credential(id = "c-1", label = "prod root", secret = CredentialSecret.Password("x")).note)
    }
}
