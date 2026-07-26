package app.skerry.server.cli

/**
 * Argument parsing for `skerry-admin` — pure, so the command surface is testable without a server.
 * The CLI talks to the same `/admin` HTTP API as the web console (one authorization path, one
 * implementation of every operation), so it works both inside the container and against a remote
 * instance.
 */

/** Global options: where to talk and how to print. */
data class CliOptions(
    val baseUrl: String,
    /** Token from `--token` or `SKERRY_ADMIN_TOKEN`; null means "read [tokenFile]". */
    val token: String?,
    /** Path from `--token-file` (docker/k8s secret); read by the runner, not the parser. */
    val tokenFile: String?,
    /**
     * Bearer token for `/metrics` — a different credential from [token] on purpose: the admin token
     * also authorizes account deletion, so a scrape check must not require it.
     */
    val metricsToken: String?,
    val json: Boolean,
)

sealed interface AdminCommand {
    data object Health : AdminCommand
    data object Stats : AdminCommand
    data object Metrics : AdminCommand
    data class Activity(val limit: Int?) : AdminCommand
    data class AccountsList(val limit: Int?) : AdminCommand
    data class AccountRecords(val accountId: String, val limit: Int?) : AdminCommand
    data class AccountPurgeTombstones(val accountId: String) : AdminCommand
    data class AccountDelete(val accountId: String) : AdminCommand
    data class DevicesList(val limit: Int?, val accountId: String?) : AdminCommand
    data class DeviceRevoke(val accountId: String, val deviceId: String) : AdminCommand
}

sealed interface ParsedCli {
    data class Run(val command: AdminCommand, val options: CliOptions) : ParsedCli
    data class Help(val text: String) : ParsedCli
    data class Invalid(val message: String) : ParsedCli
}

private const val URL = "--url"
private const val TOKEN = "--token"
private const val TOKEN_FILE = "--token-file"
private const val LIMIT = "--limit"
private const val ACCOUNT = "--account"
private const val METRICS_TOKEN = "--metrics-token"
private const val JSON = "--json"
private const val YES = "--yes"

private val VALUE_FLAGS = setOf(URL, TOKEN, TOKEN_FILE, LIMIT, ACCOUNT, METRICS_TOKEN)
private val BOOL_FLAGS = setOf(JSON, YES)
private val GLOBAL_FLAGS = setOf(URL, TOKEN, TOKEN_FILE, JSON)

