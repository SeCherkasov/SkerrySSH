package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_status_closed
import app.skerry.ui.generated.resources.term_status_connected
import app.skerry.ui.generated.resources.term_status_connecting
import app.skerry.ui.generated.resources.term_status_disconnected
import app.skerry.ui.generated.resources.term_status_no_session
import org.jetbrains.compose.resources.stringResource

/** Localized status-line text for [MobileTerminalStatus] (mobile terminal header). */
@Composable
fun mobileTerminalStatusText(status: MobileTerminalStatus): String = stringResource(
    when (status) {
        MobileTerminalStatus.Connected -> Res.string.term_status_connected
        MobileTerminalStatus.Connecting -> Res.string.term_status_connecting
        MobileTerminalStatus.Disconnected -> Res.string.term_status_disconnected
        MobileTerminalStatus.Closed -> Res.string.term_status_closed
        MobileTerminalStatus.NoSession -> Res.string.term_status_no_session
    },
)
