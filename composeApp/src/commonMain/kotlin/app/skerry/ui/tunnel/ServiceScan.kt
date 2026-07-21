package app.skerry.ui.tunnel

import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection

// Discovery of listening TCP services on a host, and the tunnel draft that forwards one of them.
// Pure, kept out of Compose for UI-free testability (same split as TunnelForm).

/**
 * One TCP port the remote host listens on. [bindAddress] is the listen address as reported by the
 * host (a wildcard, loopback, or one specific interface) — it decides which destination the forward
 * has to aim at; [process] is the owning program when the host disclosed it (needs privileges).
 */
data class ListeningService(val port: Int, val bindAddress: String, val process: String?)

/** Process names come from an untrusted host: capped and stripped before they reach the UI. */
const val SERVICE_NAME_MAX_LEN = 40

/** Upper bound on reported services, so a hostile or huge answer can't flood the list. */
const val SERVICE_SCAN_MAX_ROWS = 200

/**
 * One command, one round-trip. `ss` is the modern tool and the only one that reports process names
 * without privileges; `netstat` is the fallback for hosts that lack iproute2, and its `-p` form is
 * tried first for the same reason. Both print the listen socket in the same shape, so
 * [parseListeningServices] reads either.
 *
 * `-W` comes before plain `-p` because net-tools truncates the address column to a fixed width by
 * default, which can cut into the port digits of a long IPv6 address and yield a wrong-but-valid
 * port; older builds without the flag simply fall through to the next branch. A host where every
 * branch fails (no iproute2, and a BSD `netstat` that has no `-l`) exits non-zero with no output —
 * [ServiceScanController] reports that as unsupported rather than "nothing is listening".
 */
const val SERVICE_SCAN_COMMAND: String =
    "ss -ltnp 2>/dev/null || netstat -ltnpW 2>/dev/null || netstat -ltnp 2>/dev/null || " +
        "netstat -ltn 2>/dev/null"

private val WHITESPACE = Regex("\\s+")

// ss: users:(("nginx",pid=901,fd=6),…) — the first name is enough, the rest are worker clones.
private val SS_PROCESS = Regex("users:\\(\\(\"([^\"]*)\"")

// netstat -p: the PID/Program name column, e.g. 733/redis-server.
private val NETSTAT_PROCESS = Regex("^\\d+/(\\S+)$")

// Listen addresses only: IPv4/IPv6 literals and `*`. Anything else in that column is malformed (or
// hostile) output and the row is skipped rather than guessed at.
private val ADDRESS_CHARS = Regex("^[0-9A-Fa-f.:*-]+$")

// A zone id is an interface name (fe80::1%eth0), so it carries letters outside the hex range the
// address itself is limited to.
private val ZONE_ID = Regex("^[0-9A-Za-z._-]+$")

private const val IPV4_LOOPBACK = "127.0.0.1"
private const val IPV6_LOOPBACK = "::1"

/**
 * Parses `ss -ltn`/`netstat -ltn` output into the ports the host listens on, lowest first. Header
 * and noise lines are skipped; a port listening on several addresses collapses into one row keeping
 * the most reachable address (wildcard over loopback over a single interface), since that is what
 * decides whether a forward can reach it.
 */
fun parseListeningServices(raw: String): List<ListeningService> {
    val byPort = LinkedHashMap<Int, ListeningService>()
    for (line in raw.lineSequence()) {
        val tokens = line.trim().split(WHITESPACE)
        // The listen socket is the first token shaped like address:port — its column index differs
        // between ss and netstat, and shifts again with -p.
        val socket = tokens.firstNotNullOfOrNull(::parseSocket) ?: continue
        val candidate = ListeningService(socket.second, socket.first, parseProcessName(line, tokens))
        byPort[socket.second] = merge(byPort[socket.second], candidate)
    }
    return byPort.values.sortedBy { it.port }.take(SERVICE_SCAN_MAX_ROWS)
}

/** Splits `addr:port` (including `[::1]:6379` and netstat's `:::80`), or `null` if it isn't one. */
private fun parseSocket(token: String): Pair<String, Int>? {
    val colon = token.lastIndexOf(':')
    if (colon <= 0) return null
    val port = token.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val address = token.substring(0, colon).removePrefix("[").removeSuffix("]")
    if (!isPlausibleAddress(address)) return null
    return address to port
}

