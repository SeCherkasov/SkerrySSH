package app.skerry.ui.terminal

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

/** Wraps text in [ClipData] ([ClipEntry]'s native carrier on Android); label is `""`, not `null`. */
internal actual fun plainTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("", text))

/** Text of the first [ClipData] item, or `null` if the clipboard is empty or the item has no text. */
internal actual fun ClipEntry.readPlainText(): String? =
    clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

/** No direct (non-Compose) clipboard read path on Android; reads go through the Compose clipboard. */
internal actual fun readSystemClipboardDirect(): String? = null

/** No direct (non-Compose) clipboard write path on Android; writes go through the Compose clipboard. */
internal actual fun writeSystemClipboardDirect(text: String): Boolean = false

/** No direct clipboard path on Android; reads go through the Compose clipboard. */
internal actual fun systemClipboardDirectHandlesReads(): Boolean = false
