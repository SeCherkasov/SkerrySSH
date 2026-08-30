package app.skerry.ui.design

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose half of the funnel — what the field holds, and what it hands back to the editor.
 *
 * [ImeFunnel] itself is covered without Compose (`ImeFunnelTest`); what only shows up here is the
 * contract between the two. The funnel learns that its reset reached the editor from being
 * recomposed, and it is recomposed only because the value written back differs from the one it was
 * last composed with. Pin the caret and that stops being true: every test in `ImeFunnelTest` still
 * passes and the device duplication comes straight back.
 */
@OptIn(ExperimentalTestApi::class)
class ImeFunnelFieldTest {

    @Test
    fun `typing reaches the session and leaves the field at its anchors`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextInput("ls")

        assertEquals(listOf("ls"), typed)
        assertEquals(FUNNEL_TEXT, editableText(), "the funnel kept what was typed")
    }

    @Test
    fun `each reset is written back as a value the field was not composed with`() = withFunnel {
        onNode(hasSetTextAction()).performTextInput("a")
        val first = selection()
        onNode(hasSetTextAction()).performTextInput("b")
        val second = selection()

        assertTrue(
            first != second,
            "both resets wrote the same value ($first): a write equal to the composed one is " +
                "skipped, and the funnel never hears that the field was reset",
        )
    }

    /**
     * The limitation the class KDoc names, pinned at the layer it happens on: `BasicTextField`
     * compares an edit against the value it was composed with and drops the ones that land on it, so
     * a deletion of a character typed in the same frame never reaches the funnel. Asserted as it
     * behaves, not as it should: the day the input path stops going through that comparison, this
     * test is the one that says so.
     */
    /** The bug this exists for, at the Compose layer: one press, one byte, however the field resets. */
    @Test
    fun `three keypresses reach the session once each`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextInput("1")
        onNode(hasSetTextAction()).performTextInput("2")
        onNode(hasSetTextAction()).performTextInput("3")

        assertEquals(listOf("1", "2", "3"), typed)
    }

    /**
     * Two edits with no frame between them — the S24's `commitText` + `finishComposingText` pair,
     * ~2 ms apart. The second one changes nothing, and changing nothing is what it must send.
     */
    @Test
    fun `a second edit inside one frame sends only what it changed`() = withFunnel { typed ->
        mainClock.autoAdvance = false

        onNode(hasSetTextAction()).performTextInput("1")
        onNode(hasSetTextAction()).performTextInput("2")

        assertEquals(listOf("1", "2"), typed)
        mainClock.autoAdvance = true
        onNode(hasSetTextAction()).performTextInput("3")
        assertEquals(listOf("1", "2", "3"), typed)
    }

    @Test
    fun `a deletion landing on the composed value never reaches the funnel`() = withFunnel { typed ->
        mainClock.autoAdvance = false

        onNode(hasSetTextAction()).performTextInput("a")
        // Back to the anchors, caret at their end — byte for byte the value the field was composed
        // with, since no frame has carried the funnel's own reset yet.
        onNode(hasSetTextAction()).performTextReplacement(FUNNEL_TEXT)

        assertEquals(listOf("a"), typed, "foundation forwarded an edit equal to the composed value")
    }

    @Test
    fun `clearing the field sends one backspace`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextClearance()

        assertEquals(listOf(DEL), typed)
        assertEquals(FUNNEL_TEXT, editableText(), "the field did not return to its anchors")
    }

    private fun ComposeUiTest.editableText(): String? =
        onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun ComposeUiTest.selection(): TextRange? =
        onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    private fun withFunnel(body: ComposeUiTest.(List<String>) -> Unit) {
        val typed = mutableListOf<String>()
        runForm({
            ImeFunnelField("input", Modifier, KeyboardOptions.Default) { typed += it }
        }) {
            waitForIdle()
            body(typed)
        }
    }
}

private val DEL = Char(0x7f).toString()
