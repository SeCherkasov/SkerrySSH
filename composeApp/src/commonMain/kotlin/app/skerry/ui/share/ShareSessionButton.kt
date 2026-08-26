package app.skerry.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.share.ShareFrame
import app.skerry.ui.connection.ConnectionUiState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.collectAsState
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.ui.app.LocalSharedSessions
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.design.ChipButton
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.CloseWhenUnavailable
import app.skerry.ui.design.IconBtn
import app.skerry.ui.design.InitialsAvatar
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.design.rememberModalPresence
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.share_allow_input
import androidx.compose.runtime.rememberCoroutineScope
import app.skerry.ui.generated.resources.share_control_asked
import app.skerry.ui.generated.resources.share_control_granted
import app.skerry.ui.generated.resources.share_control_request
import app.skerry.ui.generated.resources.share_control_wants
import app.skerry.ui.generated.resources.share_control_wants_unnamed
import app.skerry.ui.generated.resources.share_read_only
import app.skerry.ui.generated.resources.share_watching
import app.skerry.ui.generated.resources.share_deny
import app.skerry.ui.generated.resources.share_grant
import app.skerry.ui.generated.resources.share_input_locked
import app.skerry.ui.generated.resources.share_live
import app.skerry.ui.generated.resources.share_no_teams
import app.skerry.ui.generated.resources.share_note
import app.skerry.ui.generated.resources.share_pick_team
import app.skerry.ui.generated.resources.share_session
import app.skerry.ui.generated.resources.share_session_stop
import app.skerry.ui.generated.resources.share_starting
import app.skerry.ui.generated.resources.share_title
import app.skerry.ui.generated.resources.share_viewers
import app.skerry.ui.session.Session
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Whether the team may only watch this session, never type into it: true for a production-tagged
 * host, whose guard would otherwise be bypassed — a viewer's keys arrive past the confirmation
 * dialog. Read off the session's live guard policy, whose [ProductionGuardPolicy.production] is the
 * `#prod` tag itself; the user's "confirm warnings too" preference is also carried in that policy
 * but says nothing about the host, so it must not lock a share down.
 */
internal fun viewersMayOnlyWatch(policy: ProductionGuardPolicy): Boolean = policy.production

/**
 * Toolbar toggle for sharing the live session with a team: opens a small panel to pick the team,
 * then shows what the team sees (viewer count, whether they may type) and stops the share.
 *
 * Lit cyan while a session is being shared — a shell streaming to colleagues must be visible at a
 * glance, not something one forgets is on.
 */
