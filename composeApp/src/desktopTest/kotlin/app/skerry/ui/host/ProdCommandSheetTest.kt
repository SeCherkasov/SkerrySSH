package app.skerry.ui.host

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAncestors
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.ai.CommandAssessment
import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.guard.GuardedCommand
import app.skerry.ui.terminal.GuardAside
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.command_clipped
import app.skerry.ui.generated.resources.command_clipped_partial
import app.skerry.ui.generated.resources.guard_prod_command_title
import app.skerry.ui.generated.resources.guard_prod_further_in
import app.skerry.ui.generated.resources.guard_prod_nothing_to_show
import app.skerry.ui.generated.resources.guard_prod_on_line
import app.skerry.ui.generated.resources.guard_prod_sending
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import androidx.compose.ui.test.onNodeWithContentDescription

/**
 * What the production guard's confirmation quotes. It is the last thing read before a command runs
 * on a host tagged `#prod`, so the rule the assistant's command card follows applies here first: it
 * shows what Confirm will run, or it says that it does not.
 *
 * The quote itself is decided in [app.skerry.ui.terminal.ProductionGuardHold] and covered there;
 * what this pins is the drawing — a long block is not cut at the right edge, and one too tall for
 * the dialog is reachable and said to be partial.
 */
@OptIn(ExperimentalTestApi::class)
class ProdCommandSheetTest {

