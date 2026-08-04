package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.runbook.VaultRunbookStore
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.team.HOST_SHARE_STRIP
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.vault.RecordType
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.DropdownMenuColumn
import app.skerry.ui.design.EmptyState
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_accept
import app.skerry.ui.generated.resources.lib_teams_create
import app.skerry.ui.generated.resources.lib_teams_decline
import app.skerry.ui.generated.resources.lib_teams_delete
import app.skerry.ui.generated.resources.lib_teams_delete_message
import app.skerry.ui.generated.resources.lib_teams_empty_subtitle
import app.skerry.ui.generated.resources.lib_teams_empty_title
import app.skerry.ui.generated.resources.lib_teams_err_already_invited
import app.skerry.ui.generated.resources.lib_teams_err_already_shared
import app.skerry.ui.generated.resources.lib_teams_err_forbidden
import app.skerry.ui.generated.resources.lib_teams_err_key_missing
import app.skerry.ui.generated.resources.lib_teams_err_network
import app.skerry.ui.generated.resources.lib_teams_err_no_recipient_key
import app.skerry.ui.generated.resources.lib_teams_err_no_such_account
import app.skerry.ui.generated.resources.lib_teams_err_not_connected
import app.skerry.ui.generated.resources.lib_teams_err_protocol
import app.skerry.ui.generated.resources.lib_teams_err_scopes_unsupported
import app.skerry.ui.generated.resources.lib_teams_err_server_error
import app.skerry.ui.generated.resources.lib_teams_err_too_many_requests
import app.skerry.ui.generated.resources.lib_teams_err_vault_locked
import app.skerry.ui.generated.resources.lib_teams_err_vault_unreadable
import app.skerry.ui.generated.resources.lib_teams_header_title
import app.skerry.ui.generated.resources.lib_teams_header_subtitle
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_invite_unverified
import app.skerry.ui.generated.resources.lib_teams_invited_banner
import app.skerry.ui.generated.resources.lib_teams_invited_by
import app.skerry.ui.generated.resources.lib_teams_invited_fingerprint
import app.skerry.ui.generated.resources.lib_teams_leave
import app.skerry.ui.generated.resources.lib_teams_leave_message
import app.skerry.ui.generated.resources.lib_teams_need_sync
import app.skerry.ui.generated.resources.lib_teams_no_key
import app.skerry.ui.generated.resources.lib_teams_remove_member_message
import app.skerry.ui.generated.resources.lib_teams_remove_member_title
import app.skerry.ui.generated.resources.lib_teams_scope_delete
import app.skerry.ui.generated.resources.lib_teams_scope_delete_message
import app.skerry.ui.generated.resources.lib_teams_scopes
import app.skerry.ui.generated.resources.lib_teams_share_host
import app.skerry.ui.generated.resources.lib_teams_share_host_title
import app.skerry.ui.generated.resources.lib_teams_share_runbook
import app.skerry.ui.generated.resources.lib_teams_share_runbook_title
import app.skerry.ui.generated.resources.lib_teams_share_snippet
import app.skerry.ui.generated.resources.lib_teams_share_snippet_title
import app.skerry.ui.generated.resources.lib_teams_share_empty
import app.skerry.ui.generated.resources.lib_teams_shared_hosts_count
import app.skerry.ui.generated.resources.lib_teams_shared_runbooks_count
import app.skerry.ui.generated.resources.lib_teams_shared_snippets_count
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.generated.resources.lib_teams_synced_never
import app.skerry.ui.host.rowSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/** Teams: E2E sharing of hosts/snippets/runbooks. Live data from [LocalTeams]; null — mock preview. */
@Composable
fun TeamsView() {
    val coordinator = LocalTeams.current
    if (coordinator == null) TeamsMockView() else TeamsLiveView(coordinator)
}

/** Destructive Teams actions requiring [ConfirmActionDialog]. */
internal sealed interface TeamsConfirm {
    data class Leave(val teamId: String) : TeamsConfirm
    data class Delete(val teamId: String) : TeamsConfirm
    data class Remove(val teamId: String, val accountId: String) : TeamsConfirm
    data class DeleteScope(val teamId: String, val scopeId: String) : TeamsConfirm
}

