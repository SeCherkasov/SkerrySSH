package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_field_host_address
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.generated.resources.conn_field_tags
import app.skerry.ui.generated.resources.conn_field_username
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's "new connection" sheet. The desktop modal has the same job and its own test
 * ([app.skerry.ui.host.NewConnectionFormTest]) — both exist because parity is a rule here, and the
 * two forms are separate composables over one shared [app.skerry.ui.host.NewConnectionFormState].
 *
 * The sheet's Save differs from the desktop one in kind: it is always clickable and decides inside
 * whether to act, so what is checked is the outcome of pressing it, not whether it looks disabled.
 */
@OptIn(ExperimentalTestApi::class)
class MobileConnectionFormTest {

    @Test
    fun `a host typed into the sheet lands in the catalog`() = runMobileShell { shell ->
        openSheet()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        save()

        val saved = shell.hosts.hosts.singleOrNull { it.label == NAME }
        assertNotNull(saved, "the sheet saved nothing")
        assertEquals(ADDRESS, saved.address)
        assertEquals(USER, saved.username)
    }

    /** Pressing Save on an incomplete profile does nothing at all — it must not half-save one. */
    @Test
    fun `saving an incomplete profile writes nothing and keeps the sheet up`() = runMobileShell { shell ->
        val before = shell.hosts.hosts.map { it.id }
        openSheet()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        save()

        assertNull(shell.hosts.hosts.firstOrNull { it.label == NAME }, "an incomplete profile was saved")
        assertEquals(before, shell.hosts.hosts.map { it.id })
        onNodeWithTag(UiTags.FORM_SAVE).assertExists()
    }

    /**
     * The sheet's own select and its own create dialog: creating writes at the sheet's root, so
     * the second half — clearing the folder on the saved profile — is what proves the select's
     * own line is wired.
     */
    @Test
    fun `a folder created on the phone lands on the profile`() = runMobileShell { shell ->
        openSheet()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        // The field sits below the fold of the sheet: its dropdown is placed against the trigger,
        // so the trigger has to be on screen before the menu can be reached.
        onNodeWithText(string(Res.string.conn_group_none)).performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.conn_group_new)).performClick()
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_group_name_placeholder)).performTextInput(FOLDER)
        onNodeWithText(string(Res.string.conn_create)).performClick()
        waitForIdle()
        save()

        assertEquals(FOLDER, shell.hosts.hosts.singleOrNull { it.label == NAME }?.group)

        // Creating writes the name at the sheet's root; picking an existing folder is the select's
        // own line, and the folder the first profile made is now one the second can pick.
        openSheet()
        onField(Res.string.conn_field_name).performTextInput(SECOND)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        onNodeWithText(string(Res.string.conn_group_none)).performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(FOLDER).performClick()
        waitForIdle()
        save()

        assertEquals(FOLDER, shell.hosts.hosts.singleOrNull { it.label == SECOND }?.group)
    }

    /** Desktop parity: a tag still sitting in the box when Save is pressed must not be dropped. */
    @Test
    fun `a tag left in the box is saved with the profile`() = runMobileShell { shell ->
        openSheet()
        onField(Res.string.conn_field_name).performTextInput(NAME)
        onField(Res.string.conn_field_host_address).performTextInput(ADDRESS)
        onField(Res.string.conn_field_username).performTextInput(USER)
        onField(Res.string.conn_field_tags).performTextInput(TAG)
        save()

        val saved = shell.hosts.hosts.single { it.label == NAME }
        assertTrue(TAG in saved.tags, "the tag in the box was dropped on save: ${'$'}{saved.tags}")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openSheet() {
        onNodeWithTag(UiTags.mobileTab(app.skerry.ui.app.MobileTab.Hosts)).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.NEW_CONNECTION).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_SAVE).assertExists()
    }

    /** The sheet is taller than the phone: Save sits below the fold until scrolled to. */
    private fun androidx.compose.ui.test.ComposeUiTest.save() {
        onNodeWithTag(UiTags.FORM_SAVE).performScrollTo().performClick()
        waitForIdle()
    }
}

private const val FOLDER = "Production"
private const val NAME = "phone-box"
private const val SECOND = "phone-box-2"
private const val ADDRESS = "10.0.0.77"
private const val USER = "deploy"
private const val TAG = "staging"
