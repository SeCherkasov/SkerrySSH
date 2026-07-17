package app.skerry.shared.vnc

import app.skerry.shared.ssh.SshTarget
import kotlinx.coroutines.flow.Flow

/**
 * VNC/RFB transport — the framebuffer-protocol sibling of `SshTransport`. It is deliberately a
 * SEPARATE contract, not part of `SshConnection`: RFB is a screen + input protocol, not a byte
 * stream, so it does not fit `openShell`/`openSftp`/port-forwarding. It reuses the project's
 * transport *patterns* (a pure state-machine codec in `commonMain`, platform I/O in `jvmSharedMain`,
 * a cold single-collector `Flow`) but none of the SSH *types*.
 *
 * The concrete socket implementation is `app.skerry.shared.vnc.VncTcpTransport` (jvmSharedMain).
 */
interface VncTransport {
    /**
     * Open an RFB session to [target] (only [SshTarget.host]/[SshTarget.port] are used) and run the
     * handshake with [auth]. Returns a live [VncSession] whose read loop starts when the caller
     * collects [VncSession.updates].
     *
     * @throws VncAuthException the server rejected the password / offered no supported security type
     * @throws VncProtocolException the handshake was malformed
     */
    suspend fun connect(target: SshTarget, auth: VncAuth): VncSession
}

/**
 * RFB authentication choice. [None] is the "no authentication" security type (1); [Password] is the
 * classic VNC Authentication DES challenge-response (security type 2). The password is stored in the
 * vault as `CredentialSecret.Password`, exactly like an SSH password.
 */
sealed interface VncAuth {
    data object None : VncAuth

    data class Password(val password: String) : VncAuth {
        override fun toString(): String = "Password(redacted)"
    }
}

/**
 * A live VNC session. [framebuffer] is the shared pixel buffer (mutated in place by the read loop);
 * [updates] is a COLD, single-collector flow (same rule as `ShellChannel.output`) that drives the
 * loop — collecting it reads server messages, applies them to [framebuffer], and emits a
 * [VncUpdate] per message. Input methods ([sendPointer]/[sendKey]/[sendClientCutText]) and
 * [requestUpdate]/[setQuality] write to the server under an internal mutex.
 */
interface VncSession {
    /** Human-readable desktop name from ServerInit (the window/tab label). */
    val serverName: String

    /** The remote screen's pixels; read by the UI, written only by the read loop. */
    val framebuffer: VncFramebuffer

    /** Cold, single-collector server→client message stream. Collecting it runs the session. */
    val updates: Flow<VncUpdate>

    /** Send a pointer (mouse/touch) event in framebuffer coordinates. */
    suspend fun sendPointer(event: VncPointerEvent)

    /** Send a key event (X11 keysym) — [down] true = press, false = release. */
    suspend fun sendKey(keySym: Long, down: Boolean)

    /** Send local clipboard text to the server (ClientCutText, Latin-1). */
    suspend fun sendClientCutText(text: String)

    /**
     * Ask the server for a framebuffer update. [incremental] true = only changes since the last
     * update (the steady-state request); false = the full screen (initial / after a resize).
     */
    suspend fun requestUpdate(incremental: Boolean)

    /** Adjust the quality/compression preference (re-issues SetEncodings / Tight quality level). */
    suspend fun setQuality(quality: VncQuality)

    /**
     * Choose who draws the remote cursor. [enabled] = we do: the server sends its shape as a
     * [VncUpdate.CursorShape] and leaves the framebuffer clean, so the cursor tracks OUR pointer with
     * no round-trip. Disabled, the server paints it into the framebuffer instead — which is the only
     * honest option when our pointer isn't the one moving it (view-only).
     */
    suspend fun setLocalCursor(enabled: Boolean)

    /** Close the socket and end the session. Idempotent. */
    suspend fun close()
}

/**
 * A decoded server→client event surfaced to the UI. [Region] means [VncFramebuffer] changed in the
 * listed rectangles (upload them); [Resize] means the desktop size changed (reallocate the bitmap);
 * [CursorShape] is a new remote cursor sprite; [ClipboardText] is the server's cut buffer; [Bell] is
 * a beep; [Closed] ends the session ([cleanExit] true = the peer closed cleanly, false = a transport
 * drop → the controller may auto-reconnect).
 */
sealed interface VncUpdate {
    data class Region(val rects: List<VncRect>) : VncUpdate
    data class Resize(val width: Int, val height: Int) : VncUpdate

    /**
     * The remote cursor's shape (Cursor pseudo-encoding), sent instead of painting it into the
     * framebuffer. [argb] is [width]×[height] row-major, with alpha taken from RFB's 1-bit mask;
     * [hotspotX]/[hotspotY] is the pixel that sits under the pointer. A 0×0 shape means the server
     * is hiding the cursor.
     */
    data class CursorShape(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        val hotspotX: Int,
        val hotspotY: Int,
    ) : VncUpdate {
        // Hand-written because IntArray uses identity equality (same reason as DecodedImage).
        override fun equals(other: Any?): Boolean =
            other is CursorShape && width == other.width && height == other.height &&
                hotspotX == other.hotspotX && hotspotY == other.hotspotY && argb.contentEquals(other.argb)

        override fun hashCode(): Int =
            (((width * 31 + height) * 31 + hotspotX) * 31 + hotspotY) * 31 + argb.contentHashCode()
    }

    data class ClipboardText(val text: String) : VncUpdate
    data object Bell : VncUpdate
    data class Closed(val cleanExit: Boolean) : VncUpdate
}

/** A rectangle in framebuffer coordinates (a changed region, or a decode target). */
data class VncRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * A pointer event in framebuffer coordinates. [buttonMask] is the RFB PointerEvent bitmask:
 * bit 0 = left, 1 = middle, 2 = right, 3 = wheel-up, 4 = wheel-down, 5 = wheel-left, 6 = wheel-right.
 * A wheel "click" is sent as a press+release of the corresponding bit.
 */
data class VncPointerEvent(val x: Int, val y: Int, val buttonMask: Int)

/**
 * Quality/compression preference. Governs the encoding order advertised in SetEncodings and, for
 * Tight, the JPEG quality level. [Auto] lets the client pick based on the link; the explicit levels
 * trade bandwidth against fidelity.
 */
enum class VncQuality { Auto, Low, Medium, High }
