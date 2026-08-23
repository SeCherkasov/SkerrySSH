package app.skerry.shared.rdp

import app.skerry.shared.graphics.RemoteFramebuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** An RDP session that records what was written to it, for the tests of [RdpRemoteDesktop]. */
open class FakeRdpSession(
    override val connectedHost: String = "fake-host",
    override val desktopWidth: Int = 1024,
    override val desktopHeight: Int = 768,
    override val outputSuppressionSupported: Boolean = true,
    override val updates: Flow<RdpUpdate> = MutableSharedFlow(),
) : RdpSession {

    /** Every write, in the order it was made — the sequence matters as much as the values. */
    val calls = mutableListOf<String>()

    val refreshed = mutableListOf<List<RdpRect>>()
    val visibility = mutableListOf<Boolean>()

    override val framebuffer = RemoteFramebuffer(desktopWidth, desktopHeight)

    override val audioAvailable = true
    override val clipboardAvailable = true

    override suspend fun sendKey(scancode: Int, down: Boolean, extended: Boolean, extended1: Boolean) {
        calls += "key($scancode,$down,$extended,$extended1)"
    }

    override suspend fun sendUnicode(code: Int, down: Boolean) {
        calls += "unicode($code,$down)"
    }

    override suspend fun sendPointerMove(x: Int, y: Int) {
        calls += "move($x,$y)"
    }

    override suspend fun sendPointerButton(button: RdpMouseButton, down: Boolean, x: Int, y: Int) {
        calls += "button($button,$down,$x,$y)"
    }

    override suspend fun sendWheel(clicks: Int, axis: RdpWheelAxis, x: Int, y: Int) {
        calls += "wheel($clicks,$axis,$x,$y)"
    }

    override suspend fun sendLockKeys(scroll: Boolean, num: Boolean, caps: Boolean) {
        calls += "lock($scroll,$num,$caps)"
    }

    override suspend fun requestRefresh(rects: List<RdpRect>) {
        refreshed += rects
        calls += "refresh"
    }

    override suspend fun setOutputVisible(visible: Boolean) {
        visibility += visible
        calls += "visible($visible)"
    }

    override suspend fun setDesktopSize(width: Int, height: Int, scale: Float) {
        calls += "size($width,$height,$scale)"
    }

    override suspend fun sendClipboardText(text: String) {
        calls += "clipboard($text)"
    }

    override fun setAudioMuted(muted: Boolean) {
        calls += "mute($muted)"
    }

    override suspend fun close() {
        calls += "close"
    }
}