fun parseCli(args: List<String>, env: Map<String, String> = System.getenv()): ParsedCli {
    if (args.isEmpty()) return ParsedCli.Help(USAGE)

    val values = LinkedHashMap<String, String>()
    val bools = LinkedHashSet<String>()
    val positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        // `--flag=value` and `--flag value` are both expected of a CLI; normalize to one form.
        val eq = if (arg.startsWith("--")) arg.indexOf('=') else -1
        val name = if (eq > 0) arg.take(eq) else arg
        val inlineValue = if (eq > 0) arg.drop(eq + 1) else null
        when {
            name == "--help" || name == "-h" -> return ParsedCli.Help(USAGE)
            name in VALUE_FLAGS -> {
                val value = inlineValue ?: args.getOrNull(i + 1)
                // `--account=` is a typo, not "no filter": treating it as absent would silently widen
                // `devices list` to every account, while `devices revoke` rejects the same input.
                if (value.isNullOrBlank() || (inlineValue == null && value.startsWith("--"))) {
                    return ParsedCli.Invalid("$name requires a value")
                }
                if (values.put(name, value) != null) return ParsedCli.Invalid("$name given twice")
                i += if (inlineValue == null) 2 else 1
            }
            name in BOOL_FLAGS -> {
                if (inlineValue != null) return ParsedCli.Invalid("$name takes no value")
                bools += name
                i++
            }
            arg.startsWith("-") && arg != "-" -> return ParsedCli.Invalid("unknown flag $arg")
            else -> {
                positional += arg
                i++
            }
        }
    }

    val limit = values[LIMIT]?.let { raw ->
        raw.toIntOrNull()?.takeIf { it > 0 } ?: return ParsedCli.Invalid("$LIMIT must be a positive number, got \"$raw\"")
    }
    val account = values[ACCOUNT]
    val yes = YES in bools

    val command = when (val head = positional.firstOrNull()) {
        null -> return ParsedCli.Help(USAGE)
        "health" -> AdminCommand.Health.requiring(positional, 1) ?: return arityError(head)
        "stats" -> AdminCommand.Stats.requiring(positional, 1) ?: return arityError(head)
        "metrics" -> AdminCommand.Metrics.requiring(positional, 1) ?: return arityError(head)
        "activity" -> if (positional.size == 1) AdminCommand.Activity(limit) else return arityError(head)
        "accounts" -> when (val sub = positional.getOrNull(1)) {
            null -> return ParsedCli.Invalid("accounts needs a subcommand: list, records, purge-tombstones, delete")
            "list" -> if (positional.size == 2) AdminCommand.AccountsList(limit) else return arityError("accounts list")
            "records" -> {
                val id = positional.accountArg(2) ?: return ParsedCli.Invalid("accounts records needs an account id")
                if (positional.size > 3) return arityError("accounts records")
                AdminCommand.AccountRecords(id, limit)
            }
            "purge-tombstones" -> {
                val id = positional.accountArg(2)
                    ?: return ParsedCli.Invalid("accounts purge-tombstones needs an account id")
                if (positional.size > 3) return arityError("accounts purge-tombstones")
                AdminCommand.AccountPurgeTombstones(id)
            }
            "delete" -> {
                val id = positional.accountArg(2) ?: return ParsedCli.Invalid("accounts delete needs an account id")
                if (positional.size > 3) return arityError("accounts delete")
                // Irreversible, and `docker exec` often has no TTY — confirmation is a flag, not a prompt.
                if (!yes) {
                    return ParsedCli.Invalid("accounts delete removes $id and all its data — pass $YES to confirm")
                }
                AdminCommand.AccountDelete(id)
            }
            else -> return ParsedCli.Invalid("unknown subcommand: accounts $sub")
        }
        "devices" -> when (val sub = positional.getOrNull(1)) {
            null -> return ParsedCli.Invalid("devices needs a subcommand: list, revoke")
            "list" -> if (positional.size == 2) AdminCommand.DevicesList(limit, account) else return arityError("devices list")
            "revoke" -> {
                val deviceId = positional.getOrNull(2)?.takeIf { it.isNotBlank() }
                    ?: return ParsedCli.Invalid("devices revoke needs a device id")
                if (positional.size > 3) return arityError("devices revoke")
                // deviceId is unique only within an account (composite PK), so the account is required.
                val acct = account?.takeIf { it.isNotBlank() }
                    ?: return ParsedCli.Invalid("devices revoke needs $ACCOUNT <accountId>")
                AdminCommand.DeviceRevoke(acct, deviceId)
            }
            else -> return ParsedCli.Invalid("unknown subcommand: devices $sub")
        }
        else -> return ParsedCli.Invalid("unknown command: $head")
    }

    // A flag the command ignores is a typo or a wrong expectation, not something to swallow silently.
    val allowed = GLOBAL_FLAGS + command.acceptedFlags()
    (values.keys + bools).firstOrNull { it !in allowed }?.let {
        return ParsedCli.Invalid("$it is not valid for ${command.label()}")
    }

    return ParsedCli.Run(
        command,
        CliOptions(
            baseUrl = resolveBaseUrl(values[URL], env),
            token = values[TOKEN] ?: env["SKERRY_ADMIN_TOKEN"]?.takeIf { it.isNotBlank() },
            tokenFile = values[TOKEN_FILE] ?: env["SKERRY_ADMIN_TOKEN_FILE"]?.takeIf { it.isNotBlank() },
            metricsToken = values[METRICS_TOKEN] ?: env["SKERRY_METRICS_TOKEN"]?.takeIf { it.isNotBlank() },
            json = JSON in bools,
        ),
    )
}

