package app.skerry.ui.forward

/**
 * Parsed and validated forward form parameters (direction excluded, chosen separately). Only
 * constructed via [parseForwardInput], so values are always valid.
 */
data class ForwardRequest(
    val bindPort: Int,
    val destHost: String,
    val destPort: Int,
)

/**
 * Parses and validates raw forward form input. Returns [ForwardRequest] on valid data or `null`
 * if fields are incomplete/invalid.
 *
 * Rules: listener port `0..65535` (`0` = OS/server picks), destination host non-empty,
 * destination port `1..65535`.
 *
 * Shared source of truth for the desktop and mobile forward forms.
 */
fun parseForwardInput(bindPort: String, destHost: String, destPort: String): ForwardRequest? {
    val bind = parseBindPort(bindPort) ?: return null
    val host = destHost.trim().ifEmpty { return null }
    val dest = destPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return ForwardRequest(bind, host, dest)
}

/**
 * Validates a listener port on its own, for a dynamic (`-D`) forward with no destination.
 * `0..65535` (`0` = OS picks); `null` if invalid.
 */
fun parseBindPort(bindPort: String): Int? =
    bindPort.trim().toIntOrNull()?.takeIf { it in 0..65535 }
