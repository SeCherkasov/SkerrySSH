package app.skerry.ui.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import app.skerry.shared.graphics.IdentityCache
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.ui.vnc.VncCursorImage

/** Cursor, clipboard and audio presentation, independent of framebuffer and connection lifetime. */
internal class RemoteDesktopPeripheralState(private val onClipboard: (String) -> Unit, clipboardSharedInitial: Boolean) {
    var cursor: VncCursorImage? by mutableStateOf(null)
        private set
    var serverPointer: IntOffset? by mutableStateOf(null)
    var systemCursor by mutableStateOf(false)
        private set
    var audioFailed by mutableStateOf(false)
        private set
    var clipboardShared by mutableStateOf(clipboardSharedInitial)
        private set
    var serverClipboard: String? by mutableStateOf(null)
        private set

    // Cached protocol shapes retain their identity across re-announcements.
    private val spriteCache = IdentityCache<RemoteDesktopUpdate.CursorShape, VncCursorImage?>(SPRITE_CACHE_SIZE)

    fun toggleClipboardShared() {
        clipboardShared = !clipboardShared
        if (!clipboardShared) serverClipboard = null
    }

    fun onUpdate(update: RemoteDesktopUpdate) {
        when (update) {
            is RemoteDesktopUpdate.CursorShape -> {
                cursor = spriteCache.getOrPut(update) { VncCursorImage.of(update) }
                systemCursor = false
            }

            is RemoteDesktopUpdate.CursorPosition -> serverPointer = IntOffset(update.x, update.y)
            // "Visible" here is the server asking for its default pointer, not for the shape it sent
            // last: the sprite goes, and the local pointer takes over. Hidden drops both.
            is RemoteDesktopUpdate.CursorVisible -> {
                cursor = null
                systemCursor = update.visible
            }

            is RemoteDesktopUpdate.ClipboardText -> if (clipboardShared) {
                serverClipboard = update.text
                onClipboard(update.text)
            }

            is RemoteDesktopUpdate.AudioPlaybackFailing -> audioFailed = update.failing
            is RemoteDesktopUpdate.Bell -> {}

            // Handled by the caller; named so this `when` stays exhaustive.
            is RemoteDesktopUpdate.Region,
            is RemoteDesktopUpdate.Resize,
            is RemoteDesktopUpdate.RemoteResizeSupported,
            is RemoteDesktopUpdate.Closed,
            -> Unit
        }
    }

    private companion object {
        // Matches the RDP cache with room for uncached shapes.
        const val SPRITE_CACHE_SIZE = 32
    }
}
