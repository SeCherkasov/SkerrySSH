package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The caret tag names a colleague from their account id, which is that colleague's own and which
 * the sync server bounds in length and nothing else. It is drawn at the cursor of the host's live
 * shell — the surface #312 was about, on the one screen the host is watching while somebody types.
 */
class CollaboratorTagTest {

    @Test
    fun `an ordinary account is drawn by its local part`() {
        assertEquals("maya", collaboratorTag("maya@example.com"))
    }

    @Test
    fun `a bidi override cannot make one colleague draw as another`() {
        assertEquals("maya", collaboratorTag("\u202Emaya@example.com"))
        assertEquals("mayaadmin", collaboratorTag("maya\u202Eadmin@example.com"))
    }

    /** A 320-char local part is registrable; unbounded, the tag paints a block over the terminal. */
    @Test
    fun `a padded account cannot paint over the grid`() {
        assertTrue(collaboratorTag("a".repeat(320) + "@example.com").length <= 24)
    }
}
