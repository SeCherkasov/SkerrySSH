package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import app.skerry.shared.mosh.MoshSetupException
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_error_failed
import app.skerry.ui.generated.resources.conn_error_failed_detail
import app.skerry.ui.generated.resources.mosh_err_bootstrap
import app.skerry.ui.generated.resources.mosh_err_bootstrap_no_output
import app.skerry.ui.generated.resources.mosh_err_locale
import app.skerry.ui.generated.resources.mosh_err_not_installed
import app.skerry.ui.generated.resources.mosh_err_udp
import org.jetbrains.compose.resources.stringResource

/**
 * User-facing text for a connect error, localized for typed Mosh and serial failures. Mosh problems
 * are almost always server-side and fixable — Skerry ships only the client, so the message must
 * say what to do on the server (install the package, generate a locale, open UDP) instead of
 * leaking a raw exception string; serial problems say what to do with the device. Everything else
 * keeps the transport's message as before.
 */
@Composable
fun connectionErrorText(error: ConnectionUiState.Error): String {
    error.serialProblem?.let { return serialProblemText(it, error.serialDetail) }
    val reason = error.moshReason ?: return error.message.takeIf { it.isNotBlank() }
        ?.let { stringResource(Res.string.conn_error_failed_detail, it) }
        ?: stringResource(Res.string.conn_error_failed)
    return when (reason) {
        MoshSetupException.Reason.SERVER_NOT_INSTALLED ->
            stringResource(Res.string.mosh_err_not_installed)
        MoshSetupException.Reason.LOCALE_UNSUPPORTED ->
            stringResource(Res.string.mosh_err_locale)
        MoshSetupException.Reason.UDP_UNREACHABLE ->
            stringResource(Res.string.mosh_err_udp, error.moshDetail ?: "60000–61000")
        MoshSetupException.Reason.BOOTSTRAP_FAILED ->
            error.moshDetail?.takeIf { it.isNotBlank() }
                ?.let { stringResource(Res.string.mosh_err_bootstrap, it) }
                ?: stringResource(Res.string.mosh_err_bootstrap_no_output)
    }
}