@Composable
fun ShareSessionButton(
    session: Session?,
    controller: SessionShareController?,
    teams: List<Pair<String, String>>,
    // Fired by the overflow menu when the row is too narrow to show the button itself; the button
    // owns the panel, so the menu asks it to open rather than duplicating it.
    requests: SharedFlow<Unit>? = null,
) {
    // Keyed on the session, like the other toolbar popups: switching tabs must not leave the panel
    // open over a different session's toolbar.
    var open by remember(session) { mutableStateOf(false) }
    val state = controller?.state ?: ShareUiState.Off
    val live = state is ShareUiState.Live
    val terminal = (session?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    // A shared session that is resized must tell its viewers, or they keep rendering the old grid.
    LaunchedEffect(live, terminal?.cols, terminal?.rows) {
        if (live) controller?.announceGeometry()
    }
    // `live` is a key too, not just a captured value: a stream stopped without the terminal changing
    // would otherwise leave this collector opening the panel on a share that is already over.
    LaunchedEffect(requests, terminal, live) { requests?.collect { if (live || terminal != null) open = true } }
    // A pane that is watching someone else's session gets the viewer's panel behind the same button:
    // its only control is asking the host for permission to type. Relaying a colleague's stream on
    // to a third team is never offered — the pane's own flag decides that, not the viewer registry,
    // which is keyed by pane id and cleared the moment the watched session ends.
    val watched = LocalSharedSessions.current?.watching?.get(session?.id)
    if (watched != null) {
        WatchedSessionButton(watched, session?.subtitle.orEmpty(), requests)
        return
    }
    if (session?.controller?.isWatched == true) return
    if (controller == null) return
    Box {
        // Nothing to share without a live session, but a stream already running keeps the control
        // that stops it. Disabled rather than dimmed-and-live: the guard used to sit in the handler,
        // where the press was taken and dropped.
        val canShare = live || terminal != null
        CloseWhenUnavailable(canShare) { open = false }
        IconBtn(
            name = if (live) "cast_connected" else "cast",
            tint = if (live) Skerry.colors.cyanBright else Skerry.colors.dim,
            onClick = { open = !open },
            enabled = canShare,
            tooltip = stringResource(if (live) Res.string.share_session_stop else Res.string.share_session),
        )
        if (open && canShare) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SharePanel(
                    state = state,
                    teams = teams,
                    onShare = { teamId, teamName ->
                        if (session != null && terminal != null) {
                            controller.share(
                                teamId = teamId,
                                teamName = teamName,
                                paneId = session.id,
                                label = session.displayTitle.ifBlank { session.subtitle },
                                source = ShareSource(
                                    output = terminal.ptyOutput,
                                    toShell = { bytes -> terminal.sendSharedInput(bytes) },
                                    geometry = { ShareFrame.Resize(terminal.cols, terminal.rows) },
                                    // Ends the broadcast when the shell does — see [ShareSource].
                                    sessionState = terminal.state,
                                ),
                                readOnlyOnly = viewersMayOnlyWatch(terminal.guardPolicy),
                            )
                        }
                        open = false
                    },
                    onAllowInput = { controller.setInputAllowed(it) },
                    onAnswerRequest = { grant -> controller.answerControlRequest(grant) },
                    onStop = {
                        controller.stop()
                        open = false
                    },
                    onDismiss = {
                        controller.clearFailure()
                        open = false
                    },
                )
            }
        }
    }
}

