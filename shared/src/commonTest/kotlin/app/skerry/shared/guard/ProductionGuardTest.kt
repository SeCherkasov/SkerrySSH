package app.skerry.shared.guard

import app.skerry.shared.ai.CommandRisk
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

    @Test
    fun the_number_of_candidates_is_capped() {
        // Pasting a log file into a terminal must not run thousands of regex passes inline.
        val many = List(MAX_GUARDED_CANDIDATES + 50) { "echo line $it" } + "rm -rf /"
        assertNull(ProductionGuard.inspect(many, GUARDING)) // the tail past the cap is not classified
        assertNotNull(ProductionGuard.inspect(listOf("rm -rf /") + many, GUARDING))
    }

    @Test
    fun candidates_are_capped_by_length() {
        // A pathological line (a screen row full of output) must not reach the regex engine whole.
        val long = "x".repeat(MAX_GUARDED_COMMAND_LENGTH + 500)
        assertTrue(ProductionGuard.promptCandidates(long).all { it.length <= MAX_GUARDED_COMMAND_LENGTH })
    }
}
