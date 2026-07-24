package app.skerry.shared.ssh

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
    ) = SshConfigHost(alias, hostName, port, user, proxyJump, identityFile = null)

    @Test
    fun `maps parsed fields onto a host profile`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", hostName = "10.0.0.1", port = 2222, user = "deploy")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        val h = plan.single()
        assertEquals("web", h.label)
        assertEquals("10.0.0.1", h.address)
        assertEquals(2222, h.port)
        assertEquals("deploy", h.username)
        assertEquals(ConnectionType.SSH, h.connectionType)
        assertNull(h.credentialId)
        assertNull(h.jumpHostId)
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
        assertEquals("localuser", plan.single().username)
    }

    @Test
    fun `config User wins over the default user`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", user = "configured")),
            selected = setOf("web"),
            defaultUser = "localuser",
            newId = ids(),
        )
        assertEquals("configured", plan.single().username)
    }

    @Test
    fun `username is empty when neither config nor default supplies one`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        assertEquals("", plan.single().username)
    }

    @Test
    fun `only selected aliases are imported`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a"), host("b"), host("c")),
            selected = setOf("a", "c"),
            defaultUser = null,
            newId = ids(),
        )
        assertEquals(listOf("a", "c"), plan.map { it.label })
    }

    @Test
    fun `proxyJump resolves to the jump host id within the batch`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", proxyJump = "bastion"), host("bastion")),
            selected = setOf("web", "bastion"),
            defaultUser = null,
            newId = ids(),
        )
        val web = plan.single { it.label == "web" }
        val bastion = plan.single { it.label == "bastion" }
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
        assertNull(plan.single().jumpHostId)
    }

    @Test
    fun `every imported host gets a distinct id`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a"), host("b"), host("c")),
            selected = setOf("a", "b", "c"),
            defaultUser = null,
            newId = ids(),
        )
        assertEquals(3, plan.map { it.id }.toSet().size)
        assertTrue(plan.all { it.id.isNotBlank() })
    }

    @Test
    fun `proxyJump referencing its own alias does not create a self jump`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("web", proxyJump = "web")),
            selected = setOf("web"),
            defaultUser = null,
            newId = ids(),
        )
        assertNull(plan.single().jumpHostId)
    }

    @Test
    fun `empty selection imports nothing`() {
        val plan = SshConfigImport.plan(
            hosts = listOf(host("a")),
            selected = emptySet(),
            defaultUser = null,
            newId = ids(),
        )
        assertTrue(plan.isEmpty())
    }
}
