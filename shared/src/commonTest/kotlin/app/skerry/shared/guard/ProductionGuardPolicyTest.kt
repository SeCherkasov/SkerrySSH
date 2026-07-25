package app.skerry.shared.guard

import app.skerry.shared.ai.CommandRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the guard actually asks about: the default is Danger only, warnings are opt-in, and a root
 * session shifts the line — `sudo` stops meaning anything while a destructive Warn loses the sudo
 * step that used to sit in front of it.
 */
class ProductionGuardPolicyTest {

    private val plain = ProductionGuardPolicy(production = true)
    private val warnings = ProductionGuardPolicy(production = true, confirmWarnings = true)
    private val root = ProductionGuardPolicy(production = true, rootLogin = true)

    @Test
    fun a_non_production_session_is_never_guarded() {
        assertNull(ProductionGuard.inspect("rm -rf /", ProductionGuardPolicy.Off))
        assertNull(ProductionGuard.inspect("rm -rf /", ProductionGuardPolicy(production = false, confirmWarnings = true)))
    }

    @Test
    fun danger_is_confirmed_by_default() {
        assertEquals(CommandRisk.Danger, ProductionGuard.inspect("rm -rf /var", plain)?.assessment?.risk)
        assertNotNull(ProductionGuard.inspect("mkfs.ext4 /dev/sda1", plain))
        assertNotNull(ProductionGuard.inspect("reboot", plain))
    }

    @Test
    fun warnings_pass_until_the_user_opts_in() {
        // sudo is half of what gets typed on a production box: asking every time trains the dialog away.
        assertNull(ProductionGuard.inspect("sudo systemctl restart nginx", plain))
        assertNull(ProductionGuard.inspect("systemctl stop postgres", plain))
        assertNull(ProductionGuard.inspect("rm app.log", plain))

        assertNotNull(ProductionGuard.inspect("sudo systemctl restart nginx", warnings))
        assertNotNull(ProductionGuard.inspect("systemctl stop postgres", warnings))
        assertNotNull(ProductionGuard.inspect("rm app.log", warnings))
    }

    @Test
    fun sudo_says_nothing_in_a_root_session() {
        // Nobody types sudo as root; flagging it would be pure noise even with warnings on.
        assertNull(ProductionGuard.inspect("sudo apt update", root))
        assertNull(
            ProductionGuard.inspect(
                "sudo apt update",
                ProductionGuardPolicy(production = true, confirmWarnings = true, rootLogin = true),
            ),
        )
    }

    @Test
    fun a_destructive_warning_is_confirmed_for_root_even_with_warnings_off() {
        // As root there is no sudo step in front of these — the guard is the only pause left.
        assertNotNull(ProductionGuard.inspect("rm app.log", root))
        assertNotNull(ProductionGuard.inspect("git reset --hard HEAD~1", root))
        assertNotNull(ProductionGuard.inspect("find /var/log -name '*.gz' -delete", root))
        // A non-destructive warning still passes: stopping a service is undone by starting it.
        assertNull(ProductionGuard.inspect("systemctl stop postgres", root))
    }

    @Test
    fun root_still_confirms_every_danger() {
        assertNotNull(ProductionGuard.inspect("rm -rf /", root))
        assertNotNull(ProductionGuard.inspect("iptables --flush", root))
    }
}
