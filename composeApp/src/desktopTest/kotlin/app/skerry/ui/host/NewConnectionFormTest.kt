package app.skerry.ui.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.onScreen
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_port
import app.skerry.ui.generated.resources.conn_field_username
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The "new connection" form, filled in the way a user fills it: by clicking into fields and typing.
 *
 * [NewConnectionFormStateTest] already covers the validation itself — what `canSave` returns for a
 * given set of values. This is the part above it: that the fields are wired to those values, that
 * the Save button reflects the verdict, and that pressing it puts a profile in the catalog.
 */
@OptIn(ExperimentalTestApi::class)
class NewConnectionFormTest {

    @Test
    fun `a host typed into the form lands in the catalog`() = runDesktopShell { shell ->
        val before = shell.hosts.hosts.size
        openForm()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.hosts.hosts.singleOrNull { it.label == NAME }
        assertNotNull(saved, "the form saved nothing — catalog still has $before profiles")
        assertEquals(ADDRESS, saved.address)
        assertEquals(USER, saved.username)
        // Port is prefilled by the form, not typed: the profile must carry it anyway.
        assertEquals(DEFAULT_SSH_PORT, saved.port)
    }

    /** Save stays shut until the profile could actually be dialled — an SSH host needs a user. */
    @Test
    fun `save is refused until the required fields are filled`() = runDesktopShell {
        openForm()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onField(Res.string.conn_field_name).performTextInput(NAME)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onField(Res.string.conn_field_username).performTextInput(USER)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
    }

    /** A port that is not a number is not a port: the form must not hand it on as one. */
    @Test
    fun `a non-numeric port keeps save shut`() = runDesktopShell {
        openForm()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()

        onField(Res.string.conn_field_port).performTextReplacement("not-a-port")
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `cancelling the form writes nothing`() = runDesktopShell { shell ->
        val before = shell.hosts.hosts.map { it.id }
        openForm()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()

        assertNull(shell.hosts.hosts.firstOrNull { it.label == NAME }, "a cancelled form still saved")
        assertEquals(before, shell.hosts.hosts.map { it.id })
    }

    /** Reopening after a cancel must not show what was typed the previous time. */
    @Test
    fun `the form comes back empty after a cancel`() = runDesktopShell {
        openForm()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()

        openForm()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openForm() {
        onNodeWithTag(UiTags.NEW_CONNECTION).performClick()
        waitForIdle()
        onScreen(UiTags.FORM_SAVE).assertIsDisplayed()
    }
}

private const val NAME = "test-box"
private const val ADDRESS = "10.0.0.99"
private const val USER = "deploy"
private const val DEFAULT_SSH_PORT = 22
