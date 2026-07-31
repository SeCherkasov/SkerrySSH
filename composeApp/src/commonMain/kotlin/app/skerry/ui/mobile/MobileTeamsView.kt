package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.host.rowSubtitle
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.inSection
import app.skerry.shared.host.VaultHostStore
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.generated.resources.lib_teams_scope_delete_message
import app.skerry.ui.generated.resources.lib_teams_scope_delete
import app.skerry.ui.generated.resources.lib_teams_scopes
import app.skerry.ui.generated.resources.lib_teams_sidebar
import androidx.compose.ui.text.style.TextOverflow
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.team.HOST_SHARE_STRIP
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.vault.RecordType
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.session.SessionStatus
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_accept
import app.skerry.ui.generated.resources.lib_teams_create
import app.skerry.ui.generated.resources.lib_teams_create_subtitle
import app.skerry.ui.generated.resources.lib_teams_create_title
import app.skerry.ui.generated.resources.lib_teams_name_placeholder
import app.skerry.ui.generated.resources.lib_teams_decline
import app.skerry.ui.generated.resources.lib_teams_delete
import app.skerry.ui.generated.resources.lib_teams_delete_message
import app.skerry.ui.generated.resources.lib_teams_empty_subtitle
import app.skerry.ui.generated.resources.lib_teams_empty_title
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_invited_banner
import app.skerry.ui.generated.resources.lib_teams_leave
import app.skerry.ui.generated.resources.lib_teams_leave_message
import app.skerry.ui.app.LocalSharedSessions
import app.skerry.ui.generated.resources.lib_teams_members
import app.skerry.ui.generated.resources.share_live_sessions
import app.skerry.ui.share.SharedSessionsList
import app.skerry.ui.share.rememberJoinSharedSession
import app.skerry.ui.generated.resources.lib_teams_members_count
import app.skerry.ui.generated.resources.lib_teams_need_sync
import app.skerry.ui.generated.resources.lib_teams_no_key
import app.skerry.ui.generated.resources.lib_teams_nothing_shared
import app.skerry.ui.generated.resources.lib_teams_remove_member_message
import app.skerry.ui.generated.resources.lib_teams_remove_member_title
import app.skerry.ui.generated.resources.lib_teams_history
import app.skerry.ui.generated.resources.lib_teams_share_empty
import app.skerry.ui.generated.resources.lib_teams_share_host
import app.skerry.ui.generated.resources.lib_teams_share_host_title
import app.skerry.ui.generated.resources.lib_teams_share_snippet
import app.skerry.ui.generated.resources.lib_teams_share_snippet_title
import app.skerry.ui.generated.resources.lib_teams_shared_hosts_count
import app.skerry.ui.generated.resources.lib_teams_shared_snippets_count
import app.skerry.ui.generated.resources.lib_teams_status_invited
import app.skerry.ui.generated.resources.lib_teams_sync_now
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_create
import app.skerry.ui.generated.resources.shell_route_team
import app.skerry.ui.teams.HistoryTarget
import app.skerry.ui.teams.TeamActivityDialog
import app.skerry.ui.teams.InviteMemberDialog
import app.skerry.ui.teams.RoleBadge
import app.skerry.ui.teams.RolePickerDialog
import app.skerry.ui.teams.canModifyMember
import app.skerry.ui.teams.roleBadgeColors
import app.skerry.ui.teams.teamRoleLabel
import app.skerry.ui.teams.InvitePreview
import app.skerry.ui.teams.ShareItem
import app.skerry.ui.teams.SharePickerDialog
import app.skerry.ui.teams.TeamScopeUi
import app.skerry.ui.teams.TeamUi
import app.skerry.ui.teams.CreateScopeDialog
import app.skerry.ui.teams.ScopeAccessDialog
import app.skerry.ui.teams.ScopeSection
import app.skerry.ui.teams.TeamsCoordinator
import app.skerry.ui.teams.teamsFailureText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/** Destructive Teams actions on mobile — same confirmations as desktop. */
private sealed interface MobileTeamsConfirm {
    data class Leave(val teamId: String) : MobileTeamsConfirm
    data class Delete(val teamId: String) : MobileTeamsConfirm
    data class Remove(val teamId: String, val accountId: String) : MobileTeamsConfirm
    data class DeleteScope(val teamId: String, val scopeId: String) : MobileTeamsConfirm
}

