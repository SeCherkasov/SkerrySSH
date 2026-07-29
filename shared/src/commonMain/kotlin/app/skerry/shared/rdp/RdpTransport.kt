package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer
import kotlinx.coroutines.flow.Flow

/**
 * RDP transport — the second remote-desktop protocol beside `VncTransport`, and a separate contract
 * for the same reason: it is a screen-and-input protocol, not a byte stream, so none of the SSH
 * types fit. It mirrors the project's transport shape (pure codec in `commonMain`, platform I/O in
 * `jvmSharedMain`, a cold single-collector `Flow`).
 */
interface RdpTransport {
    /**
     * Connect to [target] and log on with [credentials].
     *
     * @throws RdpAuthException the credentials were refused
     * @throws RdpCertificateRejectedException the server's TLS certificate was not trusted
     * @throws RdpNegotiationException the server refused the security protocols offered
     */
    suspend fun connect(target: RdpTarget, credentials: RdpCredentials): RdpSession
}

/** Where to connect and what session to ask for. */
data class RdpTarget(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val desktopWidth: Int,
    val desktopHeight: Int,
    /** Name this machine reports; it appears in the server's session list. */
    val clientName: String = "Skerry",
    val keyboardLayout: Int = RdpClientSettings.KEYBOARD_LAYOUT_US,
    /** Ask for the clipboard channel; the dynamic channel follows [graphicsPipeline]. */
    val clipboard: Boolean = true,
    /**
     * Ask for the MS-RDPEGFX graphics pipeline — how a Windows 8 or later server would rather draw,
     * and the only path the progressive and Clear codecs travel. Turning it off falls back to the
     * legacy drawing path (surface commands, RemoteFX and bitmap updates), which stays implemented
     * because a server that has no pipeline is still a server this client talks to.
     */
    val graphicsPipeline: Boolean = true,
    /**
     * Ask for the display control channel (MS-RDPEDISP), which is what lets the session's resolution
     * follow the window. Like [graphicsPipeline] it rides the dynamic virtual channel, so turning
     * both off is what leaves that channel out of the connection request altogether.
     */
    val dynamicResize: Boolean = true,
    /**
     * Play the session's sound on this machine (MS-RDPEA). Off unless the profile asks for it: the
     * audio channel costs bandwidth on every notification beep, and a session opened to run one
     * command has no use for it.
     */
    val audioOutput: Boolean = false,
    /**
     * Output device to play on, as [app.skerry.shared.audio.AudioOutputs] names them; empty is the
     * system default, and so is a device that is no longer there.
     */
    val audioDeviceId: String = "",
    /**
     * A Remote Desktop farm's routing token (see [RdpSpec.loadBalanceInfo]); empty for a plain host.
     * It goes into the X.224 Connection Request, before anything is encrypted, so the connection
     * broker can route this connection to the collection the profile came from.
     */
    val loadBalanceInfo: String = "",
    /**
     * Session to rejoin after a Server Redirection Packet named one (MS-RDPBCGR 2.2.13.1). It goes
     * into the Client Cluster Data of the next connection, which is how the target machine knows
     * this is the redirected half of a session rather than a new logon. 0 for a first connection.
     */
    val redirectedSessionId: Int = 0,
) {
    companion object {
        const val DEFAULT_PORT = 3389
    }
}

/**
 * Logon credentials. [password] is used twice on different paths — once by CredSSP before the RDP
 * connection sequence, and not again: the Client Info PDU goes out without it, since the server has
 * already authenticated the user by then.
 */
data class RdpCredentials(
    val username: String,
    val password: String,
    val domain: String = "",
) {
    override fun toString(): String = "RdpCredentials($domain\\$username, password=redacted)"
}

/**
 * A live RDP session. [framebuffer] is the shared pixel buffer (mutated by the read loop);
 * [updates] is a COLD, single-collector flow that drives the loop — collecting it reads server
 * PDUs, applies them and emits an [RdpUpdate] per change. Input methods write to the server.
 */
interface RdpSession {
    /**
     * The host the session actually runs on. It is not always the one that was dialled: a farm
     * broker answers the first connection by naming another machine (MS-RDPBCGR 2.2.13) and the
     * transport follows it, so this is what the tab has to say the user is looking at.
     */
    val connectedHost: String

    /** The remote screen's pixels; read by the UI, written only by the read loop. */
    val framebuffer: RemoteFramebuffer

    /** Cold, single-collector server→client stream. Collecting it runs the session. */
    val updates: Flow<RdpUpdate>

    /** Desktop size the server settled on, which may differ from what was requested. */
    val desktopWidth: Int
    val desktopHeight: Int

    suspend fun sendKey(scancode: Int, down: Boolean, extended: Boolean = false)

    suspend fun sendUnicode(code: Int, down: Boolean)

    suspend fun sendPointerMove(x: Int, y: Int)

    suspend fun sendPointerButton(button: RdpMouseButton, down: Boolean, x: Int, y: Int)

    suspend fun sendWheel(clicks: Int, axis: RdpWheelAxis, x: Int, y: Int)

    /** Report the local lock-key state, so the remote session does not drift out of step. */
    suspend fun sendLockKeys(scroll: Boolean, num: Boolean, caps: Boolean)

    /** Ask the server to repaint [rects] (after the window was obscured). */
    suspend fun requestRefresh(rects: List<RdpRect>)

    /** Tell the server whether anyone is looking; false stops it rendering a hidden window. */
    suspend fun setOutputVisible(visible: Boolean)

    /**
     * Ask the server to serve [width]×[height] from now on (MS-RDPEDISP). Dropped when the server
     * did not open the display control channel, which is how a host that cannot resize says so.
     */
    suspend fun setDesktopSize(width: Int, height: Int)

    /** Send text to the remote clipboard. */
    suspend fun sendClipboardText(text: String)

    /** Whether this session has sound at all: false when no device was opened for it. */
    val audioAvailable: Boolean

    /** Whether the clipboard channel was asked for at connect time. */
    val clipboardAvailable: Boolean

    /**
     * Silence the session's sound, or let it through again. The channel stays open either way — the
     * server opens it from the connection request, so closing it could not be undone mid-session.
     */
    fun setAudioMuted(muted: Boolean)

    /** Close the connection. Idempotent. */
    suspend fun close()
}
