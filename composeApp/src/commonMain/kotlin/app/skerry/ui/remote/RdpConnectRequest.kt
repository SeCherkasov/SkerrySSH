package app.skerry.ui.remote

import app.skerry.shared.rdp.RdpClientSettings
import app.skerry.shared.rdp.RdpH264Mode
import app.skerry.shared.rdp.RdpImageQuality

/**
 * What an RDP tab needs to dial, resolved from the host profile and the vault before the tab opens.
 *
 * [username] may carry a domain as `DOMAIN\user`, which is the form users type and every RDP client
 * accepts; splitting it is the transport's job, not the form's. [width]/[height] come from the
 * window the session will live in — RDP fixes the desktop size at connect time, so asking for the
 * viewport is what avoids a scaled picture.
 */
data class RdpConnectRequest(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val width: Int,
    val height: Int,
    val clientName: String,
    /** The farm routing token of the profile, if it came from an `.rdp` file that named one. */
    val loadBalanceInfo: String = "",
    /** Play the session's sound on this machine (the profile's audio redirection setting). */
    val audioOutput: Boolean = false,
    /** Output device to play it on; empty is the system default. */
    val audioDeviceId: String = "",
    /** Share the clipboard with the session, in both directions (the profile's setting). */
    val clipboard: Boolean = true,
    /** How much of the desktop to ask for; fixed for the session once the connection is made. */
    val imageQuality: RdpImageQuality = RdpImageQuality.DEFAULT,
    /** The local machine's keyboard layout (LCID), so the session types what the keyboard types. */
    val keyboardLayout: Int = RdpClientSettings.KEYBOARD_LAYOUT_US,
    /** Ask for the MS-RDPEGFX pipeline; off is the profile's fallback to the legacy path (F-28). */
    val graphicsPipeline: Boolean = true,
    /** Advertise RemoteFX on the legacy path when the server offers it. */
    val remoteFx: Boolean = true,
    /** Which H.264 ladder the pipeline advertises. */
    val h264: RdpH264Mode = RdpH264Mode.Auto,
    /**
     * How the local display is scaled (1.0 = 100%). [width]/[height] are its physical pixels, and
     * this is what keeps the session at the local DPI instead of at 96 — see
     * [app.skerry.shared.rdp.RdpDisplayScale].
     */
    val displayScale: Float = 1f,
) {
    /** The domain half of `DOMAIN\user`, or empty when the name carries none. */
    val domain: String get() = username.substringBefore('\\', missingDelimiterValue = "")

    /** The user half, without the domain prefix. */
    val user: String get() = username.substringAfter('\\')

    override fun toString(): String = "RdpConnectRequest($host:$port, $username, password=redacted)"
}