/** A listen address, optionally scoped by an interface (`fe80::1%eth0`). */
private fun isPlausibleAddress(address: String): Boolean {
    val percent = address.indexOf('%')
    if (percent < 0) return ADDRESS_CHARS.matches(address)
    return ADDRESS_CHARS.matches(address.take(percent)) && ZONE_ID.matches(address.substring(percent + 1))
}

private fun parseProcessName(line: String, tokens: List<String>): String? {
    val raw = SS_PROCESS.find(line)?.groupValues?.get(1)
        ?: tokens.firstNotNullOfOrNull { NETSTAT_PROCESS.find(it)?.groupValues?.get(1) }
        ?: return null
    // Stricter than the host monitor's sanitizer, which only drops C0/DEL: this name is not just
    // drawn, it becomes the saved tunnel's label and syncs to the rest of the vault. Format
    // characters (bidi overrides, zero-width) would let a host spoof how that label reads.
    return raw
        .filter { it.code >= 0x20 && it.code != 0x7F && it.category != CharCategory.FORMAT }
        .take(SERVICE_NAME_MAX_LEN)
        .trim()
        .ifEmpty { null }
}

/** Keeps the more reachable of two rows for the same port, and any process name either one carried. */
private fun merge(existing: ListeningService?, candidate: ListeningService): ListeningService {
    if (existing == null) return candidate
    val candidateWins = reachability(candidate.bindAddress) > reachability(existing.bindAddress)
    val winner = if (candidateWins) candidate else existing
    val loser = if (candidateWins) existing else candidate
    // The winner's own name first: two different programs can hold the same port on different
    // interfaces, and the row is about the address that won, not the one listed first.
    return winner.copy(process = winner.process ?: loser.process)
}

private fun reachability(address: String): Int = when {
    isWildcard(address) -> 2
    isLoopback(address) -> 1
    else -> 0
}

private fun isWildcard(address: String): Boolean =
    address.isEmpty() || address == "0.0.0.0" || address == "::" || address == "*"

private fun isLoopback(address: String): Boolean =
    address == IPV6_LOOPBACK || address.startsWith("127.")

/**
 * Ports below this need privileges to bind on Unix, so mirroring the remote port locally would fail
 * for the ones discovery surfaces most often (22, 80, 443).
 */
private const val FIRST_UNPRIVILEGED_PORT = 1024

/**
 * Builds the local forward for a discovered service: same port locally, aimed at the host's own
 * loopback — that is where a wildcard listener answers from the server's side. A service bound to
 * one specific interface is aimed at that address instead, since loopback would not reach it.
 * A privileged remote port binds to `0` (assigned by the OS) rather than failing on a port the app
 * may not claim; the actual port shows up in the row once the forward is up.
 * [fallbackLabel] names the tunnel when the host didn't disclose the process (localized by the caller).
 */
fun serviceTunnelDraft(service: ListeningService, hostId: String, fallbackLabel: String): TunnelDraft = TunnelDraft(
    label = service.process ?: fallbackLabel,
    hostId = hostId,
    direction = TunnelDirection.Local,
    bindHost = IPV4_LOOPBACK,
    bindPort = if (service.port < FIRST_UNPRIVILEGED_PORT) 0 else service.port,
    destHost = destinationFor(service.bindAddress),
    destPort = service.port,
)

private fun destinationFor(bindAddress: String): String = when {
    bindAddress == "::" -> IPV6_LOOPBACK
    isWildcard(bindAddress) -> IPV4_LOOPBACK
    else -> bindAddress
}

/**
 * Ports of [hostId] that a saved local forward already covers, so discovery can mark them instead
 * of offering a duplicate. Only `-L` counts: `-R` runs the other way and `-D` has no destination.
 */
fun forwardedPorts(tunnels: List<Tunnel>, hostId: String): Set<Int> = tunnels
    .filter { it.hostId == hostId && it.direction == TunnelDirection.Local }
    .mapNotNullTo(mutableSetOf()) { it.destPort }
