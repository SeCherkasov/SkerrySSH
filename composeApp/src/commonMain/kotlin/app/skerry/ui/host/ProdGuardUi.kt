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
import app.skerry.shared.ai.CommandRiskReason
import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.generated.resources.guard_prod_broadcast_confirm
import app.skerry.ui.generated.resources.guard_prod_broadcast_message
import app.skerry.ui.generated.resources.guard_prod_broadcast_title

/**
 * A connection to a production host waiting for confirmation: [host] names it in the dialog,
 * [proceed] is the connect that was held back, [snippetLine] carries the command of the "run a
 * snippet on this host" path (it opens a session AND runs a command, so the dialog quotes it).
 */
@Immutable
class ProdConnectRequest(val host: Host, val snippetLine: String? = null, val proceed: () -> Unit)

/**
 * Gate in front of every connect path: a production host confirms first, anything else goes
 * straight through. Returns the pending request to show ([ProdConnectDialog]) or `null` when
 * [action] already ran.
 */
fun prodConnectGate(host: Host, snippetLine: String? = null, action: () -> Unit): ProdConnectRequest? {
    if (!isProdHost(host)) {
        action()
        return null
    }
    return ProdConnectRequest(host, snippetLine, action)
}

/**
 * Risk of a command that runs outside a session's own guard — a snippet fired at connect time, a
 * broadcast line fanned out to several hosts. Both are known verbatim and classified for display
 * only, so the threshold is the widest one: the dialog is already being shown, and the reason is
 * what makes it worth reading.
 */
fun prodDisplayRisk(command: String): GuardedCommand? =
    ProductionGuard.inspect(command, DISPLAY_POLICY)

private val DISPLAY_POLICY = ProductionGuardPolicy(production = true, confirmWarnings = true)

/**
 * Keeps every open session's terminal guard ([TerminalScreenState.guardPolicy]) in step with its
 * host profile, every pane included. Driven from the composition rather than set once at connect,
 * so tagging a host `#prod` arms the sessions that are already open (and untagging disarms them).
 */
@Composable
fun ProdGuardSync(sessions: SessionsController?, confirmWarnings: Boolean) {
    val open = sessions?.sessions ?: return
    val hosts = LocalHosts.current
    for (session in open) {
        key(session.id) {
            val panes = session.allPanes
            val policies = panes.map { pane -> prodGuardPolicy(pane.hostId?.let { hosts?.find(it) }, confirmWarnings) }
            // With synchronized input any pane carries what is typed into all the others, so the
            // whole tab runs under the strictest policy of the group: one confirmation then covers
            // the fan-out, and a production pane can't be reached from a non-production one unasked.
            val group = if (session.syncInput && policies.size > 1) policies.reduce(::strictestOf) else null
            panes.forEachIndexed { index, pane ->
                key(pane.id) { BindProdGuard(pane, group ?: policies[index]) }
            }
        }
    }
}

@Composable
private fun BindProdGuard(session: Session, policy: ProductionGuardPolicy) {
    val terminal = session.liveTerminal
    // SideEffect, not LaunchedEffect: this is a plain state write with nothing to suspend on, and it
    // must be applied on every successful composition, not on a coroutine that may run a frame later.
    SideEffect { terminal?.guardPolicy = policy }
}

/** The stricter of two policies on every axis — what a group of synchronized panes runs under. */
internal fun strictestOf(a: ProductionGuardPolicy, b: ProductionGuardPolicy): ProductionGuardPolicy =
    ProductionGuardPolicy(
        production = a.production || b.production,
        confirmWarnings = a.confirmWarnings || b.confirmWarnings,
        rootLogin = a.rootLogin || b.rootLogin,
    )

/** The guard policy a session on [host] runs under. Pure — [BindProdGuard] only applies it. */
fun prodGuardPolicy(host: Host?, confirmWarnings: Boolean): ProductionGuardPolicy =
    ProductionGuardPolicy(
        production = isProdHost(host),
        confirmWarnings = confirmWarnings,
        // The login the profile connects with. `sudo` on a root session says nothing, while a
        // destructive command has nothing in front of it — see [ProductionGuardPolicy].
        rootLogin = isRootLogin(host),
    )

/** Whether the profile logs in as root. Trimmed and case-insensitive: it is free-typed in the form. */
fun isRootLogin(host: Host?): Boolean = host?.username?.trim().equals(ROOT_LOGIN, ignoreCase = true)

/** Login that means "no sudo step in front of anything" for the guard. */
private const val ROOT_LOGIN = "root"

