package app.skerry.shared.rdp

/**
 * What this client tells the server about itself in the GCC conference data (MS-RDPBCGR 2.2.1.3):
 * the session it wants and the virtual channels it will use.
 *
 * [selectedProtocol] must be the protocol the server chose in the X.224 negotiation — the server
 * compares the echo against what it sent and drops the connection on a mismatch, which is what stops
 * an attacker from downgrading the negotiation and having the client agree.
 *
 * [channels] are static virtual channels by name: `cliprdr` for the clipboard, `rdpsnd` for the
 * session's sound, `drdynvc` for the dynamic channels the graphics pipeline rides on. Order matters
 * — the server answers with channel ids in the same order.
 */
data class RdpClientSettings(
    val desktopWidth: Int,
    val desktopHeight: Int,
    val clientName: String,
    val selectedProtocol: Int,
    val keyboardLayout: Int = KEYBOARD_LAYOUT_US,
    val keyboardType: Int = KEYBOARD_TYPE_IBM_ENHANCED,
    val keyboardFunctionKeys: Int = 12,
    val clientBuild: Int = 3790,
    val channels: List<String> = emptyList(),
    /** Ask for the MS-RDPEGFX pipeline (the server must also have advertised it in the negotiation). */
    val wantsGraphicsPipeline: Boolean = true,
    /** Session a redirection told us to rejoin (see [RdpTarget.redirectedSessionId]); 0 if none. */
    val redirectedSessionId: Int = 0,
) {
    init {
        require(desktopWidth in MIN_DIMENSION..MAX_DIMENSION) { "desktop width out of range" }
        require(desktopHeight in MIN_DIMENSION..MAX_DIMENSION) { "desktop height out of range" }
        require(channels.size <= MAX_CHANNELS) { "at most $MAX_CHANNELS virtual channels" }
        require(channels.all { it.length <= CHANNEL_NAME_SIZE - 1 }) { "channel names are 7 characters or fewer" }
    }

    companion object {
        const val KEYBOARD_LAYOUT_US = 0x409
        const val KEYBOARD_TYPE_IBM_ENHANCED = 4

        /** MS-RDPBCGR 2.2.1.3.2: the desktop dimensions the server accepts. */
        const val MIN_DIMENSION = 200
        const val MAX_DIMENSION = 8192

        /** CHANNEL_DEF::name is 8 bytes, null-terminated (MS-RDPBCGR 2.2.1.3.4.1). */
        const val CHANNEL_NAME_SIZE = 8

        /** The MCS domain has room for 31 channels above the fixed ones; this is well under it. */
        const val MAX_CHANNELS = 16

        /** Static channel names this client knows how to speak. */
        const val CHANNEL_CLIPBOARD = "cliprdr"
        const val CHANNEL_DYNAMIC = "drdynvc"

        /** The session's sound, played on this machine (MS-RDPEA). */
        const val CHANNEL_AUDIO = "rdpsnd"
    }
}
