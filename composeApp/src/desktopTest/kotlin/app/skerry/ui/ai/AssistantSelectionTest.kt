package app.skerry.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import app.skerry.shared.ai.AiRole
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.assistant_run
import app.skerry.ui.mobile.MobileAiBarInput
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Text in the assistant has to come out of the panel the way terminal output does: dragged over with
 * the mouse and copied with the platform's own chord. A reply is prose the user did not type — a
 * path, a flag, a host name — and the per-command Copy button only ever covers a fenced command.
 *
 * Drives the real composables through mouse and key injection rather than asserting a
 * `SelectionContainer` is present: what matters is that the drag lands on the text and Ctrl/Cmd+C
 * puts it on the clipboard, which is the part a wrapper in the wrong place silently breaks.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantSelectionTest {

    @Test
    fun `dragging across an assistant reply copies its prose`() = runComposeUiTest {
        val clipboard = assistantPanel { AssistantMessage(PROSE, fromUser = false, actions = INERT) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    @Test
    fun `dragging across a question copies it back out`() = runComposeUiTest {
        val clipboard = assistantPanel { AssistantMessage(PROSE, fromUser = true, actions = INERT) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    @Test
    fun `a fenced command can be dragged over as well as copied by its button`() = runComposeUiTest {
        val clipboard = assistantPanel {
            AssistantMessage("Then reload it.\n```\n$COMMAND\n```", fromUser = false, actions = INERT)
        }
        assertEquals(COMMAND, selectAndCopy(COMMAND, clipboard))
    }

    /**
     * The block wraps and, past its height, scrolls vertically — and a scroll container is exactly
     * what eats a drag. It does not eat a *mouse* drag — `scrollable` skips `PointerType.Mouse` — so
     * a sweep still selects the whole of a command that fits. That it *is* drawn whole is
     * [AssistantCommandCardTest]'s subject; here the box only has to keep out of the drag's way.
     */
    @Test
    fun `a command wider than the bubble is selectable across its wrapped lines`() = runComposeUiTest {
        val clipboard = assistantPanel {
            AssistantMessage("Then this.\n```\n$WIDE_COMMAND\n```", fromUser = false, actions = INERT)
        }
        val copied = selectAndCopy(WIDE_COMMAND, clipboard)
        assertNotNull(copied, "the scroll box ate the drag — nothing was selected")
        assertEquals(WIDE_COMMAND, copied)
    }

    /**
     * A block whose lines are all comments has no runnable command, so [AssistantMessage] draws no
     * action row at all — no Copy button, and selection is the only way that text leaves the panel.
     */
    @Test
    fun `a block with nothing runnable has no Copy button, so selection is the only way out`() = runComposeUiTest {
        val clipboard = assistantPanel {
            AssistantMessage("For reference:\n```\n$COMMENT_BLOCK\n```", fromUser = false, actions = INERT)
        }
        assertEquals(COMMENT_BLOCK, selectAndCopy(COMMENT_BLOCK, clipboard))
    }

    /**
     * The action row is out of the turn's selection scope: a sweep down the whole bubble takes the
     * answer and the command, and leaves "Run"/"Copy"/"Edit" behind. Without the `DisableSelection`
     * the labels ride along into the paste.
     */
    @Test
    fun `sweeping the whole turn takes the answer and the command but not the buttons`() = runComposeUiTest {
        var runLabel = ""
        val clipboard = assistantPanel {
            runLabel = stringResource(Res.string.assistant_run)
            AssistantMessage("$PROSE\n```\n$COMMAND\n```", fromUser = false, actions = RUNNABLE)
        }
        // Starts on the prose and ends well below the bubble, so the sweep crosses the action row.
        onNodeWithText(PROSE).performMouseInput {
            moveTo(centerLeft)
            press()
            moveTo(center)
            moveTo(bottomRight + Offset(0f, SWEEP_PAST_BUBBLE.toPx()))
            release()
        }
        val copied = copy(PROSE, clipboard)
        assertNotNull(copied)
        assertTrue(copied.contains(PROSE), "prose missing from `$copied`")
        assertTrue(copied.contains(COMMAND), "command missing from `$copied`")
        assertFalse(copied.contains(runLabel), "the Run button's label rode along in `$copied`")
    }

    /**
     * `Sym` draws an icon as a ligature — its text *is* the glyph name — so an icon left inside the
     * selection scope pastes as the word `attach_file`. The attachment line is chrome about the turn,
     * not part of it.
     */
    @Test
    fun `copying a question does not pick up the attachment icon`() = runComposeUiTest {
        val clipboard = assistantPanel { AssistantMessage(PROSE, fromUser = true, actions = INERT, attached = 2) }
        onNodeWithText(PROSE).performMouseInput {
            moveTo(topLeft)
            press()
            moveTo(center)
            moveTo(bottomRight + Offset(0f, SWEEP_PAST_BUBBLE.toPx()))
            release()
        }
        val copied = copy(PROSE, clipboard)
        assertNotNull(copied)
        assertFalse(copied.contains("attach_file"), "the icon's ligature name rode along in `$copied`")
    }

    @Test
    fun `a notice can be copied into a bug report`() = runComposeUiTest {
        val clipboard = assistantPanel { AssistantNotice(AiNotice.Ask(NOTICE)) }
        assertEquals(NOTICE, selectAndCopy(NOTICE, clipboard))
    }

    @Test
    fun `a quick-chat bubble can be copied`() = runComposeUiTest {
        val clipboard = assistantPanel { AiChatBubble(AiRole.ASSISTANT, PROSE) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    @Test
    fun `a failed request can be copied`() = runComposeUiTest {
        // The text is read out of the composition, not hardcoded: it is localized, and the run's
        // locale is the machine's.
        var message = ""
        val clipboard = assistantPanel {
            message = aiFailureMessage(AiFailure.RATE_LIMITED)
            AiChatError(AiFailure.RATE_LIMITED)
        }
        assertEquals(message, selectAndCopy(message, clipboard))
    }

    @Test
    fun `a failed model refresh can be copied from under the field`() = runComposeUiTest {
        var message = ""
        val clipboard = assistantPanel {
            message = aiFailureMessage(AiFailure.RATE_LIMITED)
            AiChatError(AiFailure.RATE_LIMITED, compact = true)
        }
        assertEquals(message, selectAndCopy(message, clipboard))
    }

    /**
     * The mobile bar is the Android half of the parity rule and the harder case: it offers no Copy
     * button at all, so what selection cannot reach cannot leave the screen.
     */
    @Test
    fun `the mobile bar's proposed command can be selected`() = runComposeUiTest {
        val controller = terminalAi(reply = MOBILE_COMMAND)
        controller.ask("free the disk")
        val clipboard = assistantPanel { MobileAiBarInput(controller, terminalState()) }
        assertEquals(MOBILE_COMMAND, selectAndCopy(MOBILE_COMMAND, clipboard))
    }

    @Test
    fun `the mobile bar's explanation can be selected`() = runComposeUiTest {
        val controller = terminalAi(reply = PROSE)
        controller.explain("total 0")
        val clipboard = assistantPanel { MobileAiBarInput(controller, terminalState()) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    /**
     * The two surfaces that never go through [AssistantAnswer.segments] — the quick chat renders a
     * whole reply as one string, the mobile bar an explanation — filter at the call site instead. A
     * bidi override left in either is one sweep and one paste from the shell.
     */
    @Test
    fun `a quick-chat reply is copied without the bidi override it arrived with`() = runComposeUiTest {
        val clipboard = assistantPanel { AiChatBubble(AiRole.ASSISTANT, SPOOFED) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    @Test
    fun `a question is copied without the bidi override it was pasted with`() = runComposeUiTest {
        val clipboard = assistantPanel { AssistantMessage(SPOOFED, fromUser = true, actions = INERT) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    @Test
    fun `the mobile bar's explanation is copied without its bidi override`() = runComposeUiTest {
        val controller = terminalAi(reply = SPOOFED)
        controller.explain("total 0")
        val clipboard = assistantPanel { MobileAiBarInput(controller, terminalState()) }
        assertEquals(PROSE, selectAndCopy(PROSE, clipboard))
    }

    /**
     * Selection is scoped to one turn, so a turn's own text changing under it is what cannot swallow
     * it. A turn scrolled out of the feed is a different matter — the `LazyColumn` disposes it, and
     * the selection goes with it; that is stated in [AssistantMessage], not defended here.
     */
    @Test
    fun `a selection in one turn survives the next turn's text growing`() = runComposeUiTest {
        var streamed by mutableStateOf("Chec")
        val clipboard = assistantPanel {
            Column {
                AssistantMessage(PROSE, fromUser = false, actions = INERT)
                AssistantMessage(streamed, fromUser = false, actions = INERT)
            }
        }
        drag(PROSE)
        streamed = "Checking the unit file now."
        waitForIdle()
        assertEquals(PROSE, copy(PROSE, clipboard))
    }

}

/** The action row drawn in its live state — the labels a sweep must not pick up. */
private val RUNNABLE = INERT.copy(runnable = true)

private const val PROSE = "Reload the unit before restarting it."
private const val COMMAND = "systemctl daemon-reload"

/** [PROSE] as a hostile model would send it: a right-to-left override the display must not keep. */
private const val SPOOFED = "Reload the unit before\u202E restarting it."
private const val NOTICE = "Which host did you mean?"

/** Wider than the bubble at 11.5.sp mono, so the block has to wrap for a sweep to reach its end. */
private const val WIDE_COMMAND =
    "journalctl -u nginx.service --since '2026-08-01 00:00' --until '2026-08-09 00:00' --no-pager -o short-iso"

/** Comments only: `AssistantAnswer.commands` drops them, so the card gets no action row. */
private const val COMMENT_BLOCK = "# keep the old unit file\n# rollback: systemctl revert nginx"

/** Single-line and runnable, so the mobile bar shows it as a proposal rather than a notice. */
private const val MOBILE_COMMAND = "du -sh /var/log/*"

/**
 * How far past the bubble's bottom-right the whole-turn sweep goes, to cross the action row. In dp,
 * converted at injection time: everything it has to clear (paddings, the code box, the chip row) is
 * in dp, so a pixel literal would stop inside the code block at any density above 1 and the
 * assertion about the buttons would pass without ever reaching them.
 */
private val SWEEP_PAST_BUBBLE = 120.dp

