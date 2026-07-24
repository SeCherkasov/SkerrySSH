package app.skerry.ui.host

import app.skerry.shared.ssh.SshConfigParseResult
import app.skerry.shared.ssh.SshConfigParser
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_import_title
import app.skerry.ui.vault.importTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Opens the native file picker for an `ssh_config` file and parses it off the main thread. Returns
 * `null` when the user cancels or the file can't be read (nothing to show); a picked file always
 * returns a result — an empty [SshConfigParseResult.hosts] is a valid outcome the modal reports as
 * "no hosts found" rather than silently doing nothing.
 */
suspend fun pickAndParseSshConfig(): SshConfigParseResult? {
    val file = importTextFile(getString(Res.string.conn_import_title)) ?: return null
    return withContext(Dispatchers.Default) { SshConfigParser.parse(file.text) }
}