    @Test
    fun `a block taller than the dialog says so and can be scrolled`() = runForm({
        ProdCommandDialog(hostLabel = HOST, guarded = guarded(), quote = TALL_BLOCK, onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.command_clipped, TALL_BLOCK.length)).assertIsDisplayed()
        // Unmerged: the dialog's own clickable merges its descendants, and the scrolling box the
        // block sits in is not a node of the merged tree at all. The dialog scrolls too, so the box
        // is the smallest of the scrolling ancestors, not the only one.
        val box = onNodeWithText(TALL_BLOCK, useUnmergedTree = true).onAncestors()
            .filter(hasScrollAction()).fetchSemanticsNodes()
            .minByOrNull { it.boundsInRoot.height }
        assertNotNull(box, "the block sits in nothing that scrolls")
        val range = box.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "the dialog cut the block with no way to reach the rest")
    }

    /** The common case is one line: no notice, and no Tab stop in front of the buttons for it. */
    @Test
    fun `a command the dialog shows whole says nothing about being partial`() = runForm({
        ProdCommandDialog(hostLabel = HOST, guarded = guarded(), quote = RISKY, onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(RISKY).assertIsDisplayed()
        onNodeWithText(string(Res.string.command_clipped, RISKY.length)).assertDoesNotExist()
    }

    /** The boundary: five lines is inside the dialog's six-line cap and must not read as partial. */
    @Test
    fun `a block that just fits says nothing about being partial`() = runForm({
        ProdCommandDialog(hostLabel = HOST, guarded = guarded(), quote = FIVE_LINES, onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithText(string(Res.string.command_clipped, FIVE_LINES.length)).assertDoesNotExist()
    }

    /** A block longer than what any dialog lays out states the length it really has. */
    @Test
    fun `a block past the drawing cap states its real length`() = runForm({
        ProdCommandDialog(
            hostLabel = HOST, guarded = guarded(), quote = RISKY, quoteLength = HUGE, onConfirm = {}, onDismiss = {},
        )
    }) {
        onNodeWithText(string(Res.string.command_clipped, HUGE)).assertIsDisplayed()
    }

    /**
     * An empty quote is the hold saying it had nothing true to show — the line that tripped the
     * guard was a join of what the client tracked and what this input adds, and that string is one
     * neither side holds. The dialog draws the reason and no command rather than that one.
     */
    @Test
    fun `an empty quote draws no command at all`() = runForm({
        ProdCommandDialog(
            hostLabel = HOST, guarded = guarded(), quote = "", quoteLength = null, onConfirm = {}, onDismiss = {},
        )
    }) {
        onNodeWithText(RISKY).assertDoesNotExist()
        onNodeWithText(string(Res.string.guard_prod_command_title)).assertIsDisplayed()
        // Said outright, not left to a blank box: "shown in part" is for a quote that has a part.
        onNodeWithText(string(Res.string.guard_prod_nothing_to_show)).assertIsDisplayed()
        onNodeWithText(string(Res.string.command_clipped_partial)).assertDoesNotExist()
    }

    /**
     * A line read off the screen is a guess about the host's own drawing; it may not be on the shell
     * line at all. It gives the reason, so it is shown — beside what Confirm sends, never instead of
     * it, and labelled so the two cannot be read as one.
     */
    @Test
    fun `the line already on the shell is drawn beside what is sent, not instead of it`() = runForm({
        ProdCommandDialog(
            hostLabel = HOST, guarded = guarded(), quote = SENT,
            aside = GuardAside(RISKY, RISKY.length, onLine = true), onConfirm = {}, onDismiss = {},
        )
    }) {
        onNodeWithText(SENT).assertIsDisplayed()
        onNodeWithText(RISKY).assertIsDisplayed()
        // Captions go through the shared FieldLabel, which uppercases for the UI locale.
        onNodeWithText(string(Res.string.guard_prod_on_line), ignoreCase = true).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_sending), ignoreCase = true).assertIsDisplayed()
    }

    /**
     * The other side of the same rule: a line from further into the input is drawn under what is
     * sent, in the order the two run, and the quote stays a real prefix of the block rather than
     * being replaced by one short line under a count of ten thousand characters.
     */
    @Test
    fun `a risky line further into the input is drawn under what is sent`() = runForm({
        ProdCommandDialog(
            hostLabel = HOST, guarded = guarded(), quote = TALL_BLOCK, quoteLength = HUGE,
            aside = GuardAside(RISKY, RISKY.length, onLine = false), onConfirm = {}, onDismiss = {},
        )
    }) {
        onNodeWithText(string(Res.string.command_clipped, HUGE)).assertIsDisplayed()
        onNodeWithText(RISKY).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_further_in), ignoreCase = true).assertIsDisplayed()
        // Whichever side the other block is on, the one Confirm sends says so — telling them apart
        // by which one has no caption is not being told.
        onNodeWithText(string(Res.string.guard_prod_sending), ignoreCase = true).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_on_line), ignoreCase = true).assertDoesNotExist()
    }

    /**
     * A line the client only holds the beginning of: it is drawn under the caption that says it is on
     * the shell already, and its notice states that it is a part without a count — no count over a
     * prefix would be true, and one would say the dialog is showing all of the line.
     */
    @Test
    fun `a line the client holds only the beginning of says so without a count`() = runForm({
        ProdCommandDialog(
            hostLabel = HOST, guarded = guarded(), quote = SENT,
            aside = GuardAside(RISKY, length = null, onLine = true), onConfirm = {}, onDismiss = {},
        )
    }) {
        onNodeWithText(RISKY).assertIsDisplayed()
        onNodeWithText(string(Res.string.command_clipped_partial)).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_on_line), ignoreCase = true).assertIsDisplayed()
        onNodeWithText(string(Res.string.guard_prod_further_in), ignoreCase = true).assertDoesNotExist()
        // The counted form belongs to the block that is cut, not to the one that is a guess.
        onNodeWithText(string(Res.string.command_clipped, RISKY.length)).assertDoesNotExist()
    }

    /**
     * Nothing in the sheet takes focus, so the scrim holds it: unnamed, the confirmation that gates
     * a risky command on a production host opens in silence for anyone reading the screen aloud.
     */
    @Test
    fun `the confirmation names itself`() = runForm({
        ProdCommandDialog(hostLabel = HOST, guarded = guarded(), quote = RISKY, onConfirm = {}, onDismiss = {})
    }) {
        onNodeWithContentDescription(string(Res.string.guard_prod_command_title)).assertExists()
    }

    private fun guarded() = GuardedCommand(
        RISKY,
        CommandAssessment(CommandRisk.Danger, CommandRiskReason.RecursiveForceDelete, destructive = true),
    )
}

private const val HOST = "db-prod"
private const val RISKY = "rm -rf /srv/data"

/** What the held input itself sends, when the screen is what tripped the guard. */
private const val SENT = "docker ps"

/** Five short lines: inside the dialog's cap at its width. */
private val FIVE_LINES = (1..5).joinToString("\n") { "systemctl restart s$it" }

/** A length no quote could carry, as the guard reports it for an oversized paste. */
private const val HUGE = 120_000

/** Twelve lines: past the dialog's cap whatever the wrapping does. */
private val TALL_BLOCK = (1..12).joinToString("\n") { "systemctl restart service-$it.service" }
