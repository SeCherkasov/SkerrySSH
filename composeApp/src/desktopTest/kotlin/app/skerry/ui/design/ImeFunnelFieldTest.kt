package app.skerry.ui.design

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Compose half of the funnel — what the field holds, and what an edit costs the session.
 *
 * The arithmetic is covered without Compose (`ImeFunnelTest`); what only shows up here is that
 * foundation runs the funnel's transformation on every edit and takes its revert. The tests that
 * pin the two reported bugs stop the frame clock first: both lived in edits the keyboard delivered
 * inside one frame (#347, #348), and letting a frame run between them hides the failure.
 */
@OptIn(ExperimentalTestApi::class)
class ImeFunnelFieldTest {

    @Test
    fun `typing reaches the session and leaves the field at its anchors`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextInput("ls")

        assertEquals(listOf("ls"), typed)
        assertEquals(FUNNEL_TEXT, editableText(), "the funnel kept what was typed")
    }

    /**
     * What the field rests at, once anything has been typed into it: the anchors, and a collapsed
     * caret at their end. A resting selection would put selection handles and a floating text
     * toolbar over the session it is typed into.
     */
    @Test
    fun `the field rests at its anchors with the caret collapsed at their end`() = withFunnel {
        onNode(hasSetTextAction()).performTextInput("a")

        assertEquals(FUNNEL_TEXT, editableText())
        assertEquals(TextRange(FUNNEL_TEXT.length), selection())
    }

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
        assertEquals(FUNNEL_TEXT, editableText(), "the field kept what three edits typed")
    }

    /**
     * Issue #348: an edit that reports back exactly what the field is resting at — a
     * `finishComposingText`, a cursor-control gesture, a Backspace that lands on the anchors — must
     * cost nothing and must not cost the keypress after it.
     */
    @Test
    fun `a keypress after an edit reporting the resting value is not lost`() = withFunnel { typed ->
        mainClock.autoAdvance = false

        onNode(hasSetTextAction()).performTextInput("a")
        onNode(hasSetTextAction()).performTextReplacement(FUNNEL_TEXT)
        onNode(hasSetTextAction()).performTextInput("a")

        assertEquals(listOf("a", "a"), typed)
        assertEquals(FUNNEL_TEXT, frameRunsAndFieldHolds(), "the field kept what was typed")
    }

    /** A deletion arriving before any frame has run is still one Backspace for the session. */
    @Test
    fun `a deletion delivered in the same frame reaches the session`() = withFunnel { typed ->
        mainClock.autoAdvance = false

        onNode(hasSetTextAction()).performTextInput("a")
        onNode(hasSetTextAction()).performTextReplacement(FUNNEL_TEXT.dropLast(1))

        assertEquals(listOf("a", DEL), typed)
        assertEquals(FUNNEL_TEXT, frameRunsAndFieldHolds(), "the field kept what was typed")
    }

    /**
     * Enter is how a shell command is run. This pins the mapping only: whether the soft keyboard
     * still offers a newline at all is `lineLimits` on the field, which lives in `EditorInfo` and is
     * invisible from here.
     */
    @Test
    fun `a newline reaches the session as a carriage return`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextInput("\n")

        assertEquals(listOf(CR), typed)
    }

    /**
     * The caret is reverted with everything else, so the anchors stay behind it however the IME moves
     * it. A caret left at 0 would have nothing before it for a Backspace to take.
     */
    @Test
    fun `a cursor move is reverted and typing still lands among the anchors`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextInputSelection(TextRange(0))

        assertEquals(TextRange(FUNNEL_TEXT.length), selection())
        onNode(hasSetTextAction()).performTextInput("x")
        assertEquals(listOf("x"), typed)
    }

    /**
     * The transformation is remembered once, and the callback it reports to is not: a screen that
     * recomposes with a new lambda — a session reconnecting, a mode changing — must not keep feeding
     * the one captured when the field was first composed.
     */
    @Test
    fun `an edit after a recomposition goes to the current callback`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val swapped = mutableStateOf(false)
        runForm({
            val sink = if (swapped.value) second else first
            ImeFunnelField("input", Modifier, KeyboardOptions.Default) { sink += it }
        }) {
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("a")
            swapped.value = true
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("b")

            assertEquals(listOf("a"), first)
            assertEquals(listOf("b"), second, "the field reported to the callback it was composed with")
        }
    }

    @Test
    fun `clearing the field sends one backspace`() = withFunnel { typed ->
        onNode(hasSetTextAction()).performTextClearance()

        assertEquals(listOf(DEL), typed)
        assertEquals(FUNNEL_TEXT, editableText(), "the field did not return to its anchors")
    }

    /**
     * What the field holds, read after letting a frame run. A stopped clock sends no snapshot apply
     * notifications, so `LayoutNode`'s semantics config is never invalidated and [editableText] keeps
     * returning the value it was built with — a retention check made there passes whatever the field
     * is holding.
     */
    private fun ComposeUiTest.frameRunsAndFieldHolds(): String? {
        mainClock.autoAdvance = true
        waitForIdle()
        return editableText()
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
private val CR = Char(0x0d).toString()
