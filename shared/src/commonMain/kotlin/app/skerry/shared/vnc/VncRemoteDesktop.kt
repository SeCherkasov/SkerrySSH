package app.skerry.shared.vnc

import app.skerry.shared.graphics.RemoteDesktopCapabilities
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.shared.graphics.RemoteRect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Presents a [VncSession] as a [RemoteDesktopSession]. RFB is the protocol the shared vocabulary was
 * modelled on — pointer state as a button mask, a quality knob, cursor handover — so this is mostly
 * a rename; the work is translating the update stream.
 */
class VncRemoteDesktop(private val session: VncSession) : RemoteDesktopSession {

    override val framebuffer: RemoteFramebuffer get() = session.framebuffer

    override val diagnostics get() = session.diagnostics

    override val title: String get() = session.serverName

    override val capabilities = RemoteDesktopCapabilities(
        adjustableQuality = true,
        remoteResize = true,
        cursorHandover = true,
        // RFB carries no sound.
        audio = false,
        clipboard = true,
    )

    override val updates: Flow<RemoteDesktopUpdate> = session.updates.map { update ->
        when (update) {
            is VncUpdate.Region -> RemoteDesktopUpdate.Region(
                update.rects.map { RemoteRect(it.x, it.y, it.width, it.height) },
            )

            is VncUpdate.Resize -> RemoteDesktopUpdate.Resize(update.width, update.height)
            is VncUpdate.SetDesktopSizeSupported -> RemoteDesktopUpdate.RemoteResizeSupported
            is VncUpdate.CursorShape -> RemoteDesktopUpdate.CursorShape(
                argb = update.argb,
                width = update.width,
                height = update.height,
                hotspotX = update.hotspotX,
                hotspotY = update.hotspotY,
            )

            is VncUpdate.ClipboardText -> RemoteDesktopUpdate.ClipboardText(update.text)
            is VncUpdate.Bell -> RemoteDesktopUpdate.Bell
            is VncUpdate.Closed -> RemoteDesktopUpdate.Closed(update.cleanExit)
        }
    }

    override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int) =
        session.sendPointer(VncPointerEvent(x, y, buttonMask))

    override suspend fun sendKey(event: RemoteKeyEvent, down: Boolean) {
        if (event.keySym != 0L) session.sendKey(event.keySym, down)
    }

    override suspend fun sendClipboardText(text: String) = session.sendClientCutText(text)

    override suspend fun requestFullUpdate() = session.requestUpdate(incremental = false)

    override suspend fun setQuality(quality: RemoteDesktopQuality) = session.setQuality(
        when (quality) {
            RemoteDesktopQuality.Auto -> VncQuality.Auto
            RemoteDesktopQuality.Low -> VncQuality.Low
            RemoteDesktopQuality.Medium -> VncQuality.Medium
            RemoteDesktopQuality.High -> VncQuality.High
        },
    )

    // RFB has no DPI of its own: the scale is dropped, and a HiDPI client gets the desktop it asked
    // for at the size it asked for.
    override suspend fun setDesktopSize(width: Int, height: Int, scale: Float) = session.setDesktopSize(width, height)

    override suspend fun setLocalCursor(enabled: Boolean) = session.setLocalCursor(enabled)

    /** RFB has no way to say "nobody is looking"; an idle client simply stops asking for updates. */
    override suspend fun setOutputVisible(visible: Boolean) = Unit

    /** RFB has no audio channel; there is nothing to silence. */
    override suspend fun setAudioMuted(muted: Boolean) = Unit

    override suspend fun close() = session.close()
}
