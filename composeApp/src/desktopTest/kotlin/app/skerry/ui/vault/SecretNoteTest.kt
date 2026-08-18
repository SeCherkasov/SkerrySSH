package app.skerry.ui.vault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_label_note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The note under a secret's name: free-form stored text, filtered on the way to the screen the same
 * way the host tooltip is. The keychain is personal, so this is defence in depth — but the note
 * crosses devices over sync and nothing downstream re-checks it.
 */
@OptIn(ExperimentalTestApi::class)
class SecretNoteTest {

    @Test
    fun `a secret without a note draws nothing at all`() {
        runForm({ SecretNote(null) }) {
            // Not an empty line reserving height under every name that has no remark.
            assertEquals(emptyList(), drawnText())
        }
    }

    @Test
    fun `a note that filters away to nothing draws nothing`() {
        // Escapes, not the raw glyphs: an invisible character in source is unreviewable.
        runForm({ SecretNote("\u200B\u202E") }) {
            assertTrue(drawnText().all { it.isBlank() }, "was ${drawnText()}")
        }
    }

    @Test
    fun `a note keeps its own line breaks — it is prose, not a label`() {
        runForm({ SecretNote("audit access\ndrop after 2026-09-01") }) {
            assertTrue(drawnText().any { it.contains("audit access\ndrop after 2026-09-01") }, "was ${drawnText()}")
        }
    }

    @Test
    fun `a note drops the characters that would let it draw as something else`() {
        runForm({ SecretNote("prod\u202Edb key") }) {
            val drawn = drawnText()
            assertTrue(drawn.any { it.contains("proddb key") }, "the text still reads, was $drawn")
            assertTrue(drawn.none { it.any { c -> c.category == CharCategory.FORMAT } }, "was $drawn")
        }
    }

    @Test
    fun `the note names itself for a reader who cannot see where it sits`() {
        runForm({ SecretNote("audit access") }) {
            // A description replaces the node's own text rather than joining it, so the label and the
            // note have to be joined here or the note itself would go unannounced.
            onNodeWithContentDescription(string(Res.string.vault_label_note) + ", audit access").assertExists()
        }
    }

}