/**
 * Create team on the phone: the native centered dialog ([MobileCenteredDialog], parity with
 * "New group") rather than the desktop [app.skerry.ui.teams.CreateTeamDialog] card — it rides above
 * the keyboard (root safeDrawing) and lays out for a narrow screen. Single name field; the team key
 * is generated locally (the caption notes it).
 */
@Composable
private fun MobileCreateTeamDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    val submit = { if (canCreate) onCreate(name.trim()) }
    MobileCenteredDialog(onDismiss = onDismiss) {
        Txt(stringResource(Res.string.lib_teams_create_title), color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold)
        Txt(
            stringResource(Res.string.lib_teams_create_subtitle),
            color = Skerry.colors.dim,
            size = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        MobileFormInput(name, { name = it }, stringResource(Res.string.lib_teams_name_placeholder), imeAction = ImeAction.Done, onSubmit = submit)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.shell_cancel), color = Skerry.colors.dim, size = 15.sp, weight = FontWeight.Medium)
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canCreate) Skerry.colors.cyan else Skerry.colors.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.shell_create), color = Skerry.colors.ink, size = 15.sp, weight = FontWeight.Bold)
            }
        }
    }
}

/**
 * More → "Team" push-screen: parity with desktop TeamsView in a mobile idiom — horizontal team chips
 * instead of a sidebar, selected team's details stacked below. Dialogs (create/invite/share picker)
 * are shared with desktop ([CreateTeamDialog] etc.).
 */
@Composable
fun MobileTeamsScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        MobilePushHeader(stringResource(Res.string.shell_route_team), onBack = state::pop)
        val tc = LocalTeams.current
        if (tc == null) {
            Txt(
                stringResource(Res.string.lib_teams_need_sync),
                color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        } else {
            MobileTeamsBody(tc)
        }
    }
}

