package app.skerry.ui.host

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_username
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The other two modes of the connection form. [NewConnectionFormTest] covers creating a profile;
 * these are the ones where an id is at stake.
 *
 * Editing must write back into the same profile — a new record instead would leave the old one in
 * the catalog and quietly detach whatever referenced it (tunnels, recents, the session tab).
 * Duplicating must do the opposite: a new id, or "Duplicate" would be an in-place rename.
 */
@OptIn(ExperimentalTestApi::class)
class EditConnectionFormTest {

    @Test
    fun `editing writes back into the same profile`() = runDesktopShell { shell ->
        val original = shell.hosts.hosts.first()
        val count = shell.hosts.hosts.size
        shell.state.openEditModal(original)
        waitForIdle()

        onField(Res.string.conn_field_name).performTextReplacement(RENAMED)
        save()

        assertEquals(count, shell.hosts.hosts.size, "editing added a profile instead of updating one")
        val updated = shell.hosts.find(original.id)
        assertNotNull(updated, "the edited profile lost its id")
        assertEquals(RENAMED, updated.label)
        assertEquals(original.address, updated.address, "an untouched field was rewritten")
    }

    /** The form opens on the profile's own values — an empty form would silently blank the record. */
    @Test
    fun `the edit form opens prefilled from the profile`() = runDesktopShell { shell ->
        val original = shell.hosts.hosts.first()
        shell.state.openEditModal(original)
        waitForIdle()

        // Only the address is changed; everything else has to survive the round trip.
        onField(Res.string.conn_field_host_address).performTextReplacement(NEW_ADDRESS)
        save()

        val updated = shell.hosts.find(original.id)
        assertNotNull(updated)
        assertEquals(NEW_ADDRESS, updated.address)
        assertEquals(original.label, updated.label)
        assertEquals(original.username, updated.username)
        assertEquals(original.port, updated.port)
    }

    @Test
    fun `duplicating creates a second profile rather than renaming the first`() = runDesktopShell { shell ->
        val original = shell.hosts.hosts.first()
        val count = shell.hosts.hosts.size
        shell.state.openDuplicateModal(original)
        waitForIdle()

        onField(Res.string.conn_field_name).performTextReplacement(COPY)
        save()

        assertEquals(count + 1, shell.hosts.hosts.size, "duplicate did not add a profile")
        assertNotNull(shell.hosts.find(original.id), "the original profile is gone")
        val copy = shell.hosts.hosts.single { it.label == COPY }
        assertNotEquals(original.id, copy.id)
        assertEquals(original.address, copy.address, "the copy did not inherit the profile")
    }

    /** Tags are typed into their own box and committed as pills; an uncommitted one must not be lost. */
    @Test
    fun `a tag left uncommitted in the box is still saved`() = runDesktopShell { shell ->
        onNodeWithTag(UiTags.NEW_CONNECTION).performClick()
        waitForIdle()
        onField(Res.string.conn_field_name).performTextInput(TAGGED)
        onField(Res.string.conn_field_host_address).performTextInput(NEW_ADDRESS)
        onField(Res.string.conn_field_username).performTextInput("root")
        onField(Res.string.conn_field_tags).performTextInput(TAG)
        save()

        val saved = shell.hosts.hosts.single { it.label == TAGGED }
        assertTrue(TAG in saved.tags, "the tag still in the box was dropped on save: ${saved.tags}")
    }

    private fun ComposeUiTest.save() {
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
    }
}

private const val RENAMED = "prod-web-01-renamed"
private const val NEW_ADDRESS = "10.9.9.9"
private const val COPY = "prod-web-01 copy"
private const val TAGGED = "tagged-box"
private const val TAG = "staging"