/**
 * Whether a guard confirmation is on screen for [session] (any of its panes).
 *
 * The window-level hotkey handler asks before acting: it sits on the root's preview pass, above the
 * focus the dialog's scrim takes, so without this a snippet chord or a shell shortcut would fire
 * over an open confirmation.
 */
fun prodGuardDialogOpen(session: Session?): Boolean =
    session != null && session.allPanes.any { it.liveTerminal?.pendingGuarded != null }

/**
 * Shows the confirmation for a command held by the guard in one of [session]'s panes. Rendered at
 * the app root, not inside the terminal pane, so the scrim covers the window and the confirmation
 * can't be left behind an overlay.
 */
@Composable
fun ProdCommandGate(session: Session?) {
    if (session == null) return
    // The focused pane is checked first: it is the one being typed into, so with several holds
    // pending its confirmation is the one the user is waiting on.
    val held = (listOf(session.focusedPane) + session.allPanes)
        .firstOrNull { it.liveTerminal?.pendingGuarded != null }
    val terminal = held?.liveTerminal ?: return
    val guarded = terminal.pendingGuarded ?: return
    ProdCommandDialog(
        hostLabel = held.title,
        guarded = guarded,
        onConfirm = { terminal.confirmGuardedCommand() },
        onDismiss = { terminal.dismissGuardedCommand() },
    )
}

/**
 * "Connect to production?" confirmation. Cancel/Esc drops the pending connection.
 *
 * The snippet variant quotes the command: it runs by itself the moment the session opens, before
 * the session's own guard can hold anything, so this dialog is the only place it can be read.
 */
@Composable
fun ProdConnectDialog(request: ProdConnectRequest, onDismiss: () -> Unit) {
    val line = request.snippetLine
    if (line == null) {
        ConfirmActionDialog(
            title = stringResource(Res.string.guard_prod_connect_title),
            message = stringResource(Res.string.guard_prod_connect_message, request.host.label),
            confirmLabel = stringResource(Res.string.guard_prod_connect_confirm),
            onConfirm = { onDismiss(); request.proceed() },
            onDismiss = onDismiss,
        )
        return
    }
    ProdCommandSheet(
        title = stringResource(Res.string.guard_prod_snippet_title),
        subtitle = stringResource(Res.string.guard_prod_snippet_message, request.host.label),
        command = line,
        reason = remember(line) { prodDisplayRisk(line) }?.assessment?.reason,
        confirmLabel = stringResource(Res.string.guard_prod_connect_confirm),
        onConfirm = { onDismiss(); request.proceed() },
        onDismiss = onDismiss,
    )
}

/**
 * "Run this on production?" confirmation for a command held back on its way to the shell.
 */
@Composable
fun ProdCommandDialog(
    hostLabel: String,
    guarded: GuardedCommand,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ProdCommandSheet(
        title = stringResource(Res.string.guard_prod_command_title),
        subtitle = stringResource(Res.string.guard_prod_command_host, hostLabel),
        command = guarded.command,
        reason = guarded.assessment.reason,
        confirmLabel = stringResource(Res.string.guard_prod_command_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * "Broadcast to production?" confirmation: one question for the whole fan-out. Quotes the command
 * for the same reason the single-session dialog does — this is the widest blast radius in the app,
 * and a count of production sessions says nothing about what is about to run on them.
 */
@Composable
fun ProdBroadcastDialog(
    command: String,
    productionCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ProdCommandSheet(
        title = stringResource(Res.string.guard_prod_broadcast_title),
        subtitle = stringResource(Res.string.guard_prod_broadcast_message, productionCount),
        command = command,
        reason = remember(command) { prodDisplayRisk(command) }?.assessment?.reason,
        confirmLabel = stringResource(Res.string.guard_prod_broadcast_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Shared body of the guard confirmations. Unlike [ConfirmActionDialog] it shows the command
 * verbatim in a monospace block: the whole point is to let the user read what they are about to
 * run — a reason alone doesn't say WHICH command tripped it.
 */
@Composable
private fun ProdCommandSheet(
    title: String,
    subtitle: String,
    command: String,
    reason: CommandRiskReason?,
    confirmLabel: String,
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
                title,
                color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            )
            Txt(
                subtitle,
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
                Txt(command, color = Skerry.colors.text, size = 12.sp, font = LocalFonts.current.mono, maxLines = 1)
            }
            reason?.let {
                Txt(
                    commandRiskReasonText(it),
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
                    confirmLabel,
                    onClick = onConfirm,
                    bg = Skerry.colors.sunset,
                    fg = Skerry.colors.sunsetInk,
                )
            }
        }
    }
}
