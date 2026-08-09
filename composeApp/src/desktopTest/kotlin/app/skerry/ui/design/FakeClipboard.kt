package app.skerry.ui.design

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * Clipboard that keeps what is put on it instead of reaching for the developer's own. Provided
 * through `LocalClipboard`, which is the only path Compose's selection manager writes through — a
 * test that ends up on the system clipboard instead reads back `null` and fails.
 */
internal class FakeClipboard : Clipboard {
    private var entry: ClipEntry? = null

    val text: String?
        get() = (entry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entry = clipEntry
    }

    // Never read by the code under test; a private AWT clipboard keeps the system one untouched.
    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("skerry-test")
}
