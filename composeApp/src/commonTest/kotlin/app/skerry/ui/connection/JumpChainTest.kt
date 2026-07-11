package app.skerry.ui.connection

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshJump
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ProxyJump chain resolution: saved profile references ([Host.jumpHostId]) → a connect-ready
 * [SshJump] chain, or a typed problem (dangling id / non-SSH hop / secretless hop / cycle). Pure
 * logic shared by desktop, mobile, tunnels and the connection test — no Compose here.
 */
class JumpChainTest {

    private val hosts = mutableMapOf<String, Host>()
    private val credentials = mutableMapOf<String, Credential>()

    private fun host(
        id: String,
        jumpHostId: String? = null,
        credentialId: String? = "cred-$id",
        connectionType: ConnectionType = ConnectionType.SSH,
    ): Host {
        credentialId?.let { credentials[it] = Credential(it, "pw of $id", CredentialSecret.Password("secret-$id")) }
        return Host(
            id = id, label = id, address = "$id.example.com", port = 22, username = "root",
            credentialId = credentialId, connectionType = connectionType, jumpHostId = jumpHostId,
        ).also { hosts[id] = it }
    }

    private fun resolve(target: Host): JumpChainResolution =
        resolveJumpChain(target, findHost = hosts::get, findCredential = { id -> id?.let(credentials::get) })

    @Test
    fun host_without_jump_resolves_to_null_chain() {
        val resolved = resolve(host("web")) as JumpChainResolution.Resolved
        assertNull(resolved.jump)
    }

    @Test
    fun single_hop_resolves_host_port_user_and_auth() {
        host("bastion")
        val target = host("web", jumpHostId = "bastion")
        val resolved = resolve(target) as JumpChainResolution.Resolved
        assertEquals(
            SshJump("bastion.example.com", 22, "root", SshAuth.Password("secret-bastion")),
            resolved.jump,
        )
    }

    @Test
    fun chain_resolves_outermost_hop_innermost_in_structure() {
        host("outer")
        host("inner", jumpHostId = "outer")
        val target = host("web", jumpHostId = "inner")
        val resolved = resolve(target) as JumpChainResolution.Resolved
        // Target connects via inner, which itself connects via outer.
        assertEquals("inner.example.com", resolved.jump?.host)
        assertEquals("outer.example.com", resolved.jump?.jump?.host)
        assertNull(resolved.jump?.jump?.jump)
    }

    @Test
    fun dangling_jump_reference_is_missing_host() {
        val target = host("web", jumpHostId = "deleted")
        assertEquals(
            JumpChainResolution.Unavailable(JumpChainProblem.MISSING_HOST),
            resolve(target),
        )
    }

    @Test
    fun non_ssh_jump_host_is_rejected() {
        host("console", connectionType = ConnectionType.TELNET)
        val target = host("web", jumpHostId = "console")
        assertEquals(
            JumpChainResolution.Unavailable(JumpChainProblem.NOT_SSH),
            resolve(target),
        )
    }

    @Test
    fun jump_host_without_saved_secret_is_rejected() {
        host("bastion", credentialId = null)
        val target = host("web", jumpHostId = "bastion")
        assertEquals(
            JumpChainResolution.Unavailable(JumpChainProblem.NO_CREDENTIAL),
            resolve(target),
        )
    }

    @Test
    fun jump_host_with_dangling_credential_is_rejected() {
        host("bastion", credentialId = "gone")
        credentials.remove("gone")
        val target = host("web", jumpHostId = "bastion")
        assertEquals(
            JumpChainResolution.Unavailable(JumpChainProblem.NO_CREDENTIAL),
            resolve(target),
        )
    }

    @Test
    fun self_reference_is_a_cycle() {
        val target = host("web", jumpHostId = "web")
        assertEquals(
            JumpChainResolution.Unavailable(JumpChainProblem.CYCLE),
            resolve(target),
        )
    }

    @Test
    fun two_host_loop_is_a_cycle() {
        host("a", jumpHostId = "b")
        host("b", jumpHostId = "a")
        val target = host("web", jumpHostId = "a")
        assertEquals(
            JumpChainResolution.Unavailable(JumpChainProblem.CYCLE),
            resolve(target),
        )
    }

    // Route label for info panels.

    @Test
    fun route_label_is_null_without_jump_and_entry_first_for_chains() {
        assertNull(jumpRouteLabel(host("plain"), hosts::get))
        host("outer")
        host("inner", jumpHostId = "outer")
        val target = host("web", jumpHostId = "inner")
        assertEquals("outer → inner", jumpRouteLabel(target, hosts::get))
    }

    @Test
    fun route_label_marks_a_dangling_reference() {
        val target = host("web", jumpHostId = "deleted")
        assertEquals("?", jumpRouteLabel(target, hosts::get))
    }

    // Picker candidates: which saved hosts may be offered as a jump for the edited profile.

    @Test
    fun candidates_are_ssh_hosts_excluding_self() {
        val bastion = host("bastion")
        host("serial", connectionType = ConnectionType.SERIAL, credentialId = null)
        host("console", connectionType = ConnectionType.TELNET, credentialId = null)
        val edited = host("web")
        assertEquals(listOf(bastion), jumpHostCandidates(hosts.values.toList(), edited.id))
    }

    @Test
    fun candidates_exclude_hosts_whose_chain_reaches_the_edited_one() {
        val edited = host("web")
        host("behindWeb", jumpHostId = "web")
        host("behindBehind", jumpHostId = "behindWeb")
        val free = host("bastion")
        assertEquals(listOf(free), jumpHostCandidates(hosts.values.toList(), edited.id))
    }

    @Test
    fun new_host_offers_all_ssh_hosts() {
        val a = host("a")
        val b = host("b", jumpHostId = "a")
        assertEquals(listOf(a, b), jumpHostCandidates(hosts.values.toList(), editingId = null))
    }
}
