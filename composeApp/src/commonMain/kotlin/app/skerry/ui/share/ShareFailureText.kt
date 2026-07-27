package app.skerry.ui.share

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.share_fail_network
import app.skerry.ui.generated.resources.share_fail_no_key
import app.skerry.ui.generated.resources.share_fail_not_connected
import app.skerry.ui.generated.resources.share_fail_rejected
import app.skerry.ui.generated.resources.share_fail_server
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Localized text for a [ShareFailure] (the reason is typed in the controller, the sentence lives here). */
@Composable
fun shareFailureText(reason: ShareFailure): String = stringResource(shareFailureResource(reason))

/** Reason → its text. Split out of the composable so the wiring itself is unit-testable. */
internal fun shareFailureResource(reason: ShareFailure): StringResource = when (reason) {
    ShareFailure.NotConnected -> Res.string.share_fail_not_connected
    ShareFailure.NoTeamKey -> Res.string.share_fail_no_key
    ShareFailure.Network -> Res.string.share_fail_network
    ShareFailure.Rejected -> Res.string.share_fail_rejected
    ShareFailure.ServerError -> Res.string.share_fail_server
}
