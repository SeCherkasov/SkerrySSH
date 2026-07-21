package app.skerry.ui.tunnel

import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.ui.terminal.isSafeLinkUri

// Browser link for a live forward. Pure, kept out of Compose for UI-free testability.

// Ports where a plain http:// link would be answered with a TLS handshake. Deliberately a short
// list of the conventional ones — guessing wider would send the browser to a broken scheme more
// often than it would help.
private val TLS_PORTS = setOf(443, 8443)

// Hostnames and IPv4 literals; an IPv6 literal takes the bracket path instead. bindHost is free-form
// user input, so anything outside this gets no link rather than a mangled URL.
private val PLAIN_HOST = Regex("^[A-Za-z0-9.-]+$")
private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:]+$")

/**
 * The URL that opens an active local forward in a browser, or `null` when there's nothing to open:
 * `-R` listens on the server, `-D` is a SOCKS proxy, and an inactive tunnel has no bound port.
 */
fun tunnelBrowserUrl(entry: TunnelEntry): String? {
    val active = entry.status as? TunnelStatus.Active ?: return null
    return localForwardUrl(entry.tunnel.direction, entry.tunnel.bindHost, active.boundPort)
}

/**
 * URL for a local forward listening on [bindHost]:[port], or `null` when it isn't browsable. A
 * wildcard listen address is browsed through loopback — `0.0.0.0` is where the listener binds, not
 * an address a browser can connect to. The result passes the same gate as terminal hyperlinks.
 */
fun localForwardUrl(direction: TunnelDirection, bindHost: String, port: Int): String? {
    if (direction != TunnelDirection.Local) return null
    if (port !in 1..65535) return null
    val host = browsableHost(bindHost) ?: return null
    val scheme = if (port in TLS_PORTS) "https" else "http"
    return "$scheme://$host:$port".takeIf { isSafeLinkUri(it) }
}

private fun browsableHost(bindHost: String): String? {
    val host = bindHost.trim().removePrefix("[").removeSuffix("]")
    return when {
        host.isEmpty() || host == "0.0.0.0" || host == "*" -> "127.0.0.1"
        host == "::" -> "[::1]"
        // A zone id (fe80::1%eth0) would need percent-encoding to survive a URL — not worth it for
        // a loopback listener, so it gets no link.
        ':' in host -> if (IPV6_LITERAL.matches(host)) "[$host]" else null
        PLAIN_HOST.matches(host) -> host
        else -> null
    }
}
