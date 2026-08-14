package app.skerry.shared.guard

import app.skerry.shared.ai.CommandRisk
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.tag.PROD_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionGuardTest {

    // A production session with warnings on, so these tests exercise classification rather than the
    // threshold — the threshold has its own tests in [ProductionGuardPolicyTest].
    private val GUARDING = ProductionGuardPolicy(production = true, confirmWarnings = true)

    @Test
    fun production_is_decided_by_the_prod_tag() {
        assertTrue(ProductionGuard.isProduction(listOf("web", PROD_TAG)))
        assertFalse(ProductionGuard.isProduction(listOf("staging", "db")))
        assertFalse(ProductionGuard.isProduction(emptyList()))
    }

    @Test
    fun risky_command_is_flagged_with_its_reason() {
        val guarded = ProductionGuard.inspect(listOf("rm -rf /var/lib/data"), GUARDING)
        assertNotNull(guarded)
        assertEquals(CommandRisk.Danger, guarded.assessment.risk)
        assertEquals("rm -rf /var/lib/data", guarded.command)
        assertNotNull(guarded.assessment.reason)
    }

    @Test
    fun warn_level_commands_are_confirmed_too() {
        // sudo/systemctl stop are Warn, not Danger: on production they still ask.
        val sudo = ProductionGuard.inspect(listOf("sudo systemctl restart nginx"), GUARDING)
        assertNotNull(sudo)
        assertEquals(CommandRisk.Warn, sudo.assessment.risk)
        assertEquals(CommandRisk.Warn, ProductionGuard.inspect(listOf("systemctl stop postgres"), GUARDING)?.assessment?.risk)
    }

    @Test
    fun harmless_commands_pass_through() {
        assertNull(ProductionGuard.inspect(listOf("ls -la"), GUARDING))
        assertNull(ProductionGuard.inspect(listOf(""), GUARDING))
        assertNull(ProductionGuard.inspect(emptyList(), GUARDING))
    }

    @Test
    fun the_riskiest_candidate_wins() {
        // The typed line and the line read off the screen can disagree; the worse one decides.
        val guarded = ProductionGuard.inspect(listOf("systemctl stop nginx", "rm -rf /etc"), GUARDING)
        assertNotNull(guarded)
        assertEquals(CommandRisk.Danger, guarded.assessment.risk)
        assertEquals("rm -rf /etc", guarded.command)
    }

    @Test
    fun equally_risky_candidates_are_quoted_without_the_prompt() {
        val guarded = ProductionGuard.inspect(ProductionGuard.promptCandidates("root@prod:~# rm -rf /srv"), GUARDING)
        assertEquals("rm -rf /srv", guarded?.command)
    }

    @Test
    fun prompt_line_yields_both_the_full_line_and_the_command_after_the_prompt() {
        val candidates = ProductionGuard.promptCandidates("user@host:~$ rm -rf /tmp/cache")
        assertTrue("rm -rf /tmp/cache" in candidates)
        assertTrue(candidates.any { it.startsWith("user@host") })
    }

    @Test
    fun prompt_stripping_keeps_a_redirect_inside_the_command() {
        // Cutting at the LAST prompt-looking character would leave "/dev/sda" and lose the redirect.
        val candidates = ProductionGuard.promptCandidates("root@db ~ # cat img > /dev/sda")
        assertTrue(candidates.any { it == "cat img > /dev/sda" })
        assertNotNull(ProductionGuard.inspect(candidates, GUARDING))
    }

    @Test
    fun a_line_without_a_prompt_is_used_as_is() {
        assertEquals(listOf("rm -rf /tmp"), ProductionGuard.promptCandidates("  rm -rf /tmp  "))
        assertEquals(emptyList(), ProductionGuard.promptCandidates("   "))
    }

    @Test
    fun prompt_with_nothing_typed_yet_produces_no_command() {
        // Only the prompt on screen: the tail is empty, and the prompt itself is not a command.
        assertNull(ProductionGuard.inspect(ProductionGuard.promptCandidates("user@host:~$ "), GUARDING))
    }

    /**
     * The rule two lists of candidates are compared by, on its own: risk first, and at equal risk the
     * shorter command — the same command shows up with and without its prompt prefix, and what ran is
     * the half without it.
     */
    @Test
    fun the_worse_of_two_findings_is_the_riskier_and_then_the_shorter() {
        val danger = assertNotNull(ProductionGuard.inspect("rm -rf /srv", GUARDING))
        val warn = assertNotNull(ProductionGuard.inspect("sudo systemctl stop nginx", GUARDING))
        val prompted = assertNotNull(ProductionGuard.inspect("root@prod:~# rm -rf /srv", GUARDING))

        assertEquals(danger, ProductionGuard.worse(warn, danger))
        assertEquals(danger, ProductionGuard.worse(danger, warn))
        assertEquals(danger, ProductionGuard.worse(prompted, danger))
        // Neither wins by being second: a tie keeps what was already found.
        assertEquals(danger, ProductionGuard.worse(danger, danger.copy()))
        assertEquals(danger, ProductionGuard.worse(null, danger))
        assertEquals(danger, ProductionGuard.worse(danger, null))
        assertNull(ProductionGuard.worse(null, null))
    }

    @Test
    fun the_number_of_candidates_is_capped() {
        // Pasting a log file into a terminal must not run thousands of regex passes inline.
        val many = List(MAX_GUARDED_CANDIDATES + 50) { "echo line $it" } + "rm -rf /"
        assertNull(ProductionGuard.inspect(many, GUARDING)) // the tail past the cap is not classified
        assertNotNull(ProductionGuard.inspect(listOf("rm -rf /") + many, GUARDING))
    }

    @Test
    fun a_long_candidate_is_cut_for_the_classifier_and_keeps_its_length() {
        // A pathological line (a screen row full of output) must not reach the regex engine whole.
        // For a row read off the screen the cut happens in inspect and only there: a dialog quoting
        // what was found has to say how much of it it is not showing, and the row is bounded by the
        // terminal anyway. A pasted block is not bounded by anything and stays cut on the way in.
        val long = "rm -rf /srv/" + "x".repeat(MAX_GUARDED_COMMAND_LENGTH)
        val candidates = ProductionGuard.promptCandidates(long)
        assertTrue(candidates.all { it.length > MAX_GUARDED_COMMAND_LENGTH }, "cut before the classifier")
        val guarded = assertNotNull(ProductionGuard.inspect(candidates, GUARDING))
        assertEquals(MAX_GUARDED_COMMAND_LENGTH, guarded.command.length)
        assertEquals(long.length, guarded.fullLength)
    }

    @Test
    fun splitting_an_input_block_caps_the_work_before_the_list_exists() {
        // A paste can be a whole log file. Splitting it eagerly would allocate a String per line
        // before the cap in inspect() ever applies — the cap has to happen while splitting.
        val text = (0 until MAX_GUARDED_CANDIDATES + 500).joinToString("\n") { "echo line $it" }
        assertEquals(MAX_GUARDED_CANDIDATES, ProductionGuard.candidatesOf(text).size)
        // And in length, so a two-line paste of a log file is not held in memory twice over.
        val longLine = "x".repeat(MAX_GUARDED_COMMAND_LENGTH + 500)
        val cut = ProductionGuard.candidatesOf(longLine)
        assertTrue(cut.all { it.command.length <= MAX_GUARDED_COMMAND_LENGTH })
        // The cut candidate still knows the real length, so a dialog's count is not the cut's.
        assertEquals(longLine.length, cut.single().fullLength)
    }

    @Test
    fun splitting_handles_every_line_ending() {
        assertEquals(listOf("a", "b", "c"), ProductionGuard.candidatesOf("a\nb\rc").map { it.command })
        assertEquals(listOf("a", "b"), ProductionGuard.candidatesOf("a\r\nb").map { it.command })
    }

    @Test
    fun a_command_split_out_of_a_block_is_still_classified() {
        // The risky line can sit anywhere in a pasted block, not only on the first line.
        val guarded = ProductionGuard.inspectCandidates(ProductionGuard.candidatesOf("cd /srv\nrm -rf /srv/data\n"), GUARDING)
        assertEquals("rm -rf /srv/data", guarded?.command)
    }

    @Test
    fun overflow_flags_a_line_past_the_candidate_cap() {
        // A payload on line 201 of a block was never classified — that fact alone is the finding.
        val text = (0..MAX_GUARDED_CANDIDATES).joinToString("\n") { "echo $it" }
        val over = assertNotNull(ProductionGuard.overflow(text, GUARDING))
        assertEquals(CommandRiskReason.BeyondInspection, over.assessment.reason)
        assertEquals(CommandRisk.Danger, over.assessment.risk)
    }

    @Test
    fun overflow_flags_a_line_longer_than_the_classifier_reads() {
        val line = "x".repeat(MAX_GUARDED_COMMAND_LENGTH + 100)
        val over = assertNotNull(ProductionGuard.overflow(line, GUARDING))
        assertEquals(MAX_GUARDED_COMMAND_LENGTH, over.command.length)
        assertEquals(line.length, over.fullLength)
    }

    @Test
    fun overflow_passes_a_block_inside_both_caps() {
        assertNull(ProductionGuard.overflow("echo one\necho two", GUARDING))
        // Blank lines past the cap run nothing and raise nothing.
        assertNull(ProductionGuard.overflow("echo one" + "\n".repeat(MAX_GUARDED_CANDIDATES + 50), GUARDING))
    }

    @Test
    fun overflow_bounds_its_scan_over_blank_runs() {
        // A run of blank lines must not turn the scan into a second full pass over a huge paste:
        // past the bound the tail is unread by definition, so the answer errs toward asking.
        val text = "echo one" + "\n".repeat(MAX_GUARDED_CANDIDATES * 2 + 50)
        val over = assertNotNull(ProductionGuard.overflow(text, GUARDING))
        assertEquals(CommandRiskReason.BeyondInspection, over.assessment.reason)
        assertEquals("", over.command)
    }

    @Test
    fun overflow_is_off_without_production() {
        assertNull(ProductionGuard.overflow("x".repeat(MAX_GUARDED_COMMAND_LENGTH * 4), ProductionGuardPolicy.Off))
    }
}
