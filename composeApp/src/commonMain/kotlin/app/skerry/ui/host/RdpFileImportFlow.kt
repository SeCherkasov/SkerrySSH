package app.skerry.ui.host

import androidx.compose.runtime.Composable
import app.skerry.shared.rdp.RdpFileImport
import app.skerry.shared.rdp.RdpFileImportResult
import app.skerry.shared.rdp.RdpImportWarning
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_rdp_import_gateway
import app.skerry.ui.generated.resources.conn_rdp_import_malformed
import app.skerry.ui.generated.resources.conn_rdp_import_no_address
import app.skerry.ui.generated.resources.conn_rdp_import_port
import app.skerry.ui.generated.resources.conn_rdp_import_title
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.vault.importTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/**
 * Opens the native file picker for a `.rdp` file and maps it off the main thread. Returns `null`
 * when the user cancels or the file can't be read; a picked file always returns a result — one that
 * names no address is reported by the modal rather than silently doing nothing.
 */
suspend fun pickAndParseRdpFile(): RdpFileImportResult? {
    val file = importTextFile(getString(Res.string.conn_rdp_import_title)) ?: return null
    return withContext(Dispatchers.Default) { RdpFileImport.read(file.text, file.name) }
}

/** What to show the user for [warning]; the same wording on the desktop modal and the mobile sheet. */
@Composable
fun rdpImportWarningText(warning: RdpImportWarning): String = stringResource(
    when (warning) {
        RdpImportWarning.NoAddress -> Res.string.conn_rdp_import_no_address
        RdpImportWarning.PortOutOfRange -> Res.string.conn_rdp_import_port
        RdpImportWarning.GatewayIgnored -> Res.string.conn_rdp_import_gateway
        RdpImportWarning.Malformed -> Res.string.conn_rdp_import_malformed
    },
)
