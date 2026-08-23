package app.skerry.shared.team

import app.skerry.shared.host.Host
import app.skerry.shared.snippet.Snippet
import app.skerry.shared.vault.RecordType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamShareTest {

    private fun strip(payload: String, fields: Set<String>) =
        stripShareFields(payload.encodeToByteArray(), fields).decodeToString()

    @Test
    fun a_shared_snippet_leaves_the_owners_folder_behind() {
        // Serialized by the record's own serializer rather than hand-written: the strip list names
        // fields as strings, so a renamed property would otherwise stop stripping and leave every
        // test green.
        val payload = Json.encodeToString(
            Snippet(id = "s1", label = "Drain", command = "drain", group = "client-acme", notes = "run first"),
        )

        val shared = strip(payload, LIBRARY_SHARE_STRIP)

        // The folder is where the owner keeps it, not part of what is shared — a name like a
        // customer's would otherwise reach everyone holding the space key.
        assertFalse(shared.contains("client-acme"))
        // What describes the record itself stays.
        assertTrue(shared.contains("run first"))
        assertTrue(shared.contains("drain"))
    }

    @Test
    fun a_shared_host_still_sheds_its_folder_and_its_secret_reference() {
        val payload = Json.encodeToString(
            Host(
                id = "h1", label = "web", address = "10.0.0.1", username = "root",
                group = "Production", credentialId = "c-1", notes = "maintenance Sun",
            ),
        )

        val shared = strip(payload, HOST_SHARE_STRIP)

        assertFalse(shared.contains("Production"))
        assertFalse(shared.contains("c-1"))
        assertTrue(shared.contains("maintenance Sun"))
    }

    @Test
    fun every_library_kind_sheds_the_folder_and_a_host_sheds_its_secret_too() {
        // The one thing the share button asks for. A kind that answers with an empty set shares the
        // owner's filing along with the record, which is what this used to do.
        assertEquals(LIBRARY_SHARE_STRIP, shareStripFields(RecordType.SNIPPET))
        assertEquals(LIBRARY_SHARE_STRIP, shareStripFields(RecordType.RUNBOOK))
        assertEquals(HOST_SHARE_STRIP, shareStripFields(RecordType.HOST))
        assertTrue(shareStripFields(RecordType.SNIPPET).contains("group"))
    }

    @Test
    fun a_kind_the_share_picker_does_not_offer_is_refused_rather_than_guessed_at() {
        // Fail closed: an `else` that strips only the folder would answer for a credential too, and
        // the day one is wired into the picker it would ship key material to the whole space.
        assertFailsWith<IllegalStateException> { shareStripFields(RecordType.CREDENTIAL) }
        assertFailsWith<IllegalStateException> { shareStripFields(RecordType.TEAM) }
    }

    @Test
    fun a_payload_that_is_not_json_is_shared_as_it_is() {
        assertEquals("not json", strip("not json", LIBRARY_SHARE_STRIP))
    }
}
