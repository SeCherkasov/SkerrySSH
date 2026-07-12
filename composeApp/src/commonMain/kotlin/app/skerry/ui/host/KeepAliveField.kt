package app.skerry.ui.host

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_keepalive_every
import app.skerry.ui.generated.resources.conn_keepalive_off
import org.jetbrains.compose.resources.stringResource

/**
 * Keep-alive cadence choices offered by the profile form, seconds (0 = off); the values behind
 * [app.skerry.shared.host.Host.keepAliveSeconds]. Shared by the desktop modal and the mobile sheet.
 */
val KEEP_ALIVE_OPTIONS: List<Int> = listOf(0, 30, 60, 120)

/** Localized label of a keep-alive cadence: 0 -> "Off", otherwise "Every Ns". */
@Composable
fun keepAliveLabel(seconds: Int): String =
    if (seconds == 0) stringResource(Res.string.conn_keepalive_off)
    else stringResource(Res.string.conn_keepalive_every, seconds)
