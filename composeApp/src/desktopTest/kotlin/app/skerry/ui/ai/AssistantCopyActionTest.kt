package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.skerry.ui.design.FakeSystemClipboard
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_copy
import app.skerry.ui.generated.resources.assistant_copy_failed
import app.skerry.ui.terminal.SystemClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Copy button under an assistant code block. It has to write to the platform's own clipboard —
 * a Compose/AWT write lands in XWayland, where no native Wayland app pastes from (#282) — and it has
 * to say so when that write is refused, because there is no second buffer holding the text.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantCopyActionTest {

    @Test
    fun `Copy puts the command on the system clipboard`() {
        val clipboard = FakeSystemClipboard()
        withActions(clipboard) { actions ->
            actions.copy("uptime") { fail("a copy the clipboard took was reported as refused") }
            waitForIdle()
            assertEquals(listOf("uptime"), clipboard.writes, "the command went somewhere else")
        }
    }

    /** The press has to hear about the refusal; nothing else knows a card was left claiming a copy. */
    @Test
    fun `a refused copy is reported back to the press`() {
        val clipboard = FakeSystemClipboard(refuseWrites = 1)
        withActions(clipboard) { actions ->
            var refused = false
            actions.copy("uptime") { refused = true }
            waitForIdle()
            assertTrue(refused, "the refused copy was swallowed — the block drew no note")
        }
    }

    /**
     * A reply can hold the same command twice, and the two cards are two presses. A note under the
     * card nobody touched reports a refusal that did not happen there — and shifts the buttons of a
     * card the user is about to press.
     */
    @Test
    fun `a refused copy is noted under the card that was pressed, not its twin`() = runComposeUiTest {
        val clipboard = FakeSystemClipboard(refuseWrites = 1)
        assistantPanel {
            AssistantMessage(
                "First.\n```\nuptime\n```\nAnd again.\n```\nuptime\n```",
                fromUser = false,
                actions = rememberAssistantCommandActions(terminal = null, clipboard = clipboard),
            )
        }
        onAllNodesWithContentDescription(string(Res.string.assistant_copy))[0].performClick()
        waitUntil { onAllNodesWithText(string(Res.string.assistant_copy_failed)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            1,
            onAllNodesWithText(string(Res.string.assistant_copy_failed)).fetchSemanticsNodes().size,
            "the card nobody pressed reported the refusal too",
        )
    }
}

/**
 * The block actions over a fake clipboard, with no session attached (Run and Edit are not what this
 * covers).
 */
@OptIn(ExperimentalTestApi::class)
private fun withActions(
    clipboard: SystemClipboard,
    body: ComposeUiTest.(AssistantCommandActions) -> Unit,
) {
    lateinit var actions: AssistantCommandActions
    runForm({ actions = Actions(clipboard) }) { body(actions) }
}

@Composable
private fun Actions(clipboard: SystemClipboard): AssistantCommandActions =
    rememberAssistantCommandActions(terminal = null, clipboard = clipboard)
