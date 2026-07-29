package app.skerry.ui.remote

import app.skerry.shared.graphics.RemoteDesktopCapabilities
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.graphics.RemoteKeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A remote desktop with a controllable update flow and captured input, for the UI-side tests. */
open class FakeRemoteDesktop(
    override val title: String = "fake-desktop",
    override val framebuffer: RemoteFramebuffer = RemoteFramebuffer(2, 1),
    override val updates: Flow<RemoteDesktopUpdate> = MutableSharedFlow(),
) : RemoteDesktopSession {
    val pointers = mutableListOf<Triple<Int, Int, Int>>()
    val keys = mutableListOf<Pair<RemoteKeyEvent, Boolean>>()
    val clipboard = mutableListOf<String>()
    val localCursor = mutableListOf<Boolean>()
    val fullUpdates = mutableListOf<Boolean>()
    val desktopSizes = mutableListOf<Pair<Int, Int>>()
    val visibility = mutableListOf<Boolean>()
    var closed = false

    val audioMutes = mutableListOf<Boolean>()

    override val capabilities = RemoteDesktopCapabilities(
        adjustableQuality = true,
        remoteResize = true,
        cursorHandover = true,
        audio = true,
        clipboard = true,
    )

    override suspend fun setAudioMuted(muted: Boolean) {
        audioMutes += muted
    }

    override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        pointers += Triple(x, y, buttonMask)
    }

    override suspend fun sendKey(event: RemoteKeyEvent, down: Boolean) {
        keys += event to down
    }

    override suspend fun sendClipboardText(text: String) {
        clipboard += text
    }

    override suspend fun requestFullUpdate() {
        fullUpdates += false
    }

    override suspend fun setQuality(quality: RemoteDesktopQuality) = Unit

    override suspend fun setDesktopSize(width: Int, height: Int) {
        desktopSizes += width to height
    }

    override suspend fun setLocalCursor(enabled: Boolean) {
        localCursor += enabled
    }

    override suspend fun setOutputVisible(visible: Boolean) {
        visibility += visible
    }

    override suspend fun close() {
        closed = true
    }
}