/** `--url`, else `SKERRY_ADMIN_URL`, else loopback on the port the server itself listens on. */
private fun resolveBaseUrl(flag: String?, env: Map<String, String>): String {
    val raw = flag ?: env["SKERRY_ADMIN_URL"]?.takeIf { it.isNotBlank() }
    if (raw != null) return raw.trimEnd('/')
    val port = env["SKERRY_PORT"]?.toIntOrNull() ?: 8080
    return "http://127.0.0.1:$port"
}

private fun List<String>.accountArg(index: Int): String? = getOrNull(index)?.takeIf { it.isNotBlank() }

private fun <T : AdminCommand> T.requiring(positional: List<String>, size: Int): T? =
    takeIf { positional.size == size }

private fun arityError(command: String) = ParsedCli.Invalid("$command takes no extra arguments")

private fun AdminCommand.acceptedFlags(): Set<String> = when (this) {
    is AdminCommand.Activity, is AdminCommand.AccountsList, is AdminCommand.AccountRecords -> setOf(LIMIT)
    is AdminCommand.DevicesList -> setOf(LIMIT, ACCOUNT)
    is AdminCommand.DeviceRevoke -> setOf(ACCOUNT)
    is AdminCommand.AccountDelete -> setOf(YES)
    AdminCommand.Metrics -> setOf(METRICS_TOKEN)
    AdminCommand.Health, AdminCommand.Stats, is AdminCommand.AccountPurgeTombstones -> emptySet()
}

private fun AdminCommand.label(): String = when (this) {
    AdminCommand.Health -> "health"
    AdminCommand.Stats -> "stats"
    AdminCommand.Metrics -> "metrics"
    is AdminCommand.Activity -> "activity"
    is AdminCommand.AccountsList -> "accounts list"
    is AdminCommand.AccountRecords -> "accounts records"
    is AdminCommand.AccountPurgeTombstones -> "accounts purge-tombstones"
    is AdminCommand.AccountDelete -> "accounts delete"
    is AdminCommand.DevicesList -> "devices list"
    is AdminCommand.DeviceRevoke -> "devices revoke"
}

val USAGE: String = """
    skerry-admin — administration CLI for the Skerry sync server.

    Usage: skerry-admin [global options] <command> [arguments]

    Commands
      health                              Server liveness and version (no token required)
      stats                               Instance totals: accounts, devices, records, storage
      metrics [--metrics-token T]         Raw Prometheus exposition (as the scraper sees it);
                                          token defaults to SKERRY_METRICS_TOKEN
      activity [--limit N]                Recent audit-log events
      accounts list [--limit N]           Accounts with device/record aggregates
      accounts records <id> [--limit N]   Record envelopes of one account (metadata only)
      accounts purge-tombstones <id>      Drop deletion markers every device has synced past
      accounts delete <id> --yes          Delete an account with all of its data (irreversible)
      devices list [--account id] [--limit N]
                                          Active devices, most recently seen first
      devices revoke <deviceId> --account <accountId>
                                          Revoke a device (it may re-authenticate later)

    Global options
      --url URL          Server base URL (default: SKERRY_ADMIN_URL, else http://127.0.0.1:SKERRY_PORT)
      --token TOKEN      Admin token (default: SKERRY_ADMIN_TOKEN). Visible in `ps` — prefer the env var
      --token-file PATH  Read the admin token from a file (default: SKERRY_ADMIN_TOKEN_FILE)
      --json             Print the server's JSON response instead of a table
      --help             This text

    Exit codes: 0 ok · 1 error · 2 usage · 3 unauthorized · 4 not found · 5 unreachable
""".trimIndent()