@Composable
private fun MobileTeamsBody(tc: TeamsCoordinator) {
    val scope = rememberCoroutineScope()
    val teams by tc.teams.collectAsState()
    val busy by tc.busy.collectAsState()
    val error by tc.lastError.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { tc.refresh(); tc.syncAll(); tick++ }

    var showCreate by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var invitePreview by remember { mutableStateOf<InvitePreview?>(null) }
    var sharePicker by remember { mutableStateOf<RecordType?>(null) }
    var confirm by remember { mutableStateOf<MobileTeamsConfirm?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    // One record's history instead of the team's whole feed (desktop parity).
    var historyRecord by remember { mutableStateOf<HistoryTarget?>(null) }
    var rolePicker by remember { mutableStateOf<TeamMember?>(null) }
    // Selected share space of the current team ("" = team-wide), like the desktop screen.
    var selectedScope by remember { mutableStateOf("") }
    var showCreateScope by remember { mutableStateOf(false) }
    var scopeAccess by remember { mutableStateOf<TeamScopeUi?>(null) }

    val selected = teams.firstOrNull { it.id == selectedId } ?: teams.firstOrNull()

    // Box, not a bare Column: the dialogs below are plain in-composition overlays (ModalScrim /
    // MobileCenteredDialog fill their parent), so they must be siblings in a full-size Box — as a
    // Column's trailing children they'd get whatever height the scroll body left (≈0) and collapse.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        error?.let { Txt(teamsFailureText(it), color = Skerry.colors.sunset, size = 11.5.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            teams.forEach { team ->
                MobileTeamChip(team, active = team.id == selected?.id) { selectedId = team.id }
            }
            GhostButton(stringResource(Res.string.lib_teams_create), onClick = { showCreate = true }, icon = "group_add")
        }
        when {
            selected == null && error == null && teams.isEmpty() -> {
                Txt(stringResource(Res.string.lib_teams_empty_title), color = Skerry.colors.text, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Txt(stringResource(Res.string.lib_teams_empty_subtitle), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
            selected != null -> MobileTeamDetail(
                tc = tc,
                team = selected,
                scopeId = selected.scopes.firstOrNull { it.id == selectedScope }?.id ?: "",
                tick = tick,
                busy = busy,
                onInvite = { showInvite = true; invitePreview = null },
                onShare = { sharePicker = it },
                onConfirm = { confirm = it },
                onAccept = { scope.launch { tc.accept(selected.id); tick++ } },
                onDecline = { scope.launch { tc.decline(selected.id); tick++ } },
                onSync = { scope.launch { tc.refresh(); tc.syncTeam(selected.id); tick++ } },
                onUnshare = { recordId ->
                    val ref = TeamScopeRef(selected.id, selected.scopes.firstOrNull { it.id == selectedScope }?.id ?: "")
                    scope.launch { tc.unshareRecord(ref, recordId); tick++ }
                },
                onShowHistory = { showHistory = true },
                onShowRecordHistory = { historyRecord = it },
                onChangeRole = { member -> rolePicker = member },
                onSelectScope = { selectedScope = it },
                onNewScope = { showCreateScope = true },
                onScopeAccess = { scopeAccess = it },
            )
        }
        Spacer(Modifier.height(40.dp))
    }

    val scopeTeam = selected
    if (showCreateScope && scopeTeam != null) {
        CreateScopeDialog(
            onDismiss = { showCreateScope = false },
            onCreate = { name -> showCreateScope = false; scope.launch { tc.createScope(scopeTeam.id, name); tick++ } },
        )
    }
    val accessScope = scopeAccess
    if (accessScope != null && scopeTeam != null) {
        val scopeMembers by produceState(emptyList<TeamMember>(), scopeTeam.id, tick) { value = tc.members(scopeTeam.id) }
        val granted by produceState(emptySet<String>(), scopeTeam.id, accessScope.id, tick) {
            value = tc.scopeGrants(scopeTeam.id, accessScope.id).toSet()
        }
        ScopeAccessDialog(
            scopeName = accessScope.name,
            members = scopeMembers.filter { it.status == TeamMemberStatus.ACTIVE },
            granted = granted,
            // Sealing the key to someone requires holding it, so a manager without a grant can
            // revoke but not hand out.
            canGrant = accessScope.hasKey,
            busy = busy,
            onGrant = { accountId -> scope.launch { tc.grantScope(scopeTeam.id, accessScope.id, accountId); tick++ } },
            onRevoke = { accountId -> scope.launch { tc.revokeScope(scopeTeam.id, accessScope.id, accountId); tick++ } },
            onDismiss = { scopeAccess = null },
        )
    }
    if (showCreate) {
        MobileCreateTeamDialog(
            onDismiss = { showCreate = false },
            onCreate = { name -> showCreate = false; scope.launch { tc.createTeam(name); tick++ } },
        )
    }
    val inviteTeam = selected
    if (showInvite && inviteTeam != null) {
        InviteMemberDialog(
            preview = invitePreview,
            ownFingerprint = tc.ownFingerprint(),
            busy = busy,
            assignableRoles = inviteTeam.role.assignableRoles(),
            onLookup = { accountId -> scope.launch { invitePreview = tc.previewInvite(accountId) } },
            onEdited = { invitePreview = null },
            onSend = { accountId, role -> showInvite = false; scope.launch { tc.invite(inviteTeam.id, accountId, role); tick++ } },
            onDismiss = { showInvite = false },
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
    val roleTeam = selected
    val roleTarget = rolePicker
    if (roleTarget != null && roleTeam != null) {
        RolePickerDialog(
            accountId = roleTarget.accountId,
            current = roleTarget.role,
            assignable = roleTeam.role.assignableRoles(),
            onPick = { newRole -> rolePicker = null; scope.launch { tc.changeRole(roleTeam.id, roleTarget.accountId, newRole); tick++ } },
            onDismiss = { rolePicker = null },
        )
    }
    val shareTeam = selected
    val shareKind = sharePicker
    if (shareKind != null && shareTeam != null) {
        val shareRef = TeamScopeRef(shareTeam.id, shareTeam.scopes.firstOrNull { it.id == selectedScope }?.id ?: "")
        MobileSharePicker(tc, shareRef, shareKind, tick, onDone = { sharePicker = null; tick++ }, onDismiss = { sharePicker = null })
    }
    confirm?.let { c ->
        val (title, message) = when (c) {
            is MobileTeamsConfirm.Leave -> stringResource(Res.string.lib_teams_leave) to stringResource(Res.string.lib_teams_leave_message)
            is MobileTeamsConfirm.Delete -> stringResource(Res.string.lib_teams_delete) to stringResource(Res.string.lib_teams_delete_message)
            is MobileTeamsConfirm.Remove -> stringResource(Res.string.lib_teams_remove_member_title) to stringResource(Res.string.lib_teams_remove_member_message, c.accountId)
            is MobileTeamsConfirm.DeleteScope -> stringResource(Res.string.lib_teams_scope_delete) to stringResource(Res.string.lib_teams_scope_delete_message)
        }
        ConfirmActionDialog(
            title = title,
            message = message,
            confirmLabel = title,
            onConfirm = {
                confirm = null
                scope.launch {
                    when (c) {
                        is MobileTeamsConfirm.Leave -> tc.leave(c.teamId)
                        is MobileTeamsConfirm.Delete -> tc.deleteTeam(c.teamId)
                        is MobileTeamsConfirm.Remove -> tc.removeMember(c.teamId, c.accountId)
                        is MobileTeamsConfirm.DeleteScope -> {
                            tc.deleteScope(c.teamId, c.scopeId)
                            selectedScope = ""
                        }
                    }
                    tick++
                }
            },
            onDismiss = { confirm = null },
        )
    }
    }
}

@Composable
private fun MobileSharePicker(
    tc: TeamsCoordinator,
    ref: TeamScopeRef,
    kind: RecordType,
    tick: Int,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hosts = LocalHosts.current
    val snippets = LocalSnippets.current
    // Across every space of the team, not just the selected one: a record lives in exactly one space.
    val sharedIds = remember(ref, kind, tick) { tc.sharedRecordIds(ref.teamId, kind) }
    val items = if (kind == RecordType.HOST) {
        (hosts?.hosts ?: emptyList()).filter { it.id !in sharedIds }.map { ShareItem(it.id, it.label, "${it.username}@${it.address}") }
    } else {
        (snippets?.snippets ?: emptyList()).filter { it.id !in sharedIds }.map { ShareItem(it.id, it.snippet.label, it.snippet.command) }
    }
    SharePickerDialog(
        title = if (kind == RecordType.HOST) stringResource(Res.string.lib_teams_share_host_title) else stringResource(Res.string.lib_teams_share_snippet_title),
        items = items,
        emptyText = stringResource(Res.string.lib_teams_share_empty),
        onPick = { item ->
            scope.launch {
                tc.shareRecord(ref, item.id, kind, if (kind == RecordType.HOST) HOST_SHARE_STRIP else emptySet())
                onDone()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun MobileTeamDetail(
    tc: TeamsCoordinator,
    team: TeamUi,
    scopeId: String,
    tick: Int,
    busy: Boolean,
    onInvite: () -> Unit,
    onShare: (RecordType) -> Unit,
    onConfirm: (MobileTeamsConfirm) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onSync: () -> Unit,
    onUnshare: (String) -> Unit,
    onShowHistory: () -> Unit,
    onShowRecordHistory: (HistoryTarget) -> Unit,
    onChangeRole: (TeamMember) -> Unit,
    onSelectScope: (String) -> Unit,
    onNewScope: () -> Unit,
    onScopeAccess: (TeamScopeUi) -> Unit,
) {
    val mono = LocalFonts.current.mono
    val invited = team.status == TeamMemberStatus.INVITED
    val owner = team.role == TeamRole.OWNER && !invited
    val canManage = team.role.canManageMembers && !invited
    val activeScope = team.scopes.firstOrNull { it.id == scopeId }
    val canWrite = team.role.canWrite && !invited && team.hasKey && (scopeId.isEmpty() || activeScope?.hasKey == true)
    val canAudit = team.role.canViewAudit && !invited
    val members by produceState(emptyList<TeamMember>(), team.id, team.memberCount, tick) {
        value = tc.members(team.id)
    }
    // Records of the selected space only; a scope we hold no key for has nothing readable to show.
    val spaceVault = if (!invited && team.hasKey && (scopeId.isEmpty() || activeScope?.hasKey == true)) {
        tc.spaceVault(TeamScopeRef(team.id, scopeId))
    } else {
        null
    }
    val sharedHosts = remember(team.id, scopeId, tick, spaceVault) { spaceVault?.let { VaultHostStore(it).all() } ?: emptyList() }
    val sharedSnippets = remember(team.id, scopeId, tick, spaceVault) { spaceVault?.let { VaultSnippetStore(it).all() } ?: emptyList() }

    Txt(team.name, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    Txt(stringResource(Res.string.lib_teams_members_count, team.memberCount), color = Skerry.colors.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))

    if (invited) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Skerry.colors.amber.copy(alpha = 0.08f))
                .border(1.dp, Skerry.colors.amber.copy(alpha = 0.25f), RoundedCornerShape(11.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("mail", size = 18.sp, color = Skerry.colors.amber)
                Txt(stringResource(Res.string.lib_teams_invited_banner), color = Skerry.colors.text, size = 12.5.sp)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(Res.string.lib_teams_accept), onClick = onAccept, enabled = !busy)
                GhostButton(stringResource(Res.string.lib_teams_decline), onClick = onDecline, fg = Skerry.colors.dim)
            }
        }
        return
    }
    if (!team.hasKey) {
        Txt(stringResource(Res.string.lib_teams_no_key), color = Skerry.colors.amber, size = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }

    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(stringResource(Res.string.lib_teams_sync_now), onClick = onSync, icon = "sync")
        if (canAudit) GhostButton(stringResource(Res.string.lib_teams_history), onClick = onShowHistory, icon = "history")
        if (canManage) PrimaryButton(stringResource(Res.string.lib_teams_invite), onClick = onInvite, icon = "person_add", enabled = !busy)
    }

    MobileTeamsSectionLabel(stringResource(Res.string.lib_teams_scopes))
    ScopeSection(
        scopes = team.scopes,
        selected = scopeId,
        canManage = canManage,
        onSelect = onSelectScope,
        onNew = onNewScope,
        onAccess = onScopeAccess,
        onDelete = { onConfirm(MobileTeamsConfirm.DeleteScope(team.id, it.id)) },
    )

    MobileTeamsSectionLabel(stringResource(Res.string.share_live_sessions))
    SharedSessionsList(team.id, LocalSharedSessions.current, onJoin = rememberJoinSharedSession(), modifier = Modifier.padding(bottom = 14.dp))
    MobileTeamsSectionLabel(stringResource(Res.string.lib_teams_members))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        members.forEach { m ->
            val modifiable = canManage && m.accountId != team.ownerAccountId && canModifyMember(team.role, m.role)
            MobileMemberRow(
                m,
                isOwnerRow = m.accountId == team.ownerAccountId,
                canManageMember = modifiable,
                onChangeRole = { onChangeRole(m) },
                onRemove = { onConfirm(MobileTeamsConfirm.Remove(team.id, m.accountId)) },
            )
        }
    }

    MobileTeamsSectionLabel(stringResource(Res.string.lib_teams_shared_hosts_count, sharedHosts.size))
    if (sharedHosts.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = Skerry.colors.faint, size = 11.5.sp)
    sharedHosts.forEach { host ->
        MobileSharedRow(
            host.label, host.rowSubtitle(),
            canUnshare = canWrite,
            onHistory = if (canAudit) { { onShowRecordHistory(HistoryTarget(host.id, host.label)) } } else null,
            onUnshare = { onUnshare(host.id) },
        )
    }
    if (canWrite) {
        GhostButton(stringResource(Res.string.lib_teams_share_host), onClick = { onShare(RecordType.HOST) }, icon = "add", modifier = Modifier.padding(top = 10.dp))
    }

    MobileTeamsSectionLabel(stringResource(Res.string.lib_teams_shared_snippets_count, sharedSnippets.size))
    if (sharedSnippets.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = Skerry.colors.faint, size = 11.5.sp)
    sharedSnippets.forEach { snippet ->
        MobileSharedRow(
            snippet.label, snippet.command,
            canUnshare = canWrite,
            onHistory = if (canAudit) { { onShowRecordHistory(HistoryTarget(snippet.id, snippet.label)) } } else null,
            onUnshare = { onUnshare(snippet.id) },
        )
    }
    if (canWrite) {
        GhostButton(stringResource(Res.string.lib_teams_share_snippet), onClick = { onShare(RecordType.SNIPPET) }, icon = "add", modifier = Modifier.padding(top = 10.dp))
    }

    Row(Modifier.padding(top = 24.dp)) {
        if (owner) {
            GhostButton(stringResource(Res.string.lib_teams_delete), onClick = { onConfirm(MobileTeamsConfirm.Delete(team.id)) }, icon = "delete", fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.4f))
        } else {
            GhostButton(stringResource(Res.string.lib_teams_leave), onClick = { onConfirm(MobileTeamsConfirm.Leave(team.id)) }, icon = "logout", fg = Skerry.colors.sunset, border = Skerry.colors.sunset.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun MobileTeamsSectionLabel(text: String) {
    Txt(text.uppercase(), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
}

@Composable
private fun MobileTeamChip(team: TeamUi, active: Boolean, onClick: () -> Unit) {
    val invited = team.status == TeamMemberStatus.INVITED
    val fg = when {
        active -> Skerry.colors.cyanBright
        invited -> Skerry.colors.amber
        else -> Skerry.colors.dim
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) Skerry.colors.cyan10 else Color.Transparent)
            .border(1.dp, if (active) Skerry.colors.cyan14 else Skerry.colors.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Sym(if (invited) "mail" else "group", size = 15.sp, color = fg)
        Txt(team.name, color = fg, size = 12.5.sp)
    }
}

@Composable
private fun MobileMemberRow(
    m: TeamMember,
    isOwnerRow: Boolean,
    canManageMember: Boolean,
    onChangeRole: () -> Unit,
    onRemove: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    val invited = m.status == TeamMemberStatus.INVITED
    val (roleFg, roleBg) = roleBadgeColors(m.role)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (isOwnerRow) Skerry.colors.cyan else Skerry.colors.moss), contentAlignment = Alignment.Center) {
            Txt(m.accountId.take(2).uppercase(), color = Skerry.colors.ink, size = 11.5.sp, weight = FontWeight.SemiBold)
        }
        Txt(m.accountId, color = Skerry.colors.text, size = 12.5.sp, font = mono, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (invited) {
            RoleBadge(stringResource(Res.string.lib_teams_status_invited), Skerry.colors.cyanBright, Skerry.colors.cyan.copy(alpha = 0.12f))
        }
        val badgeModifier = if (canManageMember) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onChangeRole) else Modifier
        RoleBadge(teamRoleLabel(m.role), roleFg, roleBg, modifier = badgeModifier)
        if (canManageMember) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(4.dp)) {
                Sym("close", size = 15.sp, color = Skerry.colors.faint)
            }
        }
    }
}

