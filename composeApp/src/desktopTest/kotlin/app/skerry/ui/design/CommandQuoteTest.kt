package app.skerry.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.command_clipped
import app.skerry.ui.generated.resources.command_clipped_partial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The block every confirmation shows a command in. Its rules are asserted here rather than through
 * the three surfaces that use it: what a card, a phone strip and a modal each route into it is what
 * their own screens can produce, and two of the rules — the character cap and the escaping — take
 * text none of them happens to carry today. Both exist for text the app did not write.
 */
@OptIn(ExperimentalTestApi::class)
class CommandQuoteTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    /**
     * A surface that draws more than one block captions them, and the caption is a separate node —
     * reached by a linear sweep, never by Tab. A block that scrolls *is* a Tab stop, and landing on
     * one that says only `rm -rf /srv` does not say whether it is what runs or what the screen was
     * already holding. So the caption becomes the block's own name once it can be focused.
     */
    @Test
    fun `a captioned block that scrolls carries the caption in its name`() {
        runForm({ quote(TALL, lines = 2, label = "sending") }) {
            onNodeWithContentDescription("sending: $TALL").assertIsDisplayed()
        }
    }

    /** And a block that fits is not a focus stop, so it stays plain text under its caption. */
    @Test
    fun `a captioned block that fits is not renamed`() {
        runForm({ quote(PLAIN, label = "sending") }) {
            onAllNodesWithContentDescription("sending: $PLAIN").fetchSemanticsNodes().let {
                assertTrue(it.isEmpty(), "a block nobody can focus was given a name to be focused by")
            }
            onNodeWithText(PLAIN).assertIsDisplayed()
        }
    }

    /**
     * A right-to-left override draws the rest of the line in an order the shell will not use: the
     * classic Trojan Source disguise, and on the guard's dialog the quote is the only place it could
     * be caught — a pasted block reaches it unfiltered. Spelled out rather than dropped, because
     * dropping it would make the drawn text differ from the text that runs.
     */
    @Test
    fun `an override that would reorder the line is spelled out`() {
        runForm({ quote(SPOOFED) }) {
            onNodeWithText("echo hello <U+202E># rm -rf /srv").assertIsDisplayed()
        }
    }

    /**
     * A letter by category and nothing on screen. `curl` and the URL read as two words and run as
     * one, and the classifier's patterns are word-anchored, so it reads them as two as well.
     */
    @Test
    fun `a letter that draws as nothing is spelled out`() {
        runForm({ quote("curl\u2800https://example.sh") }) {
            onNodeWithText("curl<U+2800>https://example.sh").assertIsDisplayed()
        }
    }

    /**
     * The cap counts UTF-16 units, so it can land between the halves of one character. Half a pair
     * draws as the replacement glyph — a character in neither the command nor the escape vocabulary,
     * on the block whose whole contract is that what is drawn is what runs.
     */
    @Test
    fun `a cut between the halves of one character drops it whole`() {
        val head = "echo " + "x".repeat(MAX_DRAWN_COMMAND_CHARS - 6)
        runForm({ quote(head + "\uD83D\uDE80 tail") }) {
            onNodeWithText(head).assertIsDisplayed()
        }
    }

    /**
     * The blank that arrives with every copy from a web page or a model reply. No shell splits a
     * word on it, so `curl` and the URL are one argument and read as two — the same lie the braille
     * blank tells, through a character orders of magnitude more likely to turn up.
     */
    @Test
    fun `a blank no shell splits on is spelled out`() {
        runForm({ quote("curl\u00A0https://example.sh") }) {
            onNodeWithText("curl<U+00A0>https://example.sh").assertIsDisplayed()
        }
    }

    /** And a character past the basic plane, which a per-unit predicate can never see. */
    @Test
    fun `an invisible character past the basic plane is spelled out`() {
        runForm({ quote("echo \uDB40\uDC41") }) { // U+E0041, the tag block's "A"
            onNodeWithText("echo <U+E0041>").assertIsDisplayed()
        }
    }

    /** An ordinary astral character is not touched: a path may hold an emoji. */
    @Test
    fun `a character past the basic plane that draws is left alone`() {
        runForm({ quote("echo \uD83D\uDE80") }) {
            onNodeWithText("echo \uD83D\uDE80").assertIsDisplayed()
        }
    }

    /**
     * A half with no other half in text the cap never touched is not the cut's doing — it is what
     * the command holds, and dropping it would draw less than what runs.
     */
    @Test
    fun `a lone half in text that was not cut is spelled out`() {
        runForm({ quote("echo \uD83D") }) {
            onNodeWithText("echo <U+D83D>").assertIsDisplayed()
        }
    }

    /** Same for a byte that draws as nothing at all. */
    @Test
    fun `a control byte is spelled out`() {
        runForm({ quote("echo \u0007done") }) {
            onNodeWithText("echo <U+0007>done").assertIsDisplayed()
        }
    }

    /**
     * A CR is how a block's lines reach a PTY — the Android IME funnel sends one per line — so it is
     * a break to draw, not a byte to spell out. Escaped, a pasted script drew as one run-on line.
     */
    @Test
    fun `lines separated by a carriage return are drawn as lines`() {
        runForm({ quote("rm -rf /srv\rchown -R nobody /srv/www") }) {
            onNodeWithText("rm -rf /srv\nchown -R nobody /srv/www").assertIsDisplayed()
        }
    }

    /**
     * A command that merely contains the text `<U+` is ordinary text, not a spelled-out character
     * half written: nothing was cut, so nothing may be dropped. The cut is the only thing that may
     * end a quote early, and it says so.
     */
    @Test
    fun `a command containing the escape's own prefix is drawn whole`() {
        runForm({ quote(LITERAL_ESCAPE) }) { onNodeWithText(LITERAL_ESCAPE).assertIsDisplayed() }
        // And the boundary the cut looks for: a text that ends the way a half-written token would.
        runForm({ quote(LITERAL_ESCAPE_TAIL) }) { onNodeWithText(LITERAL_ESCAPE_TAIL).assertIsDisplayed() }
    }

    /** Ordinary text is handed to the layout untouched — no rebuild, nothing to spell out. */
    @Test
    fun `a plain command is drawn as it is`() {
        runForm({ quote(PLAIN) }) { onNodeWithText(PLAIN).assertIsDisplayed() }
    }

    /**
     * Past the cap the block is cut whatever the layout says, and the caller is told so — the head
     * of a cut block fits its lines, so the layout alone would report it whole.
     */
    @Test
    fun `a command past the drawing cap is reported as not shown whole`() {
        var fit: Boolean? = null
        runForm({ quote("x".repeat(MAX_DRAWN_COMMAND_CHARS + 1), lines = 2000, onFit = { fit = it }) }) {
            waitForIdle()
            assertEquals(false, fit, "a cut command was reported as shown whole")
        }
    }

    /**
     * The cut's own job, on a string it really does cut: escaping inflates the text past the cap, and
     * the character the cap lands inside must leave whole or not at all. Half a token reads as a
     * different code point from the one that is there.
     */
    @Test
    fun `a cut that lands inside a spelled-out character drops it whole`() {
        runForm({ quote(INFLATES_PAST_CAP, lines = 4000) }) {
            val drawn = onNodeWithText(ESCAPED_OVERRIDE, substring = true)
                .fetchSemanticsNode().config[SemanticsProperties.Text].first().text
            assertTrue(drawn.length <= MAX_DRAWN_COMMAND_CHARS, "the cap did not hold: ${drawn.length}")
            assertTrue(drawn.endsWith(">"), "a token was left half written: `${drawn.takeLast(8)}`")
        }
    }

    @Test
    fun `a command inside the cap and its lines is reported as shown whole`() {
        var fit: Boolean? = null
        runForm({ quote(PLAIN, onFit = { fit = it }) }) {
            waitForIdle()
            assertEquals(true, fit)
        }
    }

    /**
     * The latch is for the one caller whose width depends on what this decides — the mobile bar's
     * chips sit in the command's row, so a wider label leaves fewer columns and could flip the
     * answer that chose the label. Once too long, too long until the command changes.
     */
    @Test
    fun `a latched quote stays clipped when the box grows`() {
        var width by mutableStateOf(NARROW)
        var fit: Boolean? = null
        runForm({
            Box(Modifier.width(width)) { quote(PLAIN, lines = 1, latch = true, onFit = { fit = it }) }
        }) {
            waitForIdle()
            assertEquals(false, fit, "one line at $NARROW should not hold the whole command")
            width = WIDE
            waitForIdle()
            assertFalse(fit!!, "the latch let go when the box grew")
        }
    }

    /**
     * A desktop dialog is resizable, so the same command can stop overflowing. The box must stay a
     * focus stop through that: dropping `focusable()` from a node that holds focus makes Compose
     * clear focus to the root, which restarts Tab at the top of the dialog mid-read.
     */
    @Test
    fun `a quote that has been clipped stays reachable when the box grows`() {
        var width by mutableStateOf(NARROW)
        runForm({
            Box(Modifier.width(width)) { quote(PLAIN, lines = 1, label = "sending") }
        }) {
            waitForIdle()
            onNodeWithContentDescription("sending: $PLAIN").assertIsDisplayed()
            width = WIDE
            waitForIdle()
            onNodeWithContentDescription("sending: $PLAIN").assertIsDisplayed()
        }
    }

    /**
     * The notice is announced, not only drawn — and announced as a change: a live region created
     * with its message already on it is an insertion, and Android emits nothing for it. This is the
     * case where the caller knows it is clipped before any layout has happened.
     */
    @Test
    fun `a notice that is clipped from the first frame is still announced as a change`() {
        runForm({ ClippedNotice(clipped = true, fullLength = 4200) }) {
            waitForIdle()
            // The message arrives on a node that was already composed — the announcer is created
            // empty and filled afterwards, which is a change rather than an insertion. The frame in
            // between is not observable from here; what this pins is that the live region is the
            // node carrying it, and that the message lands at all.
            onNode(polite).assertContentDescriptionEquals(noticeText(4200))
        }
    }

    /**
     * The null-length notice — a quote known to be partial with no honest count, the shape a screen
     * row continuing past the cursor publishes (issue #246) — is announced the same way. Without it
     * a screen-reader user is never told the recalled line runs longer than what is drawn.
     */
    @Test
    fun `a notice without a count is still announced`() {
        runForm({ ClippedNotice(clipped = true, fullLength = null) }) {
            waitForIdle()
            onNode(polite).assertContentDescriptionEquals(string(Res.string.command_clipped_partial))
        }
    }

    /**
     * A notice about text still being written is drawn but not read out: the count moves with every
     * delta of a streamed reply, and a live region carrying it would say a new number each time.
     */
    @Test
    fun `a notice about text still arriving is drawn but not announced`() {
        runForm({ ClippedNotice(clipped = true, fullLength = 4200, announce = false) }) {
            waitForIdle()
            onNodeWithText(noticeText(4200)).assertIsDisplayed()
            onNode(polite).assertContentDescriptionEquals("")
        }
    }

    @Test
    fun `a quote that is all there announces nothing`() {
        runForm({ ClippedNotice(clipped = false, fullLength = 12) }) {
            waitForIdle()
            onNode(polite).assertContentDescriptionEquals("")
        }
    }
}

