package app.skerry.server.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdminCliParserTest {

    private fun parse(vararg args: String, env: Map<String, String> = emptyMap()) =
        parseCli(args.toList(), env)

    @Test
    fun `no arguments prints usage`() {
        assertIs<ParsedCli.Help>(parse())
    }

    @Test
    fun `help flag prints usage`() {
        assertIs<ParsedCli.Help>(parse("--help"))
        assertIs<ParsedCli.Help>(parse("devices", "--help"))
    }

    @Test
    fun `stats uses loopback default and token from environment`() {
        val run = assertIs<ParsedCli.Run>(parse("stats", env = mapOf("SKERRY_ADMIN_TOKEN" to "t0k")))
        assertEquals(AdminCommand.Stats, run.command)
        assertEquals("http://127.0.0.1:8080", run.options.baseUrl)
        assertEquals("t0k", run.options.token)
    }

    /** The CLI runs inside the container by default, where SKERRY_PORT is what the server listens on. */
    @Test
    fun `default base url follows SKERRY_PORT`() {
        val run = assertIs<ParsedCli.Run>(parse("stats", env = mapOf("SKERRY_PORT" to "9090")))
        assertEquals("http://127.0.0.1:9090", run.options.baseUrl)
    }

    @Test
    fun `explicit url wins over environment and loses its trailing slash`() {
        val env = mapOf("SKERRY_ADMIN_URL" to "http://ignored:1/", "SKERRY_PORT" to "9090")
        val run = assertIs<ParsedCli.Run>(parse("--url", "https://sync.example.com/", "stats", env = env))
        assertEquals("https://sync.example.com", run.options.baseUrl)
    }

    @Test
    fun `admin url environment variable is honoured`() {
        val run = assertIs<ParsedCli.Run>(parse("stats", env = mapOf("SKERRY_ADMIN_URL" to "http://sync.internal:8080")))
        assertEquals("http://sync.internal:8080", run.options.baseUrl)
    }

    @Test
    fun `token flag wins over environment`() {
        val run = assertIs<ParsedCli.Run>(
            parse("--token", "flag", "stats", env = mapOf("SKERRY_ADMIN_TOKEN" to "env")),
        )
        assertEquals("flag", run.options.token)
    }

    @Test
    fun `token file is carried through for the runner to read`() {
        val run = assertIs<ParsedCli.Run>(parse("--token-file", "/run/secrets/admin", "stats"))
        assertEquals("/run/secrets/admin", run.options.tokenFile)
        assertEquals(null, run.options.token)
    }

    @Test
    fun `flags may follow the command`() {
        val run = assertIs<ParsedCli.Run>(parse("accounts", "list", "--limit", "5", "--json"))
        assertEquals(AdminCommand.AccountsList(limit = 5), run.command)
        assertTrue(run.options.json)
    }

    @Test
    fun `devices list accepts an account filter`() {
        val run = assertIs<ParsedCli.Run>(parse("devices", "list", "--account", "alice@example.com"))
        assertEquals(AdminCommand.DevicesList(limit = null, accountId = "alice@example.com"), run.command)
    }

    @Test
    fun `device revoke needs both ids`() {
        val run = assertIs<ParsedCli.Run>(
            parse("devices", "revoke", "dev-1", "--account", "alice@example.com"),
        )
        assertEquals(AdminCommand.DeviceRevoke("alice@example.com", "dev-1"), run.command)

        val missing = assertIs<ParsedCli.Invalid>(parse("devices", "revoke", "dev-1"))
        assertTrue("--account" in missing.message, missing.message)
    }

    @Test
    fun `account records and purge take the account as a positional`() {
        val records = assertIs<ParsedCli.Run>(parse("accounts", "records", "alice@example.com"))
        assertEquals(AdminCommand.AccountRecords("alice@example.com", limit = null), records.command)

        val purge = assertIs<ParsedCli.Run>(parse("accounts", "purge-tombstones", "alice@example.com"))
        assertEquals(AdminCommand.AccountPurgeTombstones("alice@example.com"), purge.command)
    }

    /**
     * Deleting an account is irreversible and there is no TTY inside `docker exec` half the time,
     * so confirmation is an explicit flag rather than an interactive prompt.
     */
    @Test
    fun `account delete demands an explicit yes`() {
        val invalid = assertIs<ParsedCli.Invalid>(parse("accounts", "delete", "alice@example.com"))
        assertTrue("--yes" in invalid.message, invalid.message)

        val run = assertIs<ParsedCli.Run>(parse("accounts", "delete", "alice@example.com", "--yes"))
        assertEquals(AdminCommand.AccountDelete("alice@example.com"), run.command)
    }

    /** /metrics is gated by its own bearer token, not the admin token (different privileges). */
    @Test
    fun `metrics reads its own token`() {
        val fromEnv = assertIs<ParsedCli.Run>(
            parse("metrics", env = mapOf("SKERRY_METRICS_TOKEN" to "scrape", "SKERRY_ADMIN_TOKEN" to "admin")),
        )
        assertEquals("scrape", fromEnv.options.metricsToken)
        assertEquals("admin", fromEnv.options.token)

        val fromFlag = assertIs<ParsedCli.Run>(parse("metrics", "--metrics-token", "flag"))
        assertEquals("flag", fromFlag.options.metricsToken)

        // The flag belongs to `metrics` only, so a typo elsewhere is reported rather than ignored.
        assertIs<ParsedCli.Invalid>(parse("stats", "--metrics-token", "x"))
    }

    @Test
    fun `health and metrics are single-word commands`() {
        assertEquals(AdminCommand.Health, assertIs<ParsedCli.Run>(parse("health")).command)
        assertEquals(AdminCommand.Metrics, assertIs<ParsedCli.Run>(parse("metrics")).command)
    }

    @Test
    fun `activity takes a limit`() {
        assertEquals(
            AdminCommand.Activity(limit = 10),
            assertIs<ParsedCli.Run>(parse("activity", "--limit", "10")).command,
        )
    }

    @Test
    fun `unknown command and unknown subcommand are rejected`() {
        assertIs<ParsedCli.Invalid>(parse("frobnicate"))
        assertIs<ParsedCli.Invalid>(parse("devices", "frobnicate"))
        assertIs<ParsedCli.Invalid>(parse("accounts"))
    }

    @Test
    fun `unknown flag is rejected instead of silently ignored`() {
        val invalid = assertIs<ParsedCli.Invalid>(parse("stats", "--verbose"))
        assertTrue("--verbose" in invalid.message, invalid.message)
    }

    @Test
    fun `limit must be a positive number`() {
        assertIs<ParsedCli.Invalid>(parse("accounts", "list", "--limit", "0"))
        assertIs<ParsedCli.Invalid>(parse("accounts", "list", "--limit", "-3"))
        assertIs<ParsedCli.Invalid>(parse("accounts", "list", "--limit", "many"))
        assertIs<ParsedCli.Invalid>(parse("accounts", "list", "--limit"))
    }

    @Test
    fun `inline flag values are accepted`() {
        val run = assertIs<ParsedCli.Run>(parse("accounts", "list", "--limit=5", "--url=http://h:1"))
        assertEquals(AdminCommand.AccountsList(limit = 5), run.command)
        assertEquals("http://h:1", run.options.baseUrl)
    }

    /** `--account=` is a typo; silently reading it as "no filter" would widen the listing instead. */
    @Test
    fun `an empty inline value is rejected`() {
        assertIs<ParsedCli.Invalid>(parse("devices", "list", "--account="))
        assertIs<ParsedCli.Invalid>(parse("accounts", "list", "--limit="))
        assertIs<ParsedCli.Invalid>(parse("stats", "--token="))
    }

    @Test
    fun `a flag the command ignores is an error`() {
        val invalid = assertIs<ParsedCli.Invalid>(parse("stats", "--limit", "5"))
        assertTrue("--limit" in invalid.message && "stats" in invalid.message, invalid.message)
        assertIs<ParsedCli.Invalid>(parse("devices", "list", "--yes"))
    }

    @Test
    fun `extra positional arguments are rejected`() {
        assertIs<ParsedCli.Invalid>(parse("stats", "extra"))
        assertIs<ParsedCli.Invalid>(parse("accounts", "records", "a@b", "c@d"))
    }
}
