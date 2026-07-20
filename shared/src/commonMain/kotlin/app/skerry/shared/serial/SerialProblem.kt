package app.skerry.shared.serial

/**
 * Why a serial port could not be opened. Platform-neutral on purpose: desktop (native port) and
 * Android (USB-OTG) must report the same cause for the same situation, and the UI turns it into
 * localized text (`serialProblemText`) instead of surfacing a platform message string — same rule
 * as [app.skerry.shared.sync.SyncFailureReason].
 */
enum class SerialProblem {
    /** The device has no serial support at all (no USB Host API, subsystem not initialized). */
    UNSUPPORTED,

    /** No port with the requested name — unplugged, renamed, or a port index that doesn't exist. */
    PORT_NOT_FOUND,

    /** The OS denied access to the port (Android USB permission, desktop device permissions). */
    PERMISSION_DENIED,

    /** The port exists but wouldn't open: busy, held by another program, or a driver failure. */
    OPEN_FAILED,

    /** The port opened but rejected the frame format (baud rate, data/stop bits, parity). */
    CONFIGURE_FAILED,
}

/**
 * Port unavailable: the platform has no serial support, or the device could not be opened.
 * [problem] is the typed cause and [detail] the port name it refers to; [message] is an English
 * fallback for logs — user-facing text is rendered from [problem] in the UI.
 */
class SerialUnavailableException(
    val problem: SerialProblem,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(defaultMessage(problem, detail), cause)

private fun defaultMessage(problem: SerialProblem, detail: String?): String {
    val port = detail ?: "serial port"
    return when (problem) {
        SerialProblem.UNSUPPORTED -> "Serial ports are unavailable on this device"
        SerialProblem.PORT_NOT_FOUND -> "Port $port not found"
        SerialProblem.PERMISSION_DENIED -> "No permission to access port $port"
        SerialProblem.OPEN_FAILED -> "Failed to open port $port"
        SerialProblem.CONFIGURE_FAILED -> "Failed to configure port $port"
    }
}
