package app.skerry.shared.rdp

/**
 * Who to log on as, and what the session should look like (MS-RDPBCGR 2.2.1.11.1.1).
 *
 * [password] is empty when NLA already authenticated: the credentials went through CredSSP, bound
 * to the TLS key, and repeating them here would put the password on a second path for nothing.
 */
data class RdpLogonInfo(
    val domain: String,
    val username: String,
    val password: String = "",
    val alternateShell: String = "",
    val workingDirectory: String = "",
    /** Ask the server to reconnect the user's existing session rather than start a second one. */
    val autoLogon: Boolean = true,
) {
    // The generated toString would print the password, and this class is named in half the PDU
    // builders — one debug log or one exception message away from putting it somewhere it stays.
    override fun toString(): String = "RdpLogonInfo($domain\\$username, password=redacted)"
}

/**
 * The Client Info PDU (MS-RDPBCGR 2.2.1.11): the first PDU after the MCS channels are joined, and
 * the one that says who is logging on.
 */
object ClientInfo {
    // TS_INFO_PACKET::flags (2.2.1.11.1.1).
    private const val INFO_MOUSE = 0x00000001
    private const val INFO_DISABLECTRLALTDEL = 0x00000002
    private const val INFO_AUTOLOGON = 0x00000008
    private const val INFO_UNICODE = 0x00000010
    private const val INFO_MAXIMIZESHELL = 0x00000020
    private const val INFO_LOGONNOTIFY = 0x00000040
    private const val INFO_ENABLEWINDOWSKEY = 0x00000100
    private const val INFO_MOUSE_HAS_WHEEL = 0x00020000
    private const val INFO_NOAUDIOPLAYBACK = 0x08000000

    /** Extended info is mandatory for RDP 5+ servers; the address family says which form it takes. */
    private const val ADDRESS_FAMILY_INET = 0x0002

    /** ANSI code page 0 means "use the one implied by the Unicode flag". */
    private const val CODE_PAGE_DEFAULT = 0

    /**
     * Build the Client Info PDU for [logon].
     *
     * [audioPlayback] says the session's sound is wanted here (the `rdpsnd` channel is in the
     * connection request). Off by default, and then INFO_NOAUDIOPLAYBACK tells the server not to
     * render audio at all — a server that hears that flag keeps the sound channel shut whatever the
     * client asked for, which is exactly what a session nobody listens to should cost.
     *
     * [quality] is the profile's picture: which of the desktop's decoration the server is asked to
     * draw ([RdpImageQuality.performanceFlags]). It is settled here and nowhere else — the server
     * holds it for the life of the session.
     */
    fun pdu(
        logon: RdpLogonInfo,
        audioPlayback: Boolean = false,
        quality: RdpImageQuality = RdpImageQuality.DEFAULT,
    ): ByteArray {
        val writer = RdpWriter(512)
        RdpSecurityHeader.write(writer, RdpSecurityHeader.SEC_INFO_PKT)

        writer.u32le(0) // CodePage, unused with INFO_UNICODE
        var flags = INFO_MOUSE or INFO_MOUSE_HAS_WHEEL or INFO_DISABLECTRLALTDEL or INFO_UNICODE or
            INFO_MAXIMIZESHELL or INFO_ENABLEWINDOWSKEY or INFO_LOGONNOTIFY
        if (!audioPlayback) flags = flags or INFO_NOAUDIOPLAYBACK
        if (logon.autoLogon) flags = flags or INFO_AUTOLOGON
        writer.u32le(flags)

        // Each cb* field counts bytes WITHOUT the terminating null, while the field that follows
        // carries it. Getting that off by two is the classic way to have a server reject the logon.
        val fields = listOf(
            logon.domain,
            logon.username,
            logon.password,
            logon.alternateShell,
            logon.workingDirectory,
        )
        for (text in fields) writer.u16le(text.length * 2)
        for (text in fields) writer.utf16le(text, nullTerminated = true)

        // Extended info. The client address and directory are sent empty: they are self-reported,
        // the server does not act on them, and they would disclose the local network layout.
        writer.u16le(ADDRESS_FAMILY_INET)
        writer.u16le(2).u16le(0) // cbClientAddress + the empty string's terminator
        writer.u16le(2).u16le(0) // cbClientDir + terminator
        writeTimeZone(writer)
        writer.u32le(0) // clientSessionId, ignored by the server
        writer.u32le(quality.performanceFlags)
        writer.u16le(0) // cbAutoReconnectCookie
        return writer.toByteArray()
    }

    /**
     * TS_TIME_ZONE_INFORMATION: 172 bytes the server parses but does not need to be true. UTC with
     * no daylight rule is sent rather than the local zone — the session's clock comes from the
     * server, and the real zone would say where the user is.
     */
    private fun writeTimeZone(writer: RdpWriter) {
        writer.u32le(0) // Bias: UTC
        writer.zeros(64) // StandardName
        writer.zeros(16) // StandardDate (SYSTEMTIME)
        writer.u32le(0) // StandardBias
        writer.zeros(64) // DaylightName
        writer.zeros(16) // DaylightDate
        writer.u32le(0) // DaylightBias
    }
}
