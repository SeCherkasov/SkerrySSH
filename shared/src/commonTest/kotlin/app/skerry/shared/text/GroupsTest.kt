package app.skerry.shared.text

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupsTest {

    @Test
    fun trims_and_maps_blank_to_null() {
        assertEquals("client-acme", normalizeGroup("  client-acme  "))
        assertNull(normalizeGroup(""))
        assertNull(normalizeGroup("   \t "))
        assertNull(normalizeGroup(null))
    }

    @Test
    fun truncates_to_max_length() {
        val long = "p".repeat(MAX_GROUP_LENGTH + 12)
        assertEquals("p".repeat(MAX_GROUP_LENGTH), normalizeGroup(long))
    }

    @Test
    fun truncation_does_not_split_a_surrogate_pair() {
        val emoji = "🚀"
        val straddling = "a".repeat(MAX_GROUP_LENGTH - 1) + emoji
        assertEquals("a".repeat(MAX_GROUP_LENGTH - 1), normalizeGroup(straddling))

        val fitting = "a".repeat(MAX_GROUP_LENGTH - 2) + emoji
        assertEquals(fitting, normalizeGroup(fitting))
    }

    @Test
    fun drops_what_draws_as_nothing() {
        // A folder name is a grouping key: two names that render alike must be one name, or the
        // list grows two "Production" sections nothing can tell apart.
        assertEquals("Production", normalizeGroup("Produc\u200Btion"))
        assertEquals("Production", normalizeGroup("\u202EProduction"))
    }

    @Test
    fun a_pasted_line_break_does_not_become_a_two_line_header() {
        assertEquals("stagingprod", normalizeGroup("staging\nprod"))
    }

    @Test
    fun cap_text_leaves_short_input_alone() {
        assertEquals("prod", capText("prod", MAX_GROUP_LENGTH))
    }

    @Test
    fun records_saved_before_the_field_existed_read_back_ungrouped() {
        // Defaulted, so nothing migrates: an old snippet, runbook or secret is simply unfiled.
        assertNull(Snippet(id = "s-1", label = "df", command = "df -h").group)
        assertNull(Runbook(id = "r-1", label = "drain").group)
        assertNull(Credential(id = "c-1", label = "prod root", secret = CredentialSecret.Password("x")).group)
    }
}
