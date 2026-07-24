package app.skerry.shared.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SshConfigParserTest {

    private fun parse(text: String) = SshConfigParser.parse(text)

    @Test
    fun `single host block maps every field`() {
        val result = parse(
            """
            Host web
                HostName 10.0.0.1
                User deploy
                Port 2222
            """.trimIndent(),
        )
        assertEquals(1, result.hosts.size)
        val host = result.hosts.single()
        assertEquals("web", host.alias)
        assertEquals("10.0.0.1", host.hostName)
        assertEquals("deploy", host.user)
        assertEquals(2222, host.port)
        assertEquals(null, host.proxyJump)
        assertEquals(null, host.identityFile)
    }

    @Test
    fun `missing HostName falls back to the alias`() {
        val result = parse("Host myserver\n    User root")
        val host = result.hosts.single()
        assertEquals("myserver", host.alias)
        assertEquals("myserver", host.hostName)
    }

    @Test
    fun `port defaults to 22 and user is null when absent`() {
        val result = parse("Host bare\n    HostName example.com")
        val host = result.hosts.single()
        assertEquals(22, host.port)
        assertEquals(null, host.user)
    }

    @Test
    fun `keywords are case-insensitive`() {
        val result = parse("HOST web\n    hostname 10.0.0.9\n    USER admin\n    PORT 42")
        val host = result.hosts.single()
        assertEquals("10.0.0.9", host.hostName)
        assertEquals("admin", host.user)
        assertEquals(42, host.port)
    }

    @Test
    fun `equals separator is accepted`() {
        val result = parse("Host web\n    HostName=1.2.3.4\n    Port = 2200\n    User=bob")
        val host = result.hosts.single()
        assertEquals("1.2.3.4", host.hostName)
        assertEquals(2200, host.port)
        assertEquals("bob", host.user)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val result = parse(
            """
            # a comment
            Host web

              # indented comment
              HostName 10.0.0.2
            """.trimIndent(),
        )
        val host = result.hosts.single()
        assertEquals("10.0.0.2", host.hostName)
    }

    @Test
    fun `multiple patterns on one Host line produce a host each`() {
        val result = parse("Host web1 web2\n    User deploy")
        assertEquals(listOf("web1", "web2"), result.hosts.map { it.alias })
        assertTrue(result.hosts.all { it.user == "deploy" })
    }

    @Test
    fun `wildcard defaults apply to concrete hosts but do not create a host`() {
        val result = parse(
            """
            Host *
                User defaultuser
                Port 2022

            Host web
                HostName 10.0.0.5
            """.trimIndent(),
        )
        assertEquals(listOf("web"), result.hosts.map { it.alias })
        val host = result.hosts.single()
        assertEquals("defaultuser", host.user)
        assertEquals(2022, host.port)
    }

    @Test
    fun `first match wins - per-host value overrides later wildcard default`() {
        // OpenSSH semantics: the first obtained value is used, regardless of file position.
        val result = parse(
            """
            Host web
                User specific

            Host *
                User fallback
            """.trimIndent(),
        )
        assertEquals("specific", result.hosts.single().user)
    }

    @Test
    fun `question mark wildcard matches a single character`() {
        val result = parse(
            """
            Host db?
                User dba

            Host db1
                HostName 10.0.0.7
            """.trimIndent(),
        )
        val host = result.hosts.single { it.alias == "db1" }
        assertEquals("dba", host.user)
    }

    @Test
    fun `proxyJump keeps only the first hop host token`() {
        val result = parse("Host web\n    ProxyJump jump1,jump2")
        assertEquals("jump1", result.hosts.single().proxyJump)
    }

    @Test
    fun `proxyJump strips user and port`() {
        val result = parse("Host web\n    ProxyJump admin@bastion:2222")
        assertEquals("bastion", result.hosts.single().proxyJump)
    }

    @Test
    fun `proxyJump none means no jump`() {
        val result = parse("Host web\n    ProxyJump none")
        assertEquals(null, result.hosts.single().proxyJump)
    }

    @Test
    fun `identityFile is captured verbatim`() {
        val result = parse("Host web\n    IdentityFile ~/.ssh/id_ed25519")
        assertEquals("~/.ssh/id_ed25519", result.hosts.single().identityFile)
    }

    @Test
    fun `quoted values are unquoted`() {
        val result = parse("Host \"web server\"\n    HostName \"10.0.0.8\"")
        val host = result.hosts.single()
        assertEquals("web server", host.alias)
        assertEquals("10.0.0.8", host.hostName)
    }

    @Test
    fun `negated pattern excludes an alias from a wildcard block`() {
        val result = parse(
            """
            Host * !secret
                User common

            Host secret
                HostName 10.0.0.10

            Host public
                HostName 10.0.0.11
            """.trimIndent(),
        )
        assertEquals(null, result.hosts.single { it.alias == "secret" }.user)
        assertEquals("common", result.hosts.single { it.alias == "public" }.user)
    }

    @Test
    fun `duplicate alias across blocks yields one host`() {
        val result = parse(
            """
            Host web
                HostName 10.0.0.1

            Host web
                User late
            """.trimIndent(),
        )
        assertEquals(1, result.hosts.size)
        val host = result.hosts.single()
        assertEquals("10.0.0.1", host.hostName)
        assertEquals("late", host.user)
    }

    @Test
    fun `Include directive is skipped with a warning`() {
        val result = parse("Include ~/.ssh/other_config\nHost web\n    HostName 10.0.0.1")
        assertEquals(listOf("web"), result.hosts.map { it.alias })
        assertTrue(result.warnings.any { it.contains("Include", ignoreCase = true) })
    }

    @Test
    fun `Match block is ignored with a warning`() {
        val result = parse(
            """
            Host web
                HostName 10.0.0.1

            Match host web
                User matched
            """.trimIndent(),
        )
        // Options under Match must not leak into the host.
        assertEquals(null, result.hosts.single().user)
        assertTrue(result.warnings.any { it.contains("Match", ignoreCase = true) })
    }

    @Test
    fun `empty input yields no hosts`() {
        assertEquals(0, parse("").hosts.size)
        assertEquals(0, parse("   \n# only a comment\n").hosts.size)
    }

    @Test
    fun `invalid port is ignored and falls back to default`() {
        val result = parse("Host web\n    Port notanumber")
        assertEquals(22, result.hosts.single().port)
    }

    @Test
    fun `inline comment on a Host line does not create phantom aliases`() {
        val result = parse("Host web  # production box\n    HostName 10.0.0.1")
        assertEquals(listOf("web"), result.hosts.map { it.alias })
        assertEquals("10.0.0.1", result.hosts.single().hostName)
    }

    @Test
    fun `inline comment after a value is stripped`() {
        val result = parse("Host web\n    HostName 10.0.0.1 # main")
        assertEquals("10.0.0.1", result.hosts.single().hostName)
    }

    @Test
    fun `hash inside a token stays literal`() {
        val result = parse("Host web\n    IdentityFile ~/.ssh/id#1")
        assertEquals("~/.ssh/id#1", result.hosts.single().identityFile)
    }

    @Test
    fun `pathological wildcard pattern resolves without catastrophic backtracking`() {
        // A block whose pattern is packed with `*` matched against a long non-matching alias is the
        // classic ReDoS shape; the linear matcher must return promptly (this test would hang on a
        // backtracking regex). We only assert the parse completes and yields the concrete host.
        val stars = "a*".repeat(60)
        val alias = "b".repeat(120)
        val result = parse("Host $stars\n    User u\n\nHost $alias\n    HostName 10.0.0.1")
        assertEquals("10.0.0.1", result.hosts.single { it.alias == alias }.hostName)
    }
}
