package app.skerry.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.desktop.runForm
import app.skerry.ui.sync.SyncFailureReason
import app.skerry.ui.sync.SyncFormError
import app.skerry.ui.sync.SyncStatus
import app.skerry.ui.sync.syncAnnouncement
import app.skerry.ui.sync.syncFailureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #244: sync state changes on its own — a background cycle, a WS signal, a dropped connection — and
 * the card that carries it is the only thing that says so. A screen reader whose focus is elsewhere on the
 * screen is told nothing (WCAG 4.1.3 Status Messages).
 *
 * What makes an announcement happen is not the modifier but where it sits: Compose sources the event from
 * the node whose own semantics changed, so the announcing node has to carry the message itself and to
 * outlive the change. These tests drive the change and assert both — and assert the node's bounds, which
 * is what decides whether Android builds an accessibility node for it at all.
 */
@OptIn(ExperimentalTestApi::class)
class SyncStatusAnnouncementTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun `the announcer carries the status it is announcing`() {
        var status by mutableStateOf("Connected")
        runForm({ StatusAnnouncer(status) }) {
            onNode(polite).assertContentDescriptionEquals("Connected")
            status = "Sync error. Server unreachable"
            waitForIdle()
            // The same node, a new description: a status change, not a node appearing.
            onNode(polite).assertContentDescriptionEquals("Sync error. Server unreachable")
        }
    }

    /**
     * Android builds the accessibility tree from the semantics nodes whose bounds intersect the space not
     * yet accounted for, so a zero-size node is dropped before a live-region event is ever emitted for it —
     * the announcer would be inert on the one platform that consumes live regions. The Compose test tree
     * keeps every node whatever its bounds, which is why only this assertion catches it.
     */
    @Test
    fun `the announcer occupies enough space to reach the accessibility tree`() {
        runForm({ StatusAnnouncer("Connected") }) {
            val bounds = onNode(polite).getBoundsInRoot()
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            assertTrue(width.value > 0f, "the announcer has no width: $bounds")
            assertTrue(height.value > 0f, "the announcer has no height: $bounds")
        }
    }

    @Test
    fun `a status worth no announcement says nothing`() {
        runForm({ StatusAnnouncer("") }) {
            onNode(polite).assertContentDescriptionEquals("")
        }
    }

    /**
     * The form's error line: the announcer stays composed while there is no error, so the one that appears
     * is a change to a node that was already there — and it carries the message, not its container.
     */
    @Test
    fun `a form error is announced by the node that holds it`() {
        var message by mutableStateOf<String?>(null)
        runForm({ SyncFormError(message) }) {
            onNode(polite).assertContentDescriptionEquals("")
            message = "Wrong password"
            waitForIdle()
            onNode(polite).assertContentDescriptionEquals("Wrong password")
            onNodeWithText("Wrong password").assertExists()
        }
    }

    /** A screen that keeps an announcer of its own must be able to stop this row being the second voice. */
    @Test
    fun `a form error can be drawn without announcing itself`() {
        runForm({ SyncFormError("Wrong password", announce = false) }) {
            onAllNodes(polite).fetchSemanticsNodes().let {
                assertTrue(it.isEmpty(), "the row announced itself anyway")
            }
            onNodeWithText("Wrong password").assertExists()
        }
    }

    /**
     * Every state that the coordinator can move to on its own has to have something to say. `Configured` is
     * where a session lands when its refresh token dies mid-use, and `NeedsPasswordReplaceConfirm` is the
     * one state that is a question — an `else` branch covering either of them is the bug, not the default.
     */
    @Test
    fun `every self-driven status has an announcement`() {
        val spoken = mutableMapOf<String, String>()
        val statuses = listOf(
            "busy" to SyncStatus.Busy,
            "online" to SyncStatus.Online("maya", 0, 0),
            "configured" to SyncStatus.Configured("https://work.test", "maya"),
            "pending" to SyncStatus.NeedsPasswordReplaceConfirm("https://work.test", "maya"),
            "failed" to SyncStatus.Failed(SyncFailureReason.ConnectFailed),
            "disabled" to SyncStatus.Disabled,
        )
        runForm({ statuses.forEach { (name, s) -> spoken[name] = syncAnnouncement(s) } }) { waitForIdle() }

        assertEquals("", spoken["disabled"], "sync that was never set up has nothing to announce")
        for ((name, _) in statuses.filter { it.first != "disabled" }) {
            assertTrue(spoken[name].orEmpty().isNotBlank(), "$name announces nothing")
        }
        // Distinct: an announcement equal to the previous one is not a change and stays silent.
        val heard = spoken.filterKeys { it != "disabled" }.values.toList()
        assertEquals(heard.size, heard.toSet().size, "two states announce the same sentence: $heard")
    }

    /**
     * The detail in a failure is written by the server. It reaches the screen reader through the same text
     * the screen draws, so the filter has to be on that text — a bidi override in it reverses the localized
     * reason it is appended to, in speech as on screen.
     */
    @Test
    fun `a server-authored failure detail is filtered before it is drawn or spoken`() {
        var drawn = ""
        var spoken = ""
        val hostile = "\u202Eexe.evil\u200B\u200B"
        val failed = SyncStatus.Failed(SyncFailureReason.ConnectFailed, hostile)
        runForm({
            drawn = syncFailureText(failed)
            spoken = syncAnnouncement(failed)
        }) { waitForIdle() }

        for ((what, text) in listOf("drawn" to drawn, "spoken" to spoken)) {
            assertFalse(text.contains('\u202E'), "the $what text kept the bidi override: $text")
            assertFalse(text.contains('\u200B'), "the $what text kept the zero-width joiner: $text")
            assertTrue(text.contains("exe.evil"), "the $what text lost the detail itself: $text")
        }
    }
}