/** The primitive as the surfaces call it, with the parts a test varies. */
@Composable
private fun quote(
    text: String,
    lines: Int = 8,
    latch: Boolean = false,
    label: String = "",
    onFit: (Boolean?) -> Unit = {},
) = CommandQuote(text, visibleLines = lines, latchClipped = latch, label = label, onFit = onFit)

/** The notice as the run's locale writes it — the strings are localized, the machine's locale is not. */
private fun noticeText(length: Int) = string(Res.string.command_clipped, length)

private const val PLAIN = "systemctl restart nginx"

/** Taller than the two lines it is drawn in, so the block scrolls and can be focused. */
private val TALL = (1..4).joinToString("\n") { "systemctl restart service-$it" }

/** Contains the escape's introducer and no closing bracket — a shape the cut must not react to. */
private const val LITERAL_ESCAPE = "sed 's/<U+/x/g' input.log"

/** What one override is drawn as. */
private const val ESCAPED_OVERRIDE = "<U+202E>"

/**
 * Short enough to pass the cap as characters and eight times too long once spelled out, with four
 * characters of head so the cap lands inside a token rather than between two.
 */
private val INFLATES_PAST_CAP = "echo" + "\u202E".repeat(1200)

/** Ends in what a token being written looks like, on a string nothing cut. */
private const val LITERAL_ESCAPE_TAIL = "echo <U+202"
private const val SPOOFED = "echo hello \u202E# rm -rf /srv"
private val NARROW = 60.dp
private val WIDE = 600.dp
