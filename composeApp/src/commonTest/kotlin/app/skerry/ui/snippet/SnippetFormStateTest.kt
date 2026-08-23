package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.text.MAX_NOTES_LENGTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetFormStateTest {

    private fun entry(notes: String? = null) =
        SnippetEntry(Snippet(id = "s1", label = "Rollout", command = "kubectl apply -f -", notes = notes))

    /** Editing a snippet and saving it unchanged must not drop the note it already carried. */
    @Test
    fun `an edited snippet keeps the note it arrived with`() {
        val form = SnippetFormState.fromEntry(entry(notes = "Drains the canary pool first"))

        assertEquals("Drains the canary pool first", form.notes)
        assertEquals("Drains the canary pool first", form.toDraft().notes)
    }

    /** A note is optional: an empty field is no note at all, not an empty one. */
    @Test
    fun `a blank note saves as none`() {
        val form = SnippetFormState.fromEntry(entry(notes = "gone"))
        form.notes = "   \n  "

        assertNull(form.toDraft().notes)
    }

    @Test
    fun `a note is trimmed before it is stored`() {
        val form = SnippetFormState.fromEntry(null)
        form.notes = "  runs as root  "

        assertEquals("runs as root", form.toDraft().notes)
    }

    @Test
    fun `a snippet without a note stays without one`() {
        assertEquals("", SnippetFormState.fromEntry(entry()).notes)
        assertNull(SnippetFormState.fromEntry(entry()).toDraft().notes)
    }

    /**
     * A note is stored the way every other note is (shared/text/Notes.kt): capped, so a pasted log
     * cannot bloat the vault record the whole sync pushes in one body.
     */
    @Test
    fun `a note longer than the cap is cut on the way to the store`() {
        val form = SnippetFormState.fromEntry(null)
        form.notes = "a".repeat(MAX_NOTES_LENGTH + 400)

        assertEquals(MAX_NOTES_LENGTH, form.toDraft().notes?.length)
    }
}