@Composable
private fun TeamsLiveView(tc: TeamsCoordinator) {
    val scope = rememberCoroutineScope()
    val teams by tc.teams.collectAsState()
    val busy by tc.busy.collectAsState()
    val error by tc.lastError.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Reread counter for team-vault stores: incremented after each operation/sync.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { tc.refresh(); tc.syncAll(); tick++ }

    var showCreate by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var invitePreview by remember { mutableStateOf<InvitePreview?>(null) }
    var sharePicker by remember { mutableStateOf<RecordType?>(null) }
    var confirm by remember { mutableStateOf<TeamsConfirm?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    // Set to look at one record's history instead of the team's whole feed ("who touched this host").
    var historyRecord by remember { mutableStateOf<HistoryTarget?>(null) }
    var rolePicker by remember { mutableStateOf<TeamMember?>(null) }
    // Selected share space of the current team ("" = team-wide). Kept per team so switching teams
    // doesn't land on a scope id that belongs to another one.
    var selectedScope by remember { mutableStateOf("") }
    var showCreateScope by remember { mutableStateOf(false) }
    var scopeAccess by remember { mutableStateOf<TeamScopeUi?>(null) }
    // Which "Shared with the team" list is open, if any.
    var sharedView by remember { mutableStateOf<TeamSharedView?>(null) }

    val selected = teams.firstOrNull { it.id == selectedId } ?: teams.firstOrNull()
    // A scope that vanished (revoked, deleted, or simply another team's) falls back to team-wide.
    val activeScope = selected?.scopes?.firstOrNull { it.id == selectedScope }
    val scopeId = activeScope?.id ?: ""
    fun afterOp() {
        tick++
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            SidebarSectionTitle(stringResource(Res.string.lib_teams_sidebar), modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            teams.forEach { team ->
                LiveTeamRow(team, active = team.id == selected?.id) { selectedId = team.id }
            }
            Spacer(Modifier.weight(1f))
            error?.let { Txt(teamsFailureText(it), color = Skerry.colors.sunset, size = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
            PrimaryButton(stringResource(Res.string.lib_teams_create), onClick = { showCreate = true }, icon = "group_add", modifier = Modifier.fillMaxWidth())
        }
        VLine(Skerry.colors.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(Skerry.colors.bg)) {
            when {
                selected == null && error == TeamsFailure.NotConnected -> TeamsEmptyState(stringResource(Res.string.lib_teams_need_sync))
                selected == null -> TeamsEmptyState(stringResource(Res.string.lib_teams_empty_subtitle))
                else -> TeamScreen(
                    tc = tc,
                    team = selected,
                    scopeId = scopeId,
                    tick = tick,
                    busy = busy,
                    onInvite = { showInvite = true; invitePreview = null },
                    onConfirm = { confirm = it },
                    onAccept = { scope.launch2 { tc.accept(selected.id); afterOp() } },
                    onDecline = { scope.launch2 { tc.decline(selected.id); afterOp() } },
                    onSync = { scope.launch2 { tc.refresh(); tc.syncTeam(selected.id); afterOp() } },
                    onShowHistory = { showHistory = true },
                    onChangeRole = { member -> rolePicker = member },
                    onSelectScope = { selectedScope = it },
                    onNewScope = { showCreateScope = true },
                    onScopeAccess = { scopeAccess = it },
                    onOpenShared = { sharedView = it },
                )
            }
        }
    }

    if (showCreate) {
        CreateTeamDialog(
            onDismiss = { showCreate = false },
            onCreate = { name -> showCreate = false; scope.launch2 { tc.createTeam(name); afterOp() } },
        )
    }
    val inviteTeam = selected
    if (showInvite && inviteTeam != null) {
        InviteMemberDialog(
            preview = invitePreview,
            ownFingerprint = tc.ownFingerprint(),
            busy = busy,
            assignableRoles = inviteTeam.role.assignableRoles(),
            onLookup = { accountId -> scope.launch2 { invitePreview = tc.previewInvite(accountId) } },
            onEdited = { invitePreview = null },
            onSend = { accountId, role -> showInvite = false; scope.launch2 { tc.invite(inviteTeam.id, accountId, role); afterOp() } },
            onDismiss = { showInvite = false },
        )
    }
    val shareTeam = selected
    val shareKind = sharePicker
    if (shareKind != null && shareTeam != null) {
        SharePicker(tc, TeamScopeRef(shareTeam.id, scopeId), shareKind, tick, onDone = { sharePicker = null; afterOp() }, onDismiss = { sharePicker = null })
    }
    val sharedTeam = selected
    val openShared = sharedView
    if (openShared != null && sharedTeam != null) {
        SharedRecordsView(
            tc = tc,
            team = sharedTeam,
            scopeId = scopeId,
            view = openShared,
            tick = tick,
            onShare = { sharePicker = it },
            onUnshare = { recordId -> scope.launch2 { tc.unshareRecord(TeamScopeRef(sharedTeam.id, scopeId), recordId); afterOp() } },
            onRecordHistory = { historyRecord = it },
            onDismiss = { sharedView = null },
        )
    }
    confirm?.let { c ->
        val (title, message) = when (c) {
            is TeamsConfirm.Leave -> stringResource(Res.string.lib_teams_leave) to stringResource(Res.string.lib_teams_leave_message)
            is TeamsConfirm.Delete -> stringResource(Res.string.lib_teams_delete) to stringResource(Res.string.lib_teams_delete_message)
            is TeamsConfirm.Remove -> stringResource(Res.string.lib_teams_remove_member_title) to stringResource(Res.string.lib_teams_remove_member_message, c.accountId)
            is TeamsConfirm.DeleteScope -> stringResource(Res.string.lib_teams_scope_delete) to stringResource(Res.string.lib_teams_scope_delete_message)
        }
        ConfirmActionDialog(
            title = title,
            message = message,
            confirmLabel = title,
            onConfirm = {
                confirm = null
                scope.launch2 {
                    when (c) {
                        is TeamsConfirm.Leave -> tc.leave(c.teamId)
                        is TeamsConfirm.Delete -> tc.deleteTeam(c.teamId)
                        is TeamsConfirm.Remove -> tc.removeMember(c.teamId, c.accountId)
                        is TeamsConfirm.DeleteScope -> {
                            tc.deleteScope(c.teamId, c.scopeId)
                            selectedScope = ""
                        }
                    }
                    afterOp()
                }
            },
            onDismiss = { confirm = null },
        )
    }
    val historyTeam = selected
    val recordFocus = historyRecord
    if ((showHistory || recordFocus != null) && historyTeam != null) {
        val entries by produceState(emptyList<TeamActivityEntry>(), historyTeam.id, tick) {
            value = tc.teamActivity(historyTeam.id)
        }
        // Record names come from our own copy of each share space — the server holds only ids.
        // Fetched like the entries above rather than in a bare remember{}: resolving them decrypts
        // every shared record of the team, which has no business blocking a frame.
        val recordNames by produceState(emptyMap<String, Map<String, String>>(), historyTeam.id, tick) {
            value = withContext(Dispatchers.Default) { tc.sharedRecordNames(historyTeam.id) }
        }
        val scopeNames = remember(historyTeam.scopes) { historyTeam.scopes.associate { it.id to it.name } }
        TeamActivityDialog(
            entries = entries,
            selfAccountId = tc.selfAccountId(),
            recordNames = recordNames,
            scopeNames = scopeNames,
            focusRecordId = recordFocus?.recordId,
            focusRecordLabel = recordFocus?.label,
            onDismiss = { showHistory = false; historyRecord = null },
        )
    }
    val scopeTeam = selected
    if (showCreateScope && scopeTeam != null) {
        CreateScopeDialog(
            onDismiss = { showCreateScope = false },
            onCreate = { name -> showCreateScope = false; scope.launch2 { tc.createScope(scopeTeam.id, name); afterOp() } },
        )
    }
    val accessScope = scopeAccess
    if (accessScope != null && scopeTeam != null) {
        val members by produceState(emptyList<TeamMember>(), scopeTeam.id, tick) { value = tc.members(scopeTeam.id) }
        val granted by produceState<ScopeAccess>(ScopeAccess.Loading, scopeTeam.id, accessScope.id, tick) {
            value = tc.scopeGrants(scopeTeam.id, accessScope.id)
                ?.let { ScopeAccess.Known(it.toSet()) } ?: ScopeAccess.Unavailable
        }
        ScopeAccessDialog(
            scopeName = accessScope.name,
            members = members.filter { it.status == TeamMemberStatus.ACTIVE },
            granted = granted,
            // Sealing the scope key to someone requires holding it: a manager without a grant can
            // see and revoke, but has nothing to hand out.
            canGrant = accessScope.hasKey,
            busy = busy,
            onGrant = { accountId -> scope.launch2 { tc.grantScope(scopeTeam.id, accessScope.id, accountId); afterOp() } },
            onRevoke = { accountId -> scope.launch2 { tc.revokeScope(scopeTeam.id, accessScope.id, accountId); afterOp() } },
            onDismiss = { scopeAccess = null },
        )
    }
    val roleTeam = selected
    val roleTarget = rolePicker
    if (roleTarget != null && roleTeam != null) {
        RolePickerDialog(
            accountId = roleTarget.accountId,
            current = roleTarget.role,
            assignable = roleTeam.role.assignableRoles(),
            onPick = { newRole -> rolePicker = null; scope.launch2 { tc.changeRole(roleTeam.id, roleTarget.accountId, newRole); afterOp() } },
            onDismiss = { rolePicker = null },
        )
    }
}

@Composable
private fun TeamsEmptyState(subtitle: String) {
    EmptyState(icon = "groups", title = stringResource(Res.string.lib_teams_empty_title), subtitle = subtitle)
}

@Composable
private fun LiveTeamRow(team: TeamUi, active: Boolean, onClick: () -> Unit) {
    val invited = team.status == TeamMemberStatus.INVITED
    val fg = when {
        active -> Skerry.colors.cyanBright
        invited -> Skerry.colors.amber
        else -> Skerry.colors.dim
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (active) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(if (invited) "mail" else "group", size = 16.sp, color = fg)
        Txt(team.name, color = fg, size = 12.5.sp)
    }
}

/** A record to show the history of: its id, plus the name to put in the dialog's title. */
internal data class HistoryTarget(val recordId: String, val label: String)

/** Text for a typed Teams error (analogous to syncFailureText). */
@Composable
internal fun teamsFailureText(f: TeamsFailure): String = when (f) {
    TeamsFailure.NotConnected -> stringResource(Res.string.lib_teams_err_not_connected)
    TeamsFailure.VaultLocked -> stringResource(Res.string.lib_teams_err_vault_locked)
    TeamsFailure.NoRecipientKey -> stringResource(Res.string.lib_teams_err_no_recipient_key)
    TeamsFailure.AlreadyInvited -> stringResource(Res.string.lib_teams_err_already_invited)
    TeamsFailure.NoSuchAccount -> stringResource(Res.string.lib_teams_err_no_such_account)
    TeamsFailure.KeyMissing -> stringResource(Res.string.lib_teams_err_key_missing)
    TeamsFailure.Network -> stringResource(Res.string.lib_teams_err_network)
    TeamsFailure.Protocol -> stringResource(Res.string.lib_teams_err_protocol)
    TeamsFailure.Forbidden -> stringResource(Res.string.lib_teams_err_forbidden)
    TeamsFailure.VaultUnreadable -> stringResource(Res.string.lib_teams_err_vault_unreadable)
    TeamsFailure.TooManyRequests -> stringResource(Res.string.lib_teams_err_too_many_requests)
    TeamsFailure.ServerError -> stringResource(Res.string.lib_teams_err_server_error)
    TeamsFailure.AlreadyShared -> stringResource(Res.string.lib_teams_err_already_shared)
    TeamsFailure.ScopesUnsupported -> stringResource(Res.string.lib_teams_err_scopes_unsupported)
}

/** launch from click handlers: a param-less suspend block, shorter than a lambda with CoroutineScope. */
internal fun CoroutineScope.launch2(block: suspend () -> Unit) {
    launch { block() }
}
