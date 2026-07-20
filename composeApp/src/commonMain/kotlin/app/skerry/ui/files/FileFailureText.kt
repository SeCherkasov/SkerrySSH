package app.skerry.ui.files

import androidx.compose.runtime.Composable
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ftail_delete_source_failed
import app.skerry.ui.generated.resources.ftail_err_illegal_name
import app.skerry.ui.generated.resources.ftail_err_local_io
import app.skerry.ui.generated.resources.ftail_err_open_source
import app.skerry.ui.generated.resources.ftail_err_open_target
import app.skerry.ui.generated.resources.ftail_err_sftp
import app.skerry.ui.generated.resources.ftail_transfer_error
import org.jetbrains.compose.resources.stringResource

/** Localized text for a file pane failure ([app.skerry.shared.files.FileBrowserException.failure]). */
@Composable
fun fileBrowserFailureText(failure: FileBrowserFailure): String = stringResource(
    when (failure) {
        FileBrowserFailure.LocalIo -> Res.string.ftail_err_local_io
        FileBrowserFailure.Sftp -> Res.string.ftail_err_sftp
        FileBrowserFailure.IllegalName -> Res.string.ftail_err_illegal_name
        FileBrowserFailure.OpenSource -> Res.string.ftail_err_open_source
        FileBrowserFailure.OpenTarget -> Res.string.ftail_err_open_target
    },
)

/** Localized text for a [TransferState.Failed] reason. */
@Composable
fun transferFailureText(failure: FileTransferFailure): String = stringResource(
    when (failure) {
        FileTransferFailure.Transfer -> Res.string.ftail_transfer_error
        FileTransferFailure.DeleteSource -> Res.string.ftail_delete_source_failed
        FileTransferFailure.IllegalName -> Res.string.ftail_err_illegal_name
        FileTransferFailure.OpenSource -> Res.string.ftail_err_open_source
        FileTransferFailure.OpenTarget -> Res.string.ftail_err_open_target
    },
)
