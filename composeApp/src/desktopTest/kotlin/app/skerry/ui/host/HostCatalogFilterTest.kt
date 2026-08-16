package app.skerry.ui.host

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_chip_all
import app.skerry.ui.generated.resources.shtail_search_clear
import app.skerry.ui.generated.resources.term_no_hosts_match
import app.skerry.ui.generated.resources.term_search_hosts_placeholder
import kotlin.test.Test
import app.skerry.ui.design.tagChipLabel

/**
 * The catalog's two filters — the search box and the tag chips — driven through the sidebar.
 *
 * [filterHosts] and [hostTagChips] are already proved as arithmetic ([HostChipsTest]). What only a
 * click reaches is the wiring around them: that the box feeds the query in, that the chip row is
 * built from *this* section's tags, and that a filter which loses its last host doesn't leave the
 * catalog stuck on an empty list.
 */
@OptIn(ExperimentalTestApi::class)
class HostCatalogFilterTest {

    @Test
    fun `the search box narrows the catalog to what matches`() = runDesktopShell {
        onCatalog(DB_HOST).assertIsDisplayed()
        searchHosts("homelab")
        onCatalog(PI_HOST).assertIsDisplayed()
        onCatalog(DB_HOST).assertDoesNotExist()
    }

    /** Silence would read as a broken list; the sidebar says the filter is what emptied it. */
    @Test
    fun `a query nothing matches says so`() = runDesktopShell {
        searchHosts("nothing-by-this-name")
        onCatalog(string(Res.string.term_no_hosts_match)).assertIsDisplayed()
        onCatalog(PI_HOST).assertDoesNotExist()
    }

    @Test
    fun `the cross empties the search and brings the catalog back`() = runDesktopShell {
        searchHosts("homelab")
        onCatalog(DB_HOST).assertDoesNotExist()

        onNodeWithContentDescription(string(Res.string.shtail_search_clear)).performClick()
        waitForIdle()
        onCatalog(DB_HOST).assertIsDisplayed()
    }

    @Test
    fun `a tag chip filters the catalog and All brings it back`() = runDesktopShell {
        pickChip(tagChipLabel("docker"))
        onCatalog(PI_HOST).assertIsDisplayed()
        onCatalog(DB_HOST).assertDoesNotExist()

        pickChip(string(Res.string.shtail_chip_all))
        onCatalog(DB_HOST).assertIsDisplayed()
    }

    /**
     * Chips come from the section on screen, not from the whole catalog: `#lab` sits on a VNC
     * profile, and offering it among the shells would filter the list down to nothing.
     */
    @Test
    fun `the chip row offers only this section's tags`() = runDesktopShell {
        onCatalog(tagChipLabel("docker")).assertIsDisplayed()
        onCatalog(tagChipLabel("lab")).assertDoesNotExist()
    }

    /**
     * A tag lives on its hosts, so deleting the last one that carries it takes the chip away with
     * it. The filter must fall back to "All" — otherwise the catalog stays on a tag nothing has and
     * reads as empty.
     */
    @Test
    fun `a filter whose last host is deleted falls back to All`() = runDesktopShell { shell ->
        pickChip(tagChipLabel("edge"))
        onCatalog(EDGE_HOST).assertIsDisplayed()
        onCatalog(DB_HOST).assertDoesNotExist()

        shell.hosts.delete(shell.hosts.hosts.first { "edge" in it.tags }.id)
        waitForIdle()
        onCatalog(DB_HOST).assertIsDisplayed()
        onCatalog(string(Res.string.term_no_hosts_match)).assertDoesNotExist()
    }

    private fun ComposeUiTest.searchHosts(query: String) {
        onNodeWithContentDescription(string(Res.string.term_search_hosts_placeholder)).performTextInput(query)
        waitForIdle()
    }

    /** The chip row scrolls sideways in a 262dp sidebar, so a chip is brought into view first. */
    private fun ComposeUiTest.pickChip(label: String) {
        onCatalog(label).performScrollTo().performClick()
        waitForIdle()
    }
}

// Seeded catalog ([app.skerry.ui.desktop.seededHosts]): names, not ids, since a row is found by what
// it draws.
private const val DB_HOST = "db-master"
private const val PI_HOST = "homelab-pi"
private const val EDGE_HOST = "vps-edge"