/**
 * Team shared-host sections for the mobile Hosts list — parity with the desktop sidebar: one section
 * per active team with a key and a non-empty host set in its team vault. Reread is keyed to
 * [hostsSnapshot] (reloadManagers after a team sync yields a new personal-catalog list). Tap connects
 * via [LocalConnectHost].
 */
@Composable
internal fun MobileTeamHostsSections(hostsSnapshot: List<Host>, section: HostSection) {
    val teams = LocalTeams.current ?: return
    val mono = LocalFonts.current.mono
    val connect = LocalConnectHost.current
    val teamList by teams.teams.collectAsState()
    // revision changes on every team sync — otherwise hosts live-pulled into the team vault wouldn't
    // appear until a manual sync (the personal catalog is unchanged and sections read the vault imperatively).
    val revision by teams.revision.collectAsState()
    val sections = remember(teamList, hostsSnapshot, revision, section) {
        teamList.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.flatMap { team ->
            // One group per share space: the team itself plus every scope whose key we hold.
            val spaces = listOf(TeamScopeRef(team.id) to team.name) +
                team.scopes.filter { it.hasKey }.map { TeamScopeRef(team.id, it.id) to "${team.name} · ${it.name}" }
            spaces.mapNotNull { (ref, label) ->
                val vault = teams.spaceVault(ref) ?: return@mapNotNull null
                // Split by section like the personal catalog (desktop parity).
                val shared = VaultHostStore(vault).all().inSection(section)
                if (shared.isEmpty()) null else label to shared
            }
        }
    }
    if (sections.isEmpty()) return
    // The "TEAMS" super-header separates shared hosts from the personal catalog; below it, each team
    // is a folder-level section (header like MobileFolderHeader) with hosts as cards like the personal
    // catalog (MobileHostRow), so Teams match the list's visual language.
    Txt(
        stringResource(Res.string.lib_teams_sidebar),
        color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 2.dp),
    )
    sections.forEach { (name, shared) ->
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Sym("group", size = 15.sp, color = Skerry.colors.cyanBright)
            Txt(
                name.uppercase(),
                color = Skerry.colors.faint, size = 12.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Txt(shared.size.toString(), color = Skerry.colors.faint, size = 11.sp)
        }
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            shared.forEach { host ->
                key("team-${host.id}") { MobileTeamHostRow(host, mono, onClick = { connect(host) }) }
            }
        }
    }
}

