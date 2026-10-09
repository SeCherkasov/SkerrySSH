package app.skerry.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.sanitizeServerText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_reconnect
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vnc.remoteDesktopErrorText
import org.jetbrains.compose.resources.stringResource

/** Shared desktop/mobile notice: the retained frame can never be mistaken for a live session. */
@Composable
internal fun RemoteDesktopDisconnected(ui: RemoteDesktopUiState.Disconnected, onReconnect: () -> Unit) {
    RemoteDesktopConnectionNotice(
        message = stringResource(if (ui.cleanExit) Res.string.vnc_session_closed else Res.string.vnc_connection_lost),
        reason = ui.reason,
        icon = "link_off",
        onReconnect = onReconnect,
    )
}

/** Keep retry available when the previous explicit reconnect could not establish a session. */
@Composable
internal fun RemoteDesktopConnectionError(ui: RemoteDesktopUiState.Error, onReconnect: () -> Unit) {
    RemoteDesktopConnectionNotice(remoteDesktopErrorText(ui), reason = "", icon = "error", onReconnect = onReconnect)
}

@Composable
private fun RemoteDesktopConnectionNotice(message: String, reason: String, icon: String, onReconnect: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Skerry.colors.ink), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Sym(icon, size = 28.sp, color = Skerry.colors.sunset)
            Txt(
                message,
                color = Skerry.colors.sunset,
                size = 14.sp,
                align = TextAlign.Center,
            )
            val detail = sanitizeServerText(reason, maxChars = 512, allowNewlines = false)
            if (detail.isNotBlank()) Txt(detail, color = Skerry.colors.dim, size = 12.sp, align = TextAlign.Center)
            PrimaryButton(stringResource(Res.string.rd_reconnect), onReconnect, Modifier.heightIn(min = 44.dp), icon = "refresh")
        }
    }
}
