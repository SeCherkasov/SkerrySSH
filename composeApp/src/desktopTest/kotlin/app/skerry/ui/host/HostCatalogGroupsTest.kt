package app.skerry.ui.host

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_group_delete
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.generated.resources.shtail_group_expand
import app.skerry.ui.generated.resources.shtail_group_rename
import app.skerry.ui.generated.resources.term_search_hosts_placeholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sidebar's folders: folding one away, creating an empty one, and the rename/delete dialog
 * behind the header's pencil.
 *
 * A group lives in two places at once — [Host.group] on the profiles, and the shell's own list of
 * groups that have no host yet ([app.skerry.ui.app.DesktopDesignState.customGroups]). Every write
 * here has to reach both, and only the dialog's own buttons prove that it does.
 */
@OptIn(ExperimentalTestApi::class)
class HostCatalogGroupsTest {

    @Test
    fun `the chevron folds a folder away and back`() = runDesktopShell {
        onCatalog(DB_HOST).assertIsDisplayed()

        collapse(PROD_GROUP)
        onCatalog(DB_HOST).assertDoesNotExist()
        // The folder itself stays: collapsed is not deleted, and the header is the way back.
        onCatalog(PROD_GROUP).assertIsDisplayed()

        expand(PROD_GROUP)
        onCatalog(DB_HOST).assertIsDisplayed()
    }

    /** Folding one folder says nothing about the others. */
    @Test
    fun `collapsing a folder leaves its neighbours open`() = runDesktopShell {
        collapse(PROD_GROUP)
        onCatalog(PI_HOST).assertIsDisplayed()
    }

    @Test
    fun `a folder created from the sidebar shows up empty`() = runDesktopShell { shell ->
        createGroup(NEW_GROUP)
        onCatalog(NEW_GROUP).assertIsDisplayed()
        assertEquals(
            emptyList(),
            shell.hosts.hosts.filter { it.group == NEW_GROUP },
            "creating a folder must not move any profile into it",
        )
    }

    /**
     * Search narrows the catalog by host, and an empty folder has no host to match with — leaving it
     * on screen would read as "these are the results".
     */
    @Test
    fun `an empty folder is hidden while the catalog is filtered`() = runDesktopShell {
        createGroup(NEW_GROUP)
        onNodeWithContentDescription(string(Res.string.term_search_hosts_placeholder)).performTextInput("db")
        waitForIdle()
        onCatalog(NEW_GROUP).assertDoesNotExist()
        onCatalog(DB_HOST).assertIsDisplayed()
    }

    /**
     * An empty folder is a placeholder in the sidebar that made it, not in the catalog: the desktops
     * list must not offer a folder created among the shells.
     */
    @Test
    fun `an empty folder stays in the sidebar it was created in`() = runDesktopShell {
        createGroup(NEW_GROUP)
        onNodeWithTag(UiTags.railSection(HostSection.RemoteDesktops)).performClick()
        waitForIdle()
        onCatalog(NEW_GROUP).assertDoesNotExist()

        onNodeWithTag(UiTags.railSection(HostSection.Terminal)).performClick()
        waitForIdle()
        onCatalog(NEW_GROUP).assertIsDisplayed()
    }

    @Test
    fun `renaming a folder takes its hosts with it`() = runDesktopShell { shell ->
        openGroupDialog(PROD_GROUP)
        onNodeWithTag(UiTags.FORM_FIELD).performTextInput(RENAMED_GROUP)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        onCatalog(RENAMED_GROUP).assertIsDisplayed()
        onCatalog(PROD_GROUP).assertDoesNotExist()
        onCatalog(DB_HOST).assertIsDisplayed()
        assertEquals(
            RENAMED_GROUP,
            shell.hosts.hosts.first { it.label == DB_HOST }.group,
            "the rename must reach the profiles, not just the folder header",
        )
    }

    /** Deleting a folder is an ungrouping: the profiles in it survive, filed under no group. */
    @Test
    fun `deleting a folder ungroups its hosts and keeps them`() = runDesktopShell { shell ->
        openGroupDialog(HOMELAB_GROUP)
        onNodeWithText(string(Res.string.shell_group_delete)).performClick()
        waitForIdle()

        onCatalog(HOMELAB_GROUP).assertDoesNotExist()
        onCatalog(PI_HOST).assertIsDisplayed()
        assertNull(
            shell.hosts.hosts.first { it.label == PI_HOST }.group,
            "the profile must survive its folder, without a group",
        )
    }

    private fun ComposeUiTest.collapse(group: String) {
        onNodeWithContentDescription(string(Res.string.shtail_group_collapse, group)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.expand(group: String) {
        onNodeWithContentDescription(string(Res.string.shtail_group_expand, group)).performClick()
        waitForIdle()
    }

    /** The pencil in a folder header, which opens the same dialog for renaming and deleting. */
    private fun ComposeUiTest.openGroupDialog(group: String) {
        onNodeWithContentDescription(string(Res.string.shtail_group_rename, group)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.createGroup(name: String) {
        onNodeWithTag(UiTags.NEW_GROUP).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_FIELD).performTextInput(name)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
    }
}

// Seeded catalog ([app.skerry.ui.desktop.seededHosts]).
private const val PROD_GROUP = "Production"
private const val HOMELAB_GROUP = "Homelab"
private const val DB_HOST = "db-master"
private const val PI_HOST = "homelab-pi"
private const val NEW_GROUP = "Staging"
private const val RENAMED_GROUP = "Live"