/**
 * A team shared-host row — a card modeled on the personal [MobileHostRow] (panel, border,
 * `user@address` monospaced, status dot) but with a `group` icon instead of `dns` to mark its
 * team-vault origin. Tap connects via [LocalConnectHost].
 */
@Composable
private fun MobileTeamHostRow(host: Host, mono: androidx.compose.ui.text.font.FontFamily, onClick: () -> Unit) {
    val dotColor = sessionDotColor(LocalSessions.current?.sessionStatusFor(host.id) ?: SessionStatus.Idle)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Skerry.colors.cyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym("group", size = 21.sp, color = Skerry.colors.cyanBright)
        }
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Txt(host.rowSubtitle(), color = Skerry.colors.dim, size = 11.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
    }
}

@Composable
private fun MobileSharedRow(
    label: String,
    detail: String,
    canUnshare: Boolean,
    onHistory: (() -> Unit)? = null,
    onUnshare: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Txt(label, color = Skerry.colors.textBright, size = 12.5.sp, font = mono)
        Txt(detail, color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.weight(1f))
        // "What happened to this one" — only for readers who may see the audit log at all.
        if (onHistory != null) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onHistory).padding(3.dp)) {
                Sym("history", size = 14.sp, color = Skerry.colors.faint)
            }
        }
        if (canUnshare) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onUnshare).padding(3.dp)) {
                Sym("close", size = 14.sp, color = Skerry.colors.faint)
            }
        }
    }
}
