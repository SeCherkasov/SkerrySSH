package app.skerry.shared.jumpshell

/**
 * Where a session goes once it stands at a jump host's prompt: the destination the jump host itself
 * dials with its own `ssh`, under its own keys and config. [username] may be empty — the jump
 * host's `ssh` then picks the user (its config, or the account it runs as).
 */
data class JumpShellSpec(
    val host: String,
    val port: Int = 22,
    val username: String = "",
)

/**
 * The line typed at the jump host's prompt, or `null` when some part of the destination could be
 * read by a shell as more than an address. The line goes into a shell verbatim, and a profile
 * reaches this device through sync or a team space: an address like `db; curl … | sh` must not be
 * something a click on a host row runs. So nothing is quoted — anything that would need quoting is
 * refused, and so is a leading `-`, which `ssh` would read as an option (`-oProxyCommand=…`).
 *
 * Plain `ssh`, never `exec ssh`: a bastion that wraps `ssh` in a shell function (to fetch a
 * certificate, to ask for a login) is only reached through the shell's own lookup.
 */
fun JumpShellSpec.loginCommand(): String? {
    val destination = host.trim()
    val user = username.trim()
    if (!HOST.matches(destination) || destination.length > MAX_HOST_LENGTH) return null
    if (user.isNotEmpty() && (!USER.matches(user) || user.length > MAX_USER_LENGTH)) return null
    if (port !in 1..MAX_PORT) return null
    return buildString {
        append("ssh ")
        if (port != DEFAULT_SSH_PORT) append("-p ").append(port).append(' ')
        if (user.isNotEmpty()) append(user).append('@')
        append(destination)
    }
}

// A name, an IPv4 address or an unbracketed IPv6 one — which is all `ssh` takes as a destination.
private val HOST = Regex("[A-Za-z0-9._:][A-Za-z0-9._:-]*")
private val USER = Regex("[A-Za-z0-9._][A-Za-z0-9._-]*")
private const val MAX_HOST_LENGTH = 253
private const val MAX_USER_LENGTH = 64
private const val MAX_PORT = 65535
private const val DEFAULT_SSH_PORT = 22
