package app.skerry.shared.rdp

import app.skerry.shared.host.Host
import app.skerry.shared.ssh.ConnectionType

/**
 * The profile an `.rdp` file describes, in the app's own terms. [username] already carries the
 * domain as `DOMAIN\user` — the form users type and every RDP client accepts, split apart again at
 * connect time.
 */
data class RdpFileHost(
    val label: String,
    val address: String,
    val port: Int,
    val username: String,
    val loadBalanceInfo: String,
    /** `audiomode:i:0` — the file asks for the session's sound to play on this machine. */
    val audioOutput: Boolean = false,
)

/**
 * Outcome of reading an `.rdp` file: the [host] it describes (`null` when the file names no address
 * to dial) and [warnings] about what was not carried over, so the import screen can say what it
 * dropped.
 */
data class RdpFileImportResult(val host: RdpFileHost?, val warnings: List<RdpImportWarning>)

/**
 * What an import could not carry over. Typed rather than a diagnostic sentence: the screen has to
 * put localised words to it, and matching on English text is how a reworded diagnostic stops being
 * shown at all.
 */
enum class RdpImportWarning {
    /** The file names no address to connect to, so there is no profile to save. */
    NoAddress,

    /** `server port` was outside 1..65535; the default port is used instead. */
    PortOutOfRange,

    /** The file names an RD Gateway. The connection goes straight to the host instead. */
    GatewayIgnored,

    /** The file itself did not read cleanly: a value that is not a number, or more lines than we read. */
    Malformed,
}

/**
 * Turns an `.rdp` file into a saveable profile. Pure and platform-independent, the same split as
 * [app.skerry.shared.ssh.SshConfigImport]: the UI picks the file and persists the result.
 *
 * Scope is deliberately what a connection needs: the address (with its port), who logs on and the
 * farm's routing token. Everything else in such a file — device redirection, display settings, RD
 * Gateway, the publisher signature — is either decided elsewhere in Skerry or not implemented, and
 * the ones that would change where the session lands are reported as warnings rather than ignored.
 *
 * Credentials are never taken from the file: `.rdp` files carry a password only as a DPAPI blob
 * (`password 51:b:`) that is bound to the Windows user who saved it, so there is nothing to import
 * even if we wanted to.
 */
object RdpFileImport {

    /**
     * Read [text] (contents of a file named [fileName], used for the profile label) and map it.
     */
    fun read(text: String, fileName: String): RdpFileImportResult {
        val parsed = RdpFileParser.parse(text)
        val file = parsed.file
        // Every parser complaint is the same thing to a user — the file is not quite right — so
        // they collapse into one row rather than a list of line numbers.
        val warnings = mutableListOf<RdpImportWarning>()
        if (parsed.warnings.isNotEmpty()) warnings += RdpImportWarning.Malformed

        val rawAddress = file.string("full address")?.trim()?.takeIf { it.isNotEmpty() }
            ?: file.string("alternate full address")?.trim()?.takeIf { it.isNotEmpty() }
        if (rawAddress == null) {
            warnings += RdpImportWarning.NoAddress
            return RdpFileImportResult(null, warnings)
        }

        val (address, addressPort) = splitAddress(rawAddress)
        val declaredPort = file.int("server port")
        val port = when {
            addressPort != null -> addressPort
            declaredPort == null -> RdpTarget.DEFAULT_PORT
            declaredPort in 1..MAX_PORT -> declaredPort
            else -> {
                warnings += RdpImportWarning.PortOutOfRange
                RdpTarget.DEFAULT_PORT
            }
        }

        if (file.string("gatewayhostname")?.isNotBlank() == true || (file.int("gatewayusagemethod") ?: 0) != 0) {
            warnings += RdpImportWarning.GatewayIgnored
        }

        val user = file.string("username").orEmpty().trim()
        val domain = file.string("domain").orEmpty().trim()
        return RdpFileImportResult(
            RdpFileHost(
                label = labelFor(fileName, address),
                address = address,
                port = port,
                // A name that already spells out its domain wins: the `domain` setting is what mstsc
                // fills the separate box with, and joining both would produce `A\B\user`.
                username = if (domain.isEmpty() || user.contains('\\') || user.isEmpty()) user else "$domain\\$user",
                loadBalanceInfo = file.string("loadbalanceinfo").orEmpty().trim(),
                // Only an explicit "play on this computer" turns audio on. mstsc treats a missing
                // setting the same way, but a profile that starts opening a sound device nobody
                // asked for is the wrong surprise on an import.
                audioOutput = file.int("audiomode") == AUDIO_MODE_PLAY_LOCALLY,
            ),
            warnings,
        )
    }

    /** Builds the profile to persist, with the caller's [id]. */
    fun toHost(entry: RdpFileHost, id: String): Host = Host(
        id = id,
        label = entry.label,
        address = entry.address,
        port = entry.port,
        username = entry.username,
        credentialId = null,
        connectionType = ConnectionType.RDP,
        // The same default a profile created through the form gets (F-06): a new remote desktop
        // follows the window, whichever door it came in through.
        vncResizeToWindow = true,
        rdp = RdpSpec(loadBalanceInfo = entry.loadBalanceInfo, audioOutput = entry.audioOutput)
            .takeIf { !it.isEmpty },
    )

    /**
     * Split `host:port` while leaving a bare IPv6 literal (`2001:db8::1`, several colons and no
     * brackets) intact; a bracketed one (`[2001:db8::1]:3391`) keeps its port and loses the brackets,
     * since that is how the address is dialled.
     */
    private fun splitAddress(raw: String): Pair<String, Int?> {
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end > 0) {
                val literal = raw.substring(1, end)
                val port = raw.substring(end + 1).removePrefix(":").toIntOrNull()?.takeIf { it in 1..MAX_PORT }
                return literal to port
            }
        }
        val host = raw.substringBeforeLast(':')
        // A colon left in the host half means this is a bare IPv6 literal, not `host:port` — the
        // last group of such an address is a hex number and would otherwise be torn off as a port.
        if (host.contains(':')) return raw to null
        val port = raw.substringAfterLast(':', missingDelimiterValue = "")
            .toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        return if (port == null) raw to null else host to port
    }

    /** File name without its extension, falling back to the address for a file picked without one. */
    private fun labelFor(fileName: String, address: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".rdp").removeSuffix(".RDP")
            .trim()
            .ifEmpty { address }

    private const val MAX_PORT = 65535

    /** `audiomode` values: 0 plays here, 1 leaves the sound on the server, 2 plays it nowhere. */
    private const val AUDIO_MODE_PLAY_LOCALLY = 0
}
