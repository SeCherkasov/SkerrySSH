package app.skerry.ui.vault

import app.skerry.shared.io.PrivateConfig
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_dialog_save_as
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Desktop export via the native AWT [FileDialog] (like [app.skerry.ui.sftp.pickDownloadTarget]).
 * The modal dialog runs a nested EDT event loop, so it's shown on [Dispatchers.Swing]; the write
 * happens on the IO dispatcher. Cancellation (directory/name null) returns [ExportOutcome.Cancelled].
 */
internal actual suspend fun exportTextFile(suggestedName: String, content: String): ExportOutcome {
    // Same dialog title as the SFTP download picker, so the shared key is reused.
    val title = getString(Res.string.sftp_dialog_save_as)
    val path = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val dir = dialog.directory ?: return@withContext null
        val name = dialog.file ?: return@withContext null
        File(dir, name).absolutePath
    } ?: return ExportOutcome.Cancelled
    return withContext(Dispatchers.IO) {
        // What travels through here is secret: a session recording holds whatever the server printed,
        // and a keychain export is the private key itself. writePrivateFile attaches 0600 as the file
        // is created (where the platform has permissions) — writing first and hardening after would
        // leave the key world-readable for the width of that gap, and ssh(1) refuses a key at 0644
        // anyway. Not atomicWrite: that one also forces 0700 on the parent, which here is the
        // directory the user picked in the dialog — their Downloads folder is not ours to
        // re-permission. A failed write is reported rather than swallowed: the user re-authenticated
        // for this and would otherwise believe they have a backup they don't.
        // The String behind the key can't be zeroed (accepted, see Credential's KDoc), but this copy
        // of it can be, and is — no reason to leave a second one for a heap dump or swap to find.
        val bytes = content.toByteArray()
        try {
            runCatching { PrivateConfig.writePrivateFile(File(path).toPath(), bytes) }
                .fold(onSuccess = { ExportOutcome.Saved }, onFailure = { ExportOutcome.Failed })
        } finally {
            bytes.fill(0)
        }
    }
}
