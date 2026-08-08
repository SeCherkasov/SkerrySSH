package app.skerry.ui.ai

import androidx.compose.runtime.Composable
import app.skerry.shared.ai.AiPolicy
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_policy_balanced_desc
import app.skerry.ui.generated.resources.conn_policy_balanced_short
import app.skerry.ui.generated.resources.conn_policy_balanced_title
import app.skerry.ui.generated.resources.conn_policy_off_desc
import app.skerry.ui.generated.resources.conn_policy_off_short
import app.skerry.ui.generated.resources.conn_policy_off_title
import app.skerry.ui.generated.resources.conn_policy_permissive_desc
import app.skerry.ui.generated.resources.conn_policy_permissive_short
import app.skerry.ui.generated.resources.conn_policy_permissive_title
import app.skerry.ui.generated.resources.conn_policy_strict_desc
import app.skerry.ui.generated.resources.conn_policy_strict_short
import app.skerry.ui.generated.resources.conn_policy_strict_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A per-host AI policy option for pickers (connection form, settings). [title]/[desc]/[shortLabel]
 * are localized string resources, resolved with `stringResource` at the call site, so pickers follow
 * the selected UI language ([app.skerry.ui.i18n.UiLanguage]).
 */
data class PolicyOption(
    val policy: AiPolicy,
    val icon: String,
    val title: StringResource,
    val desc: StringResource,
    val shortLabel: StringResource,
)

/**
 * Whether an AI provider endpoint uses plain http:// (excluding the loopback host) — the key and
 * prompt (with secrets, under Permissive) would travel in plaintext, and the settings screen has a
 * button that sends the key to this address on one click.
 *
 * Scheme and host are compared as parsed values, not as prefixes: `HTTP://` is the same scheme to
 * every client, and `localhost.attacker.com` is not the loopback host — either would otherwise slip
 * past the warning while the request still goes out in the clear.
 */
fun isInsecureAiEndpoint(url: String): Boolean {
    val u = url.trim()
    if (u.startsWith("//")) return true // protocol-relative: a client defaults it to http
    if (u.isEmpty() || !u.contains(':')) return false // nothing typed yet, or still being typed
    val scheme = u.substringBefore("://", missingDelimiterValue = "").lowercase()
    // Anything that is not confidently TLS is treated as cleartext, including a scheme-less or
    // protocol-relative address: a client defaults those to http and dials them anyway, so
    // answering "safe" for what could not be parsed would hide exactly the case worth warning about.
    if (scheme == "https") return false
    if (scheme != "http") return true
    return hostOf(u.removePrefix("$scheme://")).lowercase() !in LOOPBACK_HOSTS
}

/**
 * Host of an `authority[/path][?query][#fragment]` string. All four delimiters have to be cut, not
 * just the path: `evil.com#@localhost` would otherwise have its userinfo stripped down to
 * "localhost" and pass for the loopback host while the request goes to evil.com.
 */
private fun hostOf(authority: String): String {
    val host = authority.takeWhile { it !in "/?#\\" }.substringAfterLast('@') // drop user:password@
    val portAt = host.lastIndexOf(':')
    // A bracketed IPv6 literal is full of colons; only one after the closing bracket is a port.
    val hasPort = portAt > host.lastIndexOf(']')
    return if (hasPort) host.take(portAt) else host
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

val POLICY_OPTIONS = listOf(
    PolicyOption(AiPolicy.Strict, "shield_lock", Res.string.conn_policy_strict_title, Res.string.conn_policy_strict_desc, Res.string.conn_policy_strict_short),
    PolicyOption(AiPolicy.Balanced, "tune", Res.string.conn_policy_balanced_title, Res.string.conn_policy_balanced_desc, Res.string.conn_policy_balanced_short),
    PolicyOption(AiPolicy.Permissive, "science", Res.string.conn_policy_permissive_title, Res.string.conn_policy_permissive_desc, Res.string.conn_policy_permissive_short),
    PolicyOption(AiPolicy.Off, "block", Res.string.conn_policy_off_title, Res.string.conn_policy_off_desc, Res.string.conn_policy_off_short),
)

/** Localized short label for [AiPolicy] (mobile policy pills). */
@Composable
fun AiPolicy.shortLabel(): String {
    val policy = this
    return stringResource(POLICY_OPTIONS.first { it.policy == policy }.shortLabel)
}
