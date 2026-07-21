package app.skerry.ui.tunnel

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.ui.connection.JumpChainProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TunnelFormTest {

    @Test
    fun `builds a local draft from valid fields`() {
        val draft = buildTunnelDraft(
            id = null, label = "web", hostId = "h1", direction = TunnelDirection.Local,
            bindHost = "127.0.0.1", bindPort = "8080", destHost = "10.0.0.5", destPort = "80",
        )
        assertEquals(
            TunnelDraft(null, "web", "h1", TunnelDirection.Local, "127.0.0.1", 8080, "10.0.0.5", 80),
            draft,
        )
    }

    @Test
    fun `dynamic draft drops destination`() {
        val draft = buildTunnelDraft(
            id = null, label = "socks", hostId = "h1", direction = TunnelDirection.Dynamic,
            bindHost = "", bindPort = "1080", destHost = "", destPort = "",
        )
        assertEquals(
            TunnelDraft(null, "socks", "h1", TunnelDirection.Dynamic, "127.0.0.1", 1080, null, null),
            draft,
        )
    }

    @Test
    fun `blank name or host is invalid`() {
        val noName = buildTunnelDraft(null, "  ", "h1", TunnelDirection.Local, "127.0.0.1", "80", "a", "1")
        val noHost = buildTunnelDraft(null, "web", null, TunnelDirection.Local, "127.0.0.1", "80", "a", "1")
        assertNull(noName)
        assertNull(noHost)
    }

    @Test
    fun `local without destination is invalid`() {
        val draft = buildTunnelDraft(null, "web", "h1", TunnelDirection.Local, "127.0.0.1", "8080", "", "")
        assertNull(draft)
    }

    @Test
    fun `out of range ports are invalid`() {
        val badBind = buildTunnelDraft(null, "web", "h1", TunnelDirection.Local, "127.0.0.1", "99999", "a", "1")
        val zeroDest = buildTunnelDraft(null, "web", "h1", TunnelDirection.Local, "127.0.0.1", "8080", "a", "0")
        assertNull(badBind)
        assertNull(zeroDest)
    }

    @Test
    fun `bind port zero is allowed`() {
        val draft = buildTunnelDraft(null, "web", "h1", TunnelDirection.Local, "127.0.0.1", "0", "a", "1")
        assertEquals(0, draft?.bindPort)
    }

    private val host = Host("h1", "prod", "10.0.0.5", 22, "deploy", credentialId = "c1")
    private val credential = Credential("c1", "deploy@prod", CredentialSecret.Password("pw"))
    private val tunnel = Tunnel("t1", "web", "h1", TunnelDirection.Local, "127.0.0.1", 8080, "10.0.0.5", 80)

    @Test
    fun `resolve returns Ready with target and auth`() {
        val r = assertIs<TunnelResolution.Ready>(
            resolveTunnelHost(tunnel.hostId, findHost = { host }, findCredential = { credential }),
        )
        assertEquals("10.0.0.5", r.target.host)
        assertEquals("deploy", r.target.username)
        assertIs<SshAuth.Password>(r.auth)
    }

    @Test
    fun `resolve reports missing host`() {
        val r = assertIs<TunnelResolution.Unavailable>(
            resolveTunnelHost(tunnel.hostId, findHost = { null }, findCredential = { credential }),
        )
        assertEquals(TunnelUnavailable.HostNotFound, r.reason)
    }

    @Test
    fun `resolve reports missing credential`() {
        val r = assertIs<TunnelResolution.Unavailable>(
            resolveTunnelHost(tunnel.hostId, findHost = { host }, findCredential = { null }),
        )
        assertEquals(TunnelUnavailable.NoCredential, r.reason)
    }

    @Test
    fun `resolve routes the tunnel through the host's jump chain`() {
        val bastion = Host("j1", "bastion", "bastion.example.com", 22, "gate", credentialId = "c2")
        val jumped = host.copy(jumpHostId = "j1")
        val hosts = mapOf("h1" to jumped, "j1" to bastion)
        val creds = mapOf(
            "c1" to credential,
            "c2" to Credential("c2", "gate@bastion", CredentialSecret.Password("jump-pw")),
        )
        val r = assertIs<TunnelResolution.Ready>(
            resolveTunnelHost(tunnel.hostId, findHost = { hosts[it] }, findCredential = { creds[it] }),
        )
        assertEquals("bastion.example.com", r.target.jump?.host)
        assertEquals("gate", r.target.jump?.username)
    }

    @Test
    fun `resolve reports a broken jump chain`() {
        val bastion = Host("j1", "bastion", "bastion.example.com", 22, "gate", credentialId = null)
        val jumped = host.copy(jumpHostId = "j1")
        val hosts = mapOf("h1" to jumped, "j1" to bastion)
        val r = assertIs<TunnelResolution.Unavailable>(
            resolveTunnelHost(tunnel.hostId, findHost = { hosts[it] }, findCredential = { if (it == "c1") credential else null }),
        )
        assertEquals(TunnelUnavailable.Jump(JumpChainProblem.NO_CREDENTIAL), r.reason)
    }
}