/** The panel behind the toolbar toggle: pick a team, or manage the live share. */
@Composable
internal fun SharePanel(
    state: ShareUiState,
    teams: List<Pair<String, String>>,
    onShare: (String, String) -> Unit,
    onAllowInput: (Boolean) -> Unit,
    onAnswerRequest: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    rememberModalPresence()
    Column(
        Modifier.width(320.dp)
            .background(Skerry.colors.surface2, RoundedCornerShape(9.dp))
            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(9.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(stringResource(Res.string.share_title), size = 13.sp, color = Skerry.colors.text)
        when (state) {
            ShareUiState.Off -> {
                Txt(stringResource(Res.string.share_note), size = 11.5.sp, color = Skerry.colors.dim)
                if (teams.isEmpty()) {
                    Txt(stringResource(Res.string.share_no_teams), size = 11.5.sp, color = Skerry.colors.faint)
                } else {
                    Txt(stringResource(Res.string.share_pick_team), size = 11.sp, color = Skerry.colors.faint)
                    teams.forEach { (id, name) ->
                        ChipButton(untrustedLabel(name), color = Skerry.colors.cyan, onClick = { onShare(id, name) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            ShareUiState.Starting -> Txt(stringResource(Res.string.share_starting), size = 12.sp, color = Skerry.colors.dim)
            is ShareUiState.Live -> {
                Txt(stringResource(Res.string.share_live, untrustedLabel(state.teamName)), size = 12.sp, color = Skerry.colors.cyanBright)
                if (state.viewerAccounts.isEmpty()) {
                    Txt(stringResource(Res.string.share_viewers, state.viewers), size = 11.5.sp, color = Skerry.colors.dim)
                } else {
                    // Who is on the session, by name: on a shared shell it matters which colleague
                    // is watching, not how many are.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.viewerAccounts.take(MAX_SHOWN_VIEWERS).forEach { account ->
                            InitialsAvatar(account, size = 22.dp)
                        }
                        val extra = state.viewerAccounts.size - MAX_SHOWN_VIEWERS
                        Txt(
                            if (extra > 0) stringResource(Res.string.share_viewers, state.viewers)
                            else state.viewerAccounts.joinToString(", ") { untrustedLabel(it) },
                            size = 11.5.sp,
                            color = Skerry.colors.dim,
                            maxLines = 1,
                        )
                    }
                }
                // A viewer asking for control is answered in the panel: the terminal shows only who
                // is typing, so nothing that needs a decision sits over a live shell.
                if (state.controlRequestPending) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // A relay older than the naming protocol sends no account with the request.
                        // The answer is the same either way — granting lets everyone watching type —
                        // so the row is shown without a name rather than not at all.
                        val account = state.controlRequestBy
                        if (account != null) InitialsAvatar(account, size = 20.dp)
                        Txt(
                            if (account != null) {
                                stringResource(Res.string.share_control_wants, untrustedLabel(account))
                            } else {
                                stringResource(Res.string.share_control_wants_unnamed)
                            },
                            size = 11.5.sp,
                            color = Skerry.colors.amber,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GhostButton(stringResource(Res.string.share_grant), onClick = { onAnswerRequest(true) }, icon = "check")
                        GhostButton(
                            stringResource(Res.string.share_deny),
                            onClick = { onAnswerRequest(false) },
                            icon = "close",
                            fg = Skerry.colors.dim,
                        )
                    }
                }
                if (state.inputLocked) {
                    Txt(stringResource(Res.string.share_input_locked), size = 11.5.sp, color = Skerry.colors.amber)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(stringResource(Res.string.share_allow_input), size = 12.sp, modifier = Modifier.weight(1f))
                        Toggle(
                            on = state.inputAllowed,
                            onToggle = { onAllowInput(!state.inputAllowed) },
                            label = stringResource(Res.string.share_allow_input),
                        )
                    }
                }
                GhostButton(
                    stringResource(Res.string.share_session_stop),
                    onClick = onStop,
                    icon = "stop_circle",
                    fg = Skerry.colors.sunset,
                    border = Skerry.colors.sunset.copy(alpha = 0.3f),
                )
            }
            is ShareUiState.Failed -> {
                Txt(shareFailureText(state.reason), size = 12.sp, color = Skerry.colors.sunset)
                GhostButton(stringResource(Res.string.share_session), onClick = onDismiss, icon = "close")
            }
        }
    }
}

/**
 * Teams this account can share a session with: active membership and the team key actually present
 * on this device (without it there is nothing to seal the frames with).
 */
@Composable
fun shareableTeams(): List<Pair<String, String>> {
    val teams = LocalTeams.current ?: return emptyList()
    val list by teams.teams.collectAsState()
    return list.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.map { it.id to it.name }
}

/** Avatars shown before the panel falls back to a plain count. */
private const val MAX_SHOWN_VIEWERS = 4

/**
 * The cast button on a pane that is *watching* a shared session: the panel says the view is
 * read-only and offers the one thing a viewer can do — ask the host for control.
 */
@Composable
private fun WatchedSessionButton(
    viewer: app.skerry.shared.share.SharedSessionViewer,
    hostAccount: String,
    requests: SharedFlow<Unit>?,
) {
    var open by remember(viewer) { mutableStateOf(false) }
    val granted by viewer.controlGranted.collectAsState()
    var asked by remember(viewer) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(requests) { requests?.collect { open = true } }
    Box {
        IconBtn(
            name = if (granted) "keyboard" else "visibility",
            tint = if (granted) Skerry.colors.cyanBright else Skerry.colors.dim,
            onClick = { open = !open },
            tooltip = stringResource(if (granted) Res.string.share_control_granted else Res.string.share_read_only),
        )
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                rememberModalPresence()
                Column(
                    Modifier.width(300.dp)
                        .background(Skerry.colors.surface2, RoundedCornerShape(9.dp))
                        .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(9.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InitialsAvatar(hostAccount, size = 24.dp)
                        Txt(stringResource(Res.string.share_watching, hostAccount), size = 12.5.sp)
                    }
                    if (granted) {
                        Txt(stringResource(Res.string.share_control_granted), size = 11.5.sp, color = Skerry.colors.cyanBright)
                    } else {
                        Txt(stringResource(Res.string.share_read_only), size = 11.5.sp, color = Skerry.colors.dim)
                        GhostButton(
                            stringResource(if (asked) Res.string.share_control_asked else Res.string.share_control_request),
                            onClick = {
                                asked = true
                                scope.launch { runCatching { viewer.requestControl() } }
                            },
                            icon = "front_hand",
                            fg = if (asked) Skerry.colors.dim else Skerry.colors.text,
                        )
                    }
                }
            }
        }
    }
}
