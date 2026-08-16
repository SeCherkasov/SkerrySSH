package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onScreen
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_search_hosts
import app.skerry.ui.generated.resources.shtail_chip_all
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.design.tagChipLabel
import kotlin.test.Test

/**
 * The phone catalog: search, tag chips, folders, and the tap that opens a host.
 *
 * The list itself is shared with the desktop sidebar ([app.skerry.ui.host.filterHosts] and friends)
 * — what is phone-specific is the screen around it, and that is what this drives. Parity is the
 * point: the same catalog has to answer the same way in both shells.
 */
@OptIn(ExperimentalTestApi::class)
class MobileHostCatalogTest {

    @Test
    fun `the search box narrows the catalog to what matches`() = runMobileShell {
        onNodeWithText(DB_HOST).assertIsDisplayed()
        onNodeWithContentDescription(string(Res.string.shell_search_hosts)).performTextInput("homelab")
        waitForIdle()
        onNodeWithText(PI_HOST).assertIsDisplayed()
        onNodeWithText(DB_HOST).assertDoesNotExist()
    }

    @Test
    fun `a tag chip filters the catalog and All brings it back`() = runMobileShell {
        pickChip(tagChipLabel("docker"))
        onNodeWithText(PI_HOST).assertIsDisplayed()
        onNodeWithText(DB_HOST).assertDoesNotExist()

        pickChip(string(Res.string.shtail_chip_all))
        onNodeWithText(DB_HOST).assertIsDisplayed()
    }

    @Test
    fun `the chevron folds a folder away`() = runMobileShell {
        onNodeWithContentDescription(string(Res.string.shtail_group_collapse, PROD_GROUP)).performClick()
        waitForIdle()
        onNodeWithText(DB_HOST).assertDoesNotExist()
    }

    /** A row is the way into a profile: the tap opens its detail screen, it does not connect. */
    @Test
    fun `tapping a row opens the host`() = runMobileShell {
        onNodeWithText(DB_HOST).performClick()
        waitForIdle()
        onScreen(UiTags.mobileScreen(MobileRoute.HostDetail)).assertIsDisplayed()
        onNodeWithText(DB_HOST).assertIsDisplayed()
    }

    /** The chip strip scrolls sideways on a 390dp screen, so a chip is brought into view first. */
    private fun ComposeUiTest.pickChip(label: String) {
        onNodeWithText(label).performScrollTo().performClick()
        waitForIdle()
    }
}

// Seeded catalog ([app.skerry.ui.desktop.seededHosts]).
private const val PROD_GROUP = "Production"
private const val DB_HOST = "db-master"
private const val PI_HOST = "homelab-pi"
