package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.guard.GuardedCommand
import app.skerry.shared.host.Host
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.guard_prod_command_confirm
import app.skerry.ui.generated.resources.guard_prod_command_host
import app.skerry.ui.generated.resources.guard_prod_command_title
import app.skerry.ui.generated.resources.guard_prod_connect_confirm
import app.skerry.ui.generated.resources.guard_prod_connect_message
import app.skerry.ui.generated.resources.guard_prod_connect_title
import app.skerry.ui.generated.resources.guard_prod_snippet_message
import app.skerry.ui.generated.resources.guard_prod_snippet_title
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import app.skerry.ui.session.Session
import app.skerry.ui.session.SessionsController
import app.skerry.ui.ai.commandRiskReasonText

/**
 * A connection to a production host waiting for confirmation: [host] names it in the dialog,
 * [proceed] is the connect that was held back, [snippet] marks the "run a snippet on this host"
 * path (it opens a session AND runs a command, so it says so).
 */
@Immutable
class ProdConnectRequest(val host: Host, val snippet: Boolean = false, val proceed: () -> Unit)

/**
 * Gate in front of every connect path: a production host confirms first, anything else goes
 * straight through. Returns the pending request to show ([ProdConnectDialog]) or `null` when
 * [action] already ran.
 */
fun prodConnectGate(host: Host, snippet: Boolean = false, action: () -> Unit): ProdConnectRequest? {
    if (!isProdHost(host)) {
        action()
        return null
    }
    return ProdConnectRequest(host, snippet, action)
}

/**
 * Keeps every open session's terminal guard ([TerminalScreenState.guardProduction]) in step with
 * its host profile, splits included. Driven from the composition rather than set once at connect,
 * so tagging a host `#prod` arms the sessions that are already open (and untagging disarms them).
 */
@Composable
fun ProdGuardSync(sessions: SessionsController?) {
    val open = sessions?.sessions ?: return
    for (session in open) {
        key(session.id) {
            BindProdGuard(session)
            session.splitSession?.let { split -> key(split.id) { BindProdGuard(split) } }
        }
    }
}

@Composable
private fun BindProdGuard(session: Session) {
    val prod = isProdHostId(session.hostId)
    val terminal = session.liveTerminal
    // SideEffect, not LaunchedEffect: this is a plain state write with nothing to suspend on, and it
    // must be applied on every successful composition, not on a coroutine that may run a frame later.
    SideEffect { terminal?.guardProduction = prod }
}

/**
 * Shows the confirmation for a command held by the guard in [session] (or in its focused split
 * pane). Rendered at the app root, not inside the terminal pane, so the scrim covers the window
 * and the confirmation can't be left behind an overlay.
 */
@Composable
fun ProdCommandGate(session: Session?) {
    if (session == null) return
    // The split pane is checked first: it is the pane that has focus while it is being typed into.
    val held = listOfNotNull(session.splitSession, session).firstOrNull { it.liveTerminal?.pendingGuarded != null }
    val terminal = held?.liveTerminal ?: return
    val guarded = terminal.pendingGuarded ?: return
    ProdCommandDialog(
        hostLabel = held.title,
        guarded = guarded,
        onConfirm = { terminal.confirmGuardedCommand() },
        onDismiss = { terminal.dismissGuardedCommand() },
    )
}

/** "Connect to production?" confirmation. Cancel/Esc drops the pending connection. */
@Composable
fun ProdConnectDialog(request: ProdConnectRequest, onDismiss: () -> Unit) {
    val title = if (request.snippet) Res.string.guard_prod_snippet_title else Res.string.guard_prod_connect_title
    val message = if (request.snippet) Res.string.guard_prod_snippet_message else Res.string.guard_prod_connect_message
    ConfirmActionDialog(
        title = stringResource(title),
        message = stringResource(message, request.host.label),
        confirmLabel = stringResource(Res.string.guard_prod_connect_confirm),
        onConfirm = { onDismiss(); request.proceed() },
        onDismiss = onDismiss,
    )
}

/**
 * "Run this on production?" confirmation for a command held back on its way to the shell. Unlike
 * [ConfirmActionDialog] it shows the command verbatim in a monospace block: the whole point is to
 * let the user read what they are about to run — the classifier's [GuardedCommand.assessment]
 * reason alone doesn't say WHICH command tripped it.
 */
@Composable
fun ProdCommandDialog(
    hostLabel: String,
    guarded: GuardedCommand,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Skerry.colors.surfaceDeep)
                .border(1.dp, Skerry.colors.sunset, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(
                stringResource(Res.string.guard_prod_command_title),
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            )
            Txt(
                stringResource(Res.string.guard_prod_command_host, hostLabel),
                color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            // The command scrolls sideways instead of wrapping: a wrapped one-liner reads as several
            // commands, and this dialog exists to be read exactly.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Skerry.colors.terminalBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Txt(guarded.command, color = Skerry.colors.text, size = 12.sp, font = LocalFonts.current.mono, maxLines = 1)
            }
            guarded.assessment.reason?.let { reason ->
                Txt(
                    commandRiskReasonText(reason),
                    color = Skerry.colors.sunset, size = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss)
                PrimaryButton(
                    stringResource(Res.string.guard_prod_command_confirm),
                    onClick = onConfirm,
                    bg = Skerry.colors.sunset,
                    fg = Skerry.colors.sunsetInk,
                )
            }
        }
    }
}
