package app.skerry.shared.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostNotesTest {

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
    fun keeps_inner_line_breaks() {
        assertEquals("reboot window: Sun 03:00\nask ops first", normalizeNotes("reboot window: Sun 03:00\nask ops first\n"))
    }

    @Test
    fun host_notes_default_to_null_for_profiles_saved_before_the_field_existed() {
        assertNull(Host(id = "1", label = "web", address = "10.0.0.1", username = "root").notes)
    }
}
