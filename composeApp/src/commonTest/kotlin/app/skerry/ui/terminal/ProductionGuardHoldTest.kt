package app.skerry.ui.terminal

import app.skerry.shared.guard.MAX_GUARDED_CANDIDATES
import app.skerry.shared.guard.MAX_GUARDED_COMMAND_LENGTH
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.ui.snippet.maskSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two rules every input path shares. [TerminalScreenStateTest] covers them through the real
 * paths (typed, pasted, ready-made); these pin them on the state machine itself.
 */
class ProductionGuardHoldTest {

    private fun guarding() = ProductionGuardHold().apply {
        policy = ProductionGuardPolicy(production = true, confirmWarnings = true)
    }

    @Test
    fun a_session_with_no_guard_holds_nothing_and_never_classifies() {
        val hold = ProductionGuardHold()
        var asked = false
        assertFalse(hold.hold("rm -rf /\n", HeldInputSource.Typed) { policy -> asked = true; ProductionGuard.inspect(listOf("rm -rf /"), policy) })
        // Reading the screen and the tracked line is wasted work off a production host.
        assertFalse(asked)
        assertNull(hold.pending)
    }

    @Test
    fun a_risky_block_is_held_with_the_input_to_replay() {
        val hold = guarding()
        assertTrue(hold.hold("rm -rf /srv\n", HeldInputSource.Paste) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) })
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertEquals(HeldInput("rm -rf /srv\n", HeldInputSource.Paste), hold.take())
        assertNull(hold.pending)
    }

    @Test
    fun nothing_else_runs_while_something_is_held() {
        val hold = guarding()
        hold.hold("rm -rf /srv\n", HeldInputSource.Typed) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) }

        var asked = false
        // Harmless or not, it is held back — and it isn't even classified: the answer is the same.
        assertTrue(hold.hold("uptime\n", HeldInputSource.Command) { policy -> asked = true; ProductionGuard.inspect(listOf("uptime"), policy) })
        assertFalse(asked)
        // What was dropped stays dropped: the pending command is still the one being asked about.
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertEquals("rm -rf /srv\n", hold.take()?.text)
    }

    /**
     * What the confirmation quotes is what will run, and for a ready-made command or a paste that is
     * the input itself — not the classifier's view of it, which is capped in lines and in line
     * length while [ProductionGuardHold.take] replays every byte.
     */
    @Test
    fun a_held_paste_is_quoted_whole() {
        val hold = guarding()
        val block = "rm -rf /srv\nchown -R nobody /srv/www\n"
        assertTrue(hold.hold(block, HeldInputSource.Paste) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv", "chown -R nobody /srv/www", ""), policy) })
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertEquals("rm -rf /srv\nchown -R nobody /srv/www", hold.pendingQuote)
    }

    /**
     * A typed block is keystrokes: what they run is whatever is already on the shell line, and the
     * client only ever guesses at that. With nothing of its own to quote — a bare Enter — the line
     * read off the screen is everything that runs, so it becomes the quote rather than leaving an
     * empty box under a danger reason.
     */
    @Test
    fun a_command_only_the_screen_saw_is_the_one_quoted() {
        val hold = guarding()
        // Enter over a line recalled from history: nothing was typed, so the tracked lines are blank.
        assertTrue(
            hold.hold("\r", HeldInputSource.Typed, screenGuesses = { listOf("rm -rf /srv/data") }) { policy -> ProductionGuard.inspect(listOf("", ""), policy) },
        )
        assertEquals("rm -rf /srv/data", hold.pending?.command)
        assertEquals("rm -rf /srv/data", hold.pendingQuote)
        assertNull(hold.pendingAside, "the quote is already the screen's line")
    }

    /**
     * The other half of the same rule: what the client tracked is not blank, it is simply not the
     * command that tripped the guard — a line recalled from history with something typed after it.
     * The fragment is still what gets sent, so it stays the quote and the recalled line is reported
     * beside it rather than in place of it.
     */
    @Test
    fun a_screen_line_under_a_fragment_is_reported_beside_it() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                " --force\r",
                HeldInputSource.Typed,
                quote = { " --force" },
                screenGuesses = { listOf("rm -rf /srv") },
            ) { policy -> ProductionGuard.inspect(listOf(" --force", ""), policy) },
        )
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertEquals(" --force", hold.pendingQuote)
        assertEquals(GuardAside("rm -rf /srv", "rm -rf /srv".length, onLine = true), hold.pendingAside)
    }

    /**
     * The classifier reads a bounded prefix of a candidate, and a shell line can be longer than that
     * on a wide terminal. What the dialog draws is that prefix; what it must not do is call it the
     * whole command — the count under the box is the only thing saying otherwise.
     */
    @Test
    fun a_line_too_long_for_the_classifier_reports_its_real_length() {
        val hold = guarding()
        val long = "rm -rf /srv/" + "a".repeat(MAX_GUARDED_COMMAND_LENGTH)
        assertTrue(hold.hold("\r", HeldInputSource.Typed, screenGuesses = { listOf(long) }) { policy -> ProductionGuard.inspect(listOf("", ""), policy) })
        assertEquals(MAX_GUARDED_COMMAND_LENGTH, hold.pendingQuote.length)
        assertEquals(long.length, hold.pendingQuoteLength)

        // The same line as the guess drawn beside a quote, where it has a length of its own.
        val beside = guarding()
        assertTrue(
            beside.hold("docker ps\n", HeldInputSource.Paste, screenGuesses = { listOf(long) }) { policy ->
                ProductionGuard.inspect(listOf("docker ps", ""), policy)
            },
        )
        assertEquals(MAX_GUARDED_COMMAND_LENGTH, beside.pendingAside?.line?.length)
        assertEquals(long.length, beside.pendingAside?.length)
    }

    /**
     * A line the shell completed past what the client tracked: the prefix is all there is to quote,
     * and it is by construction not the whole line. The length goes out as null so the dialog says
     * it is showing a part without inventing a number for what it is a part of.
     */
    @Test
    fun a_prefix_standing_in_for_a_blank_quote_reports_no_length() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "\r",
                HeldInputSource.Typed,
                quote = { "" },
                partialGuess = { PartialGuess("rm -rf /srv/bac", "rm -rf /srv/bac") },
            ) { policy -> ProductionGuard.inspect(listOf("", ""), policy) },
        )
        assertEquals("rm -rf /srv/bac", hold.pendingQuote)
        assertNull(hold.pendingQuoteLength)
    }

    /**
     * The same prefix beside a quote that does have content: it is drawn as its own block, and its
     * length goes out as null there too — the caption says it is on the line, and nothing may also
     * say it is all of the line.
     */
    @Test
    fun a_prefix_drawn_beside_a_quote_reports_no_length() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "m -rf /var\n",
                HeldInputSource.Paste,
                quote = { "m -rf /var" },
                partialGuess = { PartialGuess("sudo rm -rf /srv", "sudo rm -rf /srv") },
            ) { policy -> ProductionGuard.inspect(listOf("m -rf /var", ""), policy) },
        )
        assertEquals("m -rf /var", hold.pendingQuote)
        assertEquals(GuardAside("sudo rm -rf /srv", null, onLine = true), hold.pendingAside)
    }

    /**
     * The join is what gets classified; what gets drawn is the line the shell really has as far as
     * the client knows. Drawing the join would caption a string neither side holds as "already on
     * the line".
     */
    @Test
    fun a_join_is_classified_but_the_line_itself_is_drawn() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "uptime\r",
                HeldInputSource.Command,
                quote = { "uptime" },
                partialGuess = { PartialGuess(classify = "rm -rf /sruptime", onLine = "rm -rf /sr") },
            ) { policy -> ProductionGuard.inspect(listOf("uptime", ""), policy) },
        )
        assertEquals("rm -rf /sruptime", hold.pending?.command, "the join is what found the reason")
        assertEquals("uptime", hold.pendingQuote)
        assertEquals(GuardAside("rm -rf /sr", null, onLine = true), hold.pendingAside)
    }

    /**
     * And a tracked line the quote could not carry is reported even when the input's own lines are
     * what tripped the guard: it runs first, and leaving it out hides half of the command.
     */
    @Test
    fun a_line_the_quote_could_not_carry_is_reported_whoever_tripped_the_guard() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "; systemctl restart nginx\n",
                HeldInputSource.Paste,
                quote = { "; systemctl restart nginx" },
                partialGuess = { PartialGuess("rm -rf /srv/back; systemctl restart nginx", "rm -rf /srv/back") },
            ) { policy -> ProductionGuard.inspect(listOf("; systemctl restart nginx", ""), policy) },
        )
        assertEquals("; systemctl restart nginx", hold.pendingQuote)
        assertEquals("rm -rf /srv/back", hold.pendingAside?.line)
    }

    /**
     * Two lines are missing from the quote and there is one block to draw them in. The line the
     * reason is about wins it: a dialog explaining a recursive delete beside a stale `docker p` names
     * neither the danger nor anything the user can act on.
     */
    @Test
    fun the_line_the_reason_is_about_wins_the_one_block() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "docker ps\r",
                HeldInputSource.Typed,
                quote = { "docker ps" },
                screenGuesses = { listOf("rm -rf /srv/data") },
                partialGuess = { PartialGuess("apt-get instadocker ps", "apt-get insta") },
            ) { policy -> ProductionGuard.inspect(listOf("docker ps", ""), policy) },
        )
        assertEquals("rm -rf /srv/data", hold.pending?.command)
        assertEquals("rm -rf /srv/data", hold.pendingAside?.line, "the dialog explained a line it never drew")
    }

    /**
     * The guess that won has no drawable form and something milder was found elsewhere. The milder
     * line does not take its place: it would sit under a reason that is not about it, with a caption
     * about a source it did not come from. The reason stands on its own.
     */
    @Test
    fun a_milder_finding_does_not_stand_in_for_an_undrawable_one() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "\r",
                HeldInputSource.Typed,
                quote = { "" },
                screenGuesses = { listOf("sudo systemctl stop nginx") },
                partialGuess = { PartialGuess(classify = "rm -rf /srv/backx", onLine = null) },
            ) { policy -> ProductionGuard.inspect(listOf("", ""), policy) },
        )
        assertEquals("rm -rf /srv/backx", hold.pending?.command, "the worst is still what is asked about")
        assertEquals("", hold.pendingQuote)
        assertNull(hold.pendingQuoteLength)
        assertNull(hold.pendingAside)
    }

    /**
     * A block too long to draw is cut at the quote, never at the classifier's caps, and how much
     * there is really is stays on record for the dialog to state.
     */
    @Test
    fun an_oversized_block_is_cut_with_its_real_length_kept() {
        val hold = guarding()
        val padding = "# keep the old unit file\n".repeat(400)
        val block = "rm -rf /srv\n$padding"
        assertTrue(hold.hold(block, HeldInputSource.Paste) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) })
        assertEquals(block.trimEnd().length, hold.pendingQuoteLength)
        assertTrue(hold.pendingQuote.length < block.trimEnd().length, "the quote was not cut")
        assertTrue(hold.pendingQuote.startsWith("rm -rf /srv"), "the tripped line is not in the quote")
    }

    /**
     * The block is long enough that its risky line is past what a dialog can draw. The quote stays
     * the block's own beginning — a real prefix, which is what "shown in part" means everywhere else
     * — and the line that tripped the guard is reported beside it instead of replacing it. Replacing
     * it drew one short command in full under a count of ten thousand characters, which reads as a
     * cut line rather than as four hundred lines nobody saw.
     */
    @Test
    fun a_risky_line_past_the_drawing_cap_is_reported_beside_the_quote() {
        val hold = guarding()
        val padding = "# keep the old unit file\n".repeat(400)
        val block = padding + "rm -rf /srv\n"
        assertTrue(hold.hold(block, HeldInputSource.Paste) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) })
        assertTrue(hold.pendingQuote.startsWith("# keep the old unit file"), "the quote is not the block")
        assertEquals(block.trimEnd().length, hold.pendingQuoteLength)
        assertEquals(GuardAside("rm -rf /srv", "rm -rf /srv".length, onLine = false), hold.pendingAside)
    }

    /**
     * A line read off the screen never becomes the quote while there is something to quote. The
     * screen is the host's to draw: standing it in would put a command in the dialog that Confirm is
     * not going to run, and send the one the user never saw. It stays what it always was — a reason.
     */
    @Test
    fun a_screen_line_does_not_replace_a_quote_that_has_content() {
        val hold = guarding()
        val block = "docker ps\nuptime\n"
        assertTrue(
            hold.hold(block, HeldInputSource.Paste, screenGuesses = { listOf("rm -rf /srv/data") }) { policy ->
                ProductionGuard.inspect(listOf("docker ps", "uptime", ""), policy)
            },
        )
        assertEquals("rm -rf /srv/data", hold.pending?.command, "the screen still gives the reason")
        assertEquals(GuardAside("rm -rf /srv/data", "rm -rf /srv/data".length, onLine = true), hold.pendingAside)
        assertEquals(block.trimEnd(), hold.pendingQuote)
        assertEquals(block.trimEnd().length, hold.pendingQuoteLength)
    }

    /**
     * The shape that made it worth removing: the host draws a worse line than the one being run, so
     * it wins the classification, and quoting it would hide a real command behind an invented one.
     */
    @Test
    fun a_screen_line_worse_than_the_input_still_quotes_the_input() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                " --one-file-system\n",
                HeldInputSource.Paste,
                screenGuesses = { listOf("rm -rf /srv/data/archive") },
            ) { policy -> ProductionGuard.inspect(listOf(" --one-file-system", ""), policy) },
        )
        assertEquals(" --one-file-system", hold.pendingQuote)
        assertEquals(" --one-file-system".length, hold.pendingQuoteLength)
        assertEquals("rm -rf /srv/data/archive", hold.pendingAside?.line)
    }

    /**
     * Both sides trip, the screen's line is the worse of the two, and the block's own risky line sits
     * past what can be drawn. The loser must not stand in: quoting it would put a `chmod` under a
     * reason about an `rm`, and drop the line that reason is actually about.
     */
    @Test
    fun a_losing_line_of_the_input_never_stands_in_for_the_winner() {
        val hold = guarding()
        val padding = "# keep the old unit file\n".repeat(400)
        val block = padding + "chmod 777 /tmp\n"
        assertTrue(
            hold.hold(
                block,
                HeldInputSource.Paste,
                screenGuesses = { listOf("sudo rm -rf /var") },
            ) { policy -> ProductionGuard.inspect(listOf("chmod 777 /tmp"), policy) },
        )
        assertEquals("sudo rm -rf /var", hold.pending?.command)
        assertEquals("sudo rm -rf /var", hold.pendingAside?.line)
        assertTrue(hold.pendingQuote.startsWith("# keep the old unit file"), "the quote is not the paste")
        assertEquals(block.trimEnd().length, hold.pendingQuoteLength)
    }

    /**
     * The screen's guesses are classified beside the block, not inside its budget. Sharing one cap
     * let a prompt row on screen push the last line of a full-length paste out of the classification
     * — and the last line of a script is where its cleanup lives.
     */
    @Test
    fun a_screen_guess_does_not_push_the_last_line_of_a_block_past_the_cap() {
        val hold = guarding()
        val lines = List(MAX_GUARDED_CANDIDATES - 1) { "echo $it" } + "rm -rf /srv"
        assertTrue(
            hold.hold(
                lines.joinToString("\n"),
                HeldInputSource.Paste,
                screenGuesses = { listOf("user@host:~$") },
            ) { policy -> ProductionGuard.inspect(lines, policy) },
        )
        assertEquals("rm -rf /srv", hold.pending?.command)

        // And the other way round: a full-length block cannot starve the guess either.
        val guessing = guarding()
        assertTrue(
            guessing.hold(
                List(MAX_GUARDED_CANDIDATES) { "echo $it" }.joinToString("\n"),
                HeldInputSource.Paste,
                screenGuesses = { listOf("rm -rf /srv") },
            ) { policy -> ProductionGuard.inspect(List(MAX_GUARDED_CANDIDATES) { "echo $it" }, policy) },
        )
        assertEquals("rm -rf /srv", guessing.pending?.command)
    }

    /**
     * A screen row the cursor sits inside of is a beginning of the line that runs: the aside may
     * draw it, but no count over it would be true — everything right of the cursor is not in it.
     */
    @Test
    fun a_screen_finding_from_a_row_the_cursor_sits_inside_reports_no_count() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "docker ps\n",
                HeldInputSource.Paste,
                screenGuesses = { listOf("rm -rf /srv/data") },
                screenLineCut = { true },
            ) { policy -> ProductionGuard.inspect(listOf("docker ps"), policy) },
        )
        assertEquals("rm -rf /srv/data", hold.pendingAside?.line)
        assertNull(hold.pendingAside?.length, "the row continues past the cursor; a count claims it is whole")
    }

    /**
     * The mask covers the aside as well as the quote: the line that tripped the guard can carry the
     * resolved secret, and drawing it beside a masked quote would undo the masking one box lower.
     */
    @Test
    fun a_secret_in_the_aside_is_masked() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "docker ps\n",
                HeldInputSource.Paste,
                present = { maskSecrets(it, listOf("hunter2")) },
                screenGuesses = { listOf("echo hunter2 | sudo -S systemctl stop nginx") },
            ) { policy -> ProductionGuard.inspect(listOf("docker ps"), policy) },
        )
        val aside = hold.pendingAside
        assertNotNull(aside, "the tripped line lost its aside")
        assertFalse("hunter2" in aside.line, "the resolved secret is drawn in the aside: ${aside.line}")
        // The count follows the mask, as the quote's does: the raw length beside a masked line read
        // as "shown in part" over a box that is fully drawn.
        assertEquals(aside.line.length, aside.length, "a fully drawn masked aside claimed a hidden tail")
    }

    /**
     * A masked quote that is fully on screen claims nothing hidden: the count describes the drawn
     * (masked) text, because a pre-mask count read as "shown in part" over a dialog with nothing
     * left to scroll to — the mask itself already marks what is redacted.
     */
    @Test
    fun a_masked_quote_fully_drawn_claims_no_hidden_tail() {
        val hold = guarding()
        val line = "echo hunter2 | sudo -S systemctl stop nginx"
        assertTrue(
            hold.hold("$line\n", HeldInputSource.Command, present = { maskSecrets(it, listOf("hunter2")) }) { policy ->
                ProductionGuard.inspect(listOf(line), policy)
            },
        )
        assertEquals(hold.pendingQuote.length, hold.pendingQuoteLength, "a fully drawn masked quote claimed a hidden tail")
    }

    /**
     * A rule's finding outranks the overflow fallback: it explains a risk that was actually read,
     * and one confirmation is asked either way. BeyondInspection may only stand when no rule fired.
     */
    @Test
    fun a_rule_finding_outranks_beyond_inspection() {
        val hold = guarding()
        val block = (listOf("rm -rf /srv") + List(MAX_GUARDED_CANDIDATES + 20) { "echo $it" }).joinToString("\n")
        assertTrue(
            hold.hold(block, HeldInputSource.Paste) { policy ->
                ProductionGuard.inspectCandidates(ProductionGuard.candidatesOf(block), policy)
            },
        )
        assertEquals(CommandRiskReason.RecursiveForceDelete, hold.pending?.assessment?.reason)
    }

    /** A guess that loses says nothing: the dialog would be stating a line that is not the reason. */
    @Test
    fun a_guess_that_loses_is_not_reported_beside_the_quote() {
        val hold = guarding()
        assertTrue(
            hold.hold(
                "rm -rf /srv\n",
                HeldInputSource.Paste,
                screenGuesses = { listOf("sudo systemctl stop nginx") },
            ) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) },
        )
        assertEquals("rm -rf /srv", hold.pending?.command)
        assertNull(hold.pendingAside)
    }

    @Test
    fun a_typed_block_is_quoted_from_what_it_will_run() {
        val hold = guarding()
        // As the terminal calls it: the quote is what the shell line already holds plus this block.
        assertTrue(hold.hold("rf /srv\r", HeldInputSource.Typed, quote = { "rm -" + "rf /srv\r" }) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv", ""), policy) })
        assertEquals("rm -rf /srv", hold.pendingQuote)
    }

    @Test
    fun the_quote_is_dropped_with_the_hold() {
        val hold = guarding()
        hold.hold("rm -rf /srv\n", HeldInputSource.Paste) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) }
        hold.take()
        assertEquals("", hold.pendingQuote)

        hold.hold("rm -rf /srv\n", HeldInputSource.Paste) { policy -> ProductionGuard.inspect(listOf("rm -rf /srv"), policy) }
        hold.dismiss()
        assertEquals("", hold.pendingQuote)
    }

    @Test
    fun a_harmless_block_is_not_held() {
        val hold = guarding()
        assertFalse(hold.hold("uptime\n", HeldInputSource.Typed) { policy -> ProductionGuard.inspect(listOf("uptime"), policy) })
        assertNull(hold.pending)
        assertNull(hold.take())
    }

    @Test
    fun confirming_twice_replays_nothing_the_second_time() {
        val hold = guarding()
        hold.hold("shutdown now\n", HeldInputSource.Command) { policy -> ProductionGuard.inspect(listOf("shutdown now"), policy) }

        assertEquals("shutdown now\n", hold.take()?.text)
        // A double click on Confirm must not run the command again.
        assertNull(hold.take())
    }

    @Test
    fun dismissing_drops_the_held_input() {
        val hold = guarding()
        hold.hold("shutdown now\n", HeldInputSource.Typed) { policy -> ProductionGuard.inspect(listOf("shutdown now"), policy) }

        hold.dismiss()
        assertNull(hold.pending)
        assertNull(hold.take())
        // The next command is judged on its own again.
        assertTrue(hold.hold("rm -rf /etc\n", HeldInputSource.Typed) { policy -> ProductionGuard.inspect(listOf("rm -rf /etc"), policy) })
    }
}
