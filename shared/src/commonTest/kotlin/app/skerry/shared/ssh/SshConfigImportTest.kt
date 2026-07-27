package app.skerry.shared.ssh

import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshConfigImportTest {

    private fun ids(): () -> String {
        var n = 0
        return { "id-${++n}" }
    }

    private fun host(
        alias: String,
        hostName: String = alias,
        port: Int = 22,
        user: String? = null,
        proxyJump: String? = null,
        identityFile: String? = null,
        certificateFile: String? = null,
    ) = SshConfigHost(alias, hostName, port, user, proxyJump, identityFile, certificateFile)

    @Test
    fun `maps parsed fields onto a host profile`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", hostName = "10.0.0.1", port = 2222, user = "deploy")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        val h = plan.hosts.single()
        assertEquals("web", h.label)
        assertEquals("10.0.0.1", h.address)
        assertEquals(2222, h.port)
        assertEquals("deploy", h.username)
        assertEquals(ConnectionType.SSH, h.connectionType)
        assertNull(h.credentialId)
        assertNull(h.jumpHostId)
        // An ssh_config carries no free-form remark for us to import — the note starts out empty
        // rather than picking up an alias/comment.
        assertNull(h.notes)
        assertEquals("id-1", h.id)
    }

    @Test
    fun `default user fills in when the config omits User`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web")),
            selected = setOf("web"),
            defaultUser = "localuser",
            newId = ids(),
        )
        assertEquals("localuser", plan.hosts.single().username)
    }

    @Test
    fun `config User wins over the default user`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", user = "configured")),
            selected = setOf("web"),
            defaultUser = "localuser",
            newId = ids(),
        )
        assertEquals("configured", plan.hosts.single().username)
    }

    @Test
    fun `username is empty when neither config nor default supplies one`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        assertEquals("", plan.hosts.single().username)
    }

    @Test
    fun `only selected aliases are imported`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a"), host("b"), host("c")),
            selected = setOf("a", "c"),
            defaultUser = null,
            newId = ids(),
        )
        assertEquals(listOf("a", "c"), plan.hosts.map { it.label })
    }

    @Test
    fun `proxyJump resolves to the jump host id within the batch`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", proxyJump = "bastion"), host("bastion")),
            selected = setOf("web", "bastion"),
            defaultUser = null,
            newId = ids(),
        )
        val web = plan.hosts.single { it.label == "web" }
        val bastion = plan.hosts.single { it.label == "bastion" }
        assertEquals(bastion.id, web.jumpHostId)
    }

    @Test
    fun `proxyJump to a host that was not selected leaves no jump`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", proxyJump = "bastion"), host("bastion")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        assertNull(plan.hosts.single().jumpHostId)
    }

    @Test
    fun `every imported host gets a distinct id`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a"), host("b"), host("c")),
            selected = setOf("a", "b", "c"),
            defaultUser = null,
            newId = ids(),
        )
        assertEquals(3, plan.hosts.map { it.id }.toSet().size)
        assertTrue(plan.hosts.all { it.id.isNotBlank() })
    }

    @Test
    fun `proxyJump referencing its own alias does not create a self jump`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", proxyJump = "web")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        assertNull(plan.hosts.single().jumpHostId)
    }

    @Test
    fun `empty selection imports nothing`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a")),
            selected = emptySet(),
            defaultUser = null,
            newId = ids(),
        )
        assertTrue(plan.hosts.isEmpty())
    }

    @Test
    fun `IdentityFile becomes a file-backed credential bound to the host`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", identityFile = "~/.ssh/id_ed25519")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )

        val credential = plan.credentials.single()
        assertEquals(CredentialSecret.KeyFile("~/.ssh/id_ed25519", null), credential.secret)
        assertEquals("id_ed25519", credential.label)
        assertEquals(credential.id, plan.hosts.single().credentialId)
    }

    @Test
    fun `CertificateFile is carried into the credential`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", identityFile = "~/.ssh/id_ed25519", certificateFile = "~/.ssh/work-cert.pub")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )

        assertEquals(
            CredentialSecret.KeyFile("~/.ssh/id_ed25519", "~/.ssh/work-cert.pub"),
            plan.credentials.single().secret,
        )
    }

    @Test
    fun `hosts sharing an identity file share one credential`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", identityFile = "~/.ssh/id_ed25519"), host("db", identityFile = "~/.ssh/id_ed25519")),
            selected = setOf("web", "db"),
            defaultUser = null,
            newId = ids(),
        )

        assertEquals(1, plan.credentials.size)
        assertEquals(plan.hosts[0].credentialId, plan.hosts[1].credentialId)
    }

    @Test
    fun `same file name from different directories gets a distinct label`() {
        // Labels are how snippets reference a secret (${{vault:label}}), so two "id_ed25519" entries
        // pointing at different files must not collide.
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a", identityFile = "~/.ssh/id_ed25519"), host("b", identityFile = "/work/keys/id_ed25519")),
            selected = setOf("a", "b"),
            defaultUser = null,
            newId = ids(),
        )

        assertEquals(2, plan.credentials.size)
        assertEquals(2, plan.credentials.map { it.label }.toSet().size)
    }

    @Test
    fun `a label already used in the vault is not reused`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", identityFile = "~/.ssh/id_ed25519")),
            selected = setOf("web"),
            defaultUser = null,
            existingLabels = setOf("id_ed25519"),
            newId = ids(),
        )

        assertTrue(plan.credentials.single().label != "id_ed25519", plan.credentials.single().label)
    }

    @Test
    fun `CertificateFile without an IdentityFile creates nothing`() {
        // Without a key there is nothing to authenticate with; inventing a credential would only
        // produce a profile that fails at connect time.
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", certificateFile = "~/.ssh/work-cert.pub")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )

        assertTrue(plan.credentials.isEmpty())
        assertNull(plan.hosts.single().credentialId)
    }

    @Test
    fun `identity files of unselected hosts are ignored`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", identityFile = "~/.ssh/a"), host("db", identityFile = "~/.ssh/b")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )

        assertEquals(listOf("a"), plan.credentials.map { it.label })
    }
}
