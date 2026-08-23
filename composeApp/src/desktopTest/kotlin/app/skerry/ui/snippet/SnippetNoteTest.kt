package app.skerry.ui.snippet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The note under a snippet's name in the run panel: the same filtered, self-naming block the vault
 * gives a secret's note. A snippet crosses devices over sync and can arrive from a peer.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetNoteTest {

    @Test
    fun `a snippet without a note draws nothing at all`() {
        runForm({ SnippetNote(null) }) {
            assertEquals(emptyList(), drawnText())
        }
    }

    @Test
    fun `a note that filters away to nothing draws nothing`() {
        // Escapes, not the raw glyphs: an invisible character in source is unreviewable.
        runForm({ SnippetNote("\u200B\u202E") }) {
            assertTrue(drawnText().all { it.isBlank() }, "was ${drawnText()}")
        }
    }

    @Test
    fun `a note keeps its own line breaks — it is prose, not a label`() {
        runForm({ SnippetNote("drains the canary pool\nsafe to re-run") }) {
            assertTrue(drawnText().any { it.contains("drains the canary pool\nsafe to re-run") }, "was ${drawnText()}")
        }
    }

    @Test
    fun `a note drops the characters that would let it draw as something else`() {
        runForm({ SnippetNote("stop\u202Edb first") }) {
            val drawn = drawnText()
            assertTrue(drawn.any { it.contains("stopdb first") }, "the text still reads, was $drawn")
            assertTrue(drawn.none { it.any { c -> c.category == CharCategory.FORMAT } }, "was $drawn")
        }
    }

    @Test
    fun `the note names itself for a reader who cannot see where it sits`() {
        runForm({ SnippetNote("drains the canary pool") }) {
            onNodeWithContentDescription(string(Res.string.lib_snippets_field_notes) + ", drains the canary pool").assertExists()
        }
    }
}
