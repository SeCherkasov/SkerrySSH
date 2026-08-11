package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.host.Host
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.MAX_UNTRUSTED_LABEL_CHARS
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.seededHosts
import app.skerry.ui.host.HostDragState
import app.skerry.ui.host.HostSection
import kotlin.test.Test

/**
 * What a host row draws when the profile was written by a team member rather than on this machine.
 *
 * A shared host travels inside the sealed envelope, so the sync server never sees its name and
 * could not validate it if it wanted to: whatever the peer typed is what the row draws. A bidi
 * override reverses the tail of the string in every layout — the fixture below is drawn as
 * `web-01` while the profile it opens is a different one — and the zero-width formatters make two
 * names that differ nowhere the eye can reach.
 *
 * Written as escapes, never as the characters themselves: they are invisible in a diff, and a
 * reviewer could not tell the fixture from the expectation.
 */
@OptIn(ExperimentalTestApi::class)
class HostRowLabelTest {

    @Test
    fun `a bidi override in a shared host name is dropped from the team row`() = runForm({
        TeamHostRow(host(label = SPOOFED), FontFamily.Monospace)
    }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }

    /** Two names that differ only by an invisible character must not draw as the same row. */
    @Test
    fun `the zero-width formatters go too`() = runForm({
        TeamHostRow(host(label = "db\u200B-mas\u200Dter"), FontFamily.Monospace)
    }) {
        onNodeWithText("db-master").assertIsDisplayed()
    }

    /** The recent-connections row draws the same peer-authored string. */
    @Test
    fun `a recent row flattens the name as well`() = runForm({
        RecentHostRow(host(label = SPOOFED), FontFamily.Monospace)
    }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }

    /** And the catalog row — the third drawing of the same string, and the one #227 named. */
    @Test
    fun `the catalog row flattens the name as well`() = runForm({ CatalogRow(host(label = SPOOFED)) }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }

    /**
     * A name made only of what the filter drops leaves nothing to draw, and a row with no name is
     * one neither the eye nor a screen reader can tell from its neighbour.
     */
    @Test
    fun `a name that filters away falls back to the address`() = runForm({
        CatalogRow(host(label = "\u202E\u200B", address = "10.0.0.5"))
    }) {
        onNodeWithText("10.0.0.5").assertIsDisplayed()
    }

    /** Both fields hostile: the id is ours, so it is what is left to tell two rows apart. */
    @Test
    fun `a profile with nothing drawable left falls back to its id`() = runForm({
        CatalogRow(Host(id = "h-shared-4f2a91", label = "\u202E", address = "\u200B", port = 22, username = ""))
    }) {
        onNodeWithText("h-shared").assertIsDisplayed()
    }

    /**
     * The address line under the name is peer-authored too: it is assembled from the profile's own
     * username and address, both typed by whoever shared it.
     */
    @Test
    fun `the address line is flattened too`() = runForm({
        TeamHostRow(host(label = "db-master", username = "ro\u202Eot"), FontFamily.Monospace)
    }) {
        onNodeWithText("root@10.0.0.5:22").assertIsDisplayed()
    }

    /** A name a hostile peer made pathologically long is cut, and never mid-character. */
    @Test
    fun `a long name is cut to the cap`() = runForm({
        TeamHostRow(host(label = "o".repeat(5_000)), FontFamily.Monospace)
    }) {
        onNodeWithText("o".repeat(MAX_UNTRUSTED_LABEL_CHARS)).assertIsDisplayed()
    }

    /** An ordinary name is drawn exactly as the peer wrote it. */
    @Test
    fun `an ordinary name is left alone`() = runForm({
        TeamHostRow(host(label = "Платформа · прод"), FontFamily.Monospace)
    }) {
        onNodeWithText("Платформа · прод").assertIsDisplayed()
    }
}

/** The live sidebar row, with the collaborators it needs and nothing it does in this test. */
@Composable
private fun CatalogRow(host: Host) {
    HostRow(
        host = host,
        state = DesktopDesignState(),
        section = HostSection.Terminal,
        controller = seededHosts(),
        sessions = null,
        connect = {},
        mono = FontFamily.Monospace,
        selectedHostId = null,
        onSelectHost = {},
        dragState = HostDragState(),
        foldersProvider = { emptyList() },
    )
}

private fun host(
    label: String,
    username: String = "root",
    address: String = "10.0.0.5",
): Host = Host(id = "h-shared", label = label, address = address, port = 22, username = username)

/** U+202E before the tail: the row is drawn as `web-01`, the profile it opens is `web10-`. */
private const val SPOOFED = "web\u202E10-"

private const val FLATTENED = "web10-"
