package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.shared.host.VaultHostStore
import app.skerry.ui.app.LocalConnectHost
import app.skerry.ui.generated.resources.lib_teams_sidebar
import androidx.compose.ui.text.style.TextOverflow
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.team.HOST_SHARE_STRIP
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.vault.RecordType
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.session.sessionDotColor
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_accept
import app.skerry.ui.generated.resources.lib_teams_create
import app.skerry.ui.generated.resources.lib_teams_decline
import app.skerry.ui.generated.resources.lib_teams_delete
import app.skerry.ui.generated.resources.lib_teams_delete_message
import app.skerry.ui.generated.resources.lib_teams_empty_subtitle
import app.skerry.ui.generated.resources.lib_teams_empty_title
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_invited_banner
import app.skerry.ui.generated.resources.lib_teams_leave
import app.skerry.ui.generated.resources.lib_teams_leave_message
import app.skerry.ui.generated.resources.lib_teams_members
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
import app.skerry.ui.generated.resources.lib_teams_shared_hosts
import app.skerry.ui.generated.resources.lib_teams_shared_snippets
import app.skerry.ui.generated.resources.lib_teams_status_invited
import app.skerry.ui.generated.resources.lib_teams_sync_now
import app.skerry.ui.generated.resources.shell_route_team
import app.skerry.ui.teams.AuditLogDialog
import app.skerry.ui.teams.CreateTeamDialog
import app.skerry.ui.teams.InviteMemberDialog
import app.skerry.ui.teams.RoleBadge
import app.skerry.ui.teams.RolePickerDialog
import app.skerry.ui.teams.canModifyMember
import app.skerry.ui.teams.roleBadgeColors
import app.skerry.ui.teams.teamRoleLabel
import app.skerry.ui.teams.InvitePreview
import app.skerry.ui.teams.ShareItem
import app.skerry.ui.teams.SharePickerDialog
import app.skerry.ui.teams.TeamUi
import app.skerry.ui.teams.TeamsCoordinator
import app.skerry.ui.teams.teamsFailureText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Destructive Teams actions on mobile — same confirmations as desktop. */
private sealed interface MobileTeamsConfirm {
    data class Leave(val teamId: String) : MobileTeamsConfirm
    data class Delete(val teamId: String) : MobileTeamsConfirm
    data class Remove(val teamId: String, val accountId: String) : MobileTeamsConfirm
}

/**
 * More → "Team" push-screen: parity with desktop TeamsView in a mobile idiom — horizontal team chips
 * instead of a sidebar, selected team's details stacked below. Dialogs (create/invite/share picker)
 * are shared with desktop ([CreateTeamDialog] etc.).
 */
@Composable
fun MobileTeamsScreen(state: MobileDesignState) {
    Column(Modifier.fillMaxSize().background(D.bg)) {
        MobilePushHeader(stringResource(Res.string.shell_route_team), onBack = state::pop)
        val tc = LocalTeams.current
        if (tc == null) {
            Txt(
                stringResource(Res.string.lib_teams_need_sync),
                color = D.dim, size = 12.5.sp, lineHeight = 18.sp,
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
    var rolePicker by remember { mutableStateOf<TeamMember?>(null) }

    val selected = teams.firstOrNull { it.id == selectedId } ?: teams.firstOrNull()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        error?.let { Txt(teamsFailureText(it), color = D.sunset, size = 11.5.sp, modifier = Modifier.padding(vertical = 6.dp)) }
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
                Txt(stringResource(Res.string.lib_teams_empty_title), color = D.text, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Txt(stringResource(Res.string.lib_teams_empty_subtitle), color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
            selected != null -> MobileTeamDetail(
                tc = tc,
                team = selected,
                tick = tick,
                busy = busy,
                onInvite = { showInvite = true; invitePreview = null },
                onShare = { sharePicker = it },
                onConfirm = { confirm = it },
                onAccept = { scope.launch { tc.accept(selected.id); tick++ } },
                onDecline = { scope.launch { tc.decline(selected.id); tick++ } },
                onSync = { scope.launch { tc.refresh(); tc.syncTeam(selected.id); tick++ } },
                onUnshare = { recordId -> scope.launch { tc.unshareRecord(selected.id, recordId); tick++ } },
                onShowHistory = { showHistory = true },
                onChangeRole = { member -> rolePicker = member },
            )
        }
        Spacer(Modifier.height(40.dp))
    }

    if (showCreate) {
        CreateTeamDialog(
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
    if (showHistory && historyTeam != null) {
        val entries by produceState(emptyList<TeamActivityEntry>(), historyTeam.id, tick) {
            value = tc.teamActivity(historyTeam.id)
        }
        AuditLogDialog(entries, onDismiss = { showHistory = false })
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
        MobileSharePicker(tc, shareTeam.id, shareKind, tick, onDone = { sharePicker = null; tick++ }, onDismiss = { sharePicker = null })
    }
    confirm?.let { c ->
        val (title, message) = when (c) {
            is MobileTeamsConfirm.Leave -> stringResource(Res.string.lib_teams_leave) to stringResource(Res.string.lib_teams_leave_message)
            is MobileTeamsConfirm.Delete -> stringResource(Res.string.lib_teams_delete) to stringResource(Res.string.lib_teams_delete_message)
            is MobileTeamsConfirm.Remove -> stringResource(Res.string.lib_teams_remove_member_title) to stringResource(Res.string.lib_teams_remove_member_message, c.accountId)
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
                    }
                    tick++
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun MobileSharePicker(
    tc: TeamsCoordinator,
    teamId: String,
    kind: RecordType,
    tick: Int,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hosts = LocalHosts.current
    val snippets = LocalSnippets.current
    val sharedIds = remember(teamId, kind, tick) {
        val vault = tc.teamVault(teamId)
        when {
            vault == null -> emptySet()
            kind == RecordType.HOST -> VaultHostStore(vault).all().map { it.id }.toSet()
            else -> VaultSnippetStore(vault).all().map { it.id }.toSet()
        }
    }
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
                tc.shareRecord(teamId, item.id, kind, if (kind == RecordType.HOST) HOST_SHARE_STRIP else emptySet())
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
    onChangeRole: (TeamMember) -> Unit,
) {
    val mono = LocalFonts.current.mono
    val invited = team.status == TeamMemberStatus.INVITED
    val owner = team.role == TeamRole.OWNER && !invited
    val canManage = team.role.canManageMembers && !invited
    val canWrite = team.role.canWrite && !invited && team.hasKey
    val canAudit = team.role.canViewAudit && !invited
    val members by produceState(emptyList<TeamMember>(), team.id, team.memberCount, tick) {
        value = tc.members(team.id)
    }
    val teamVault = if (!invited && team.hasKey) tc.teamVault(team.id) else null
    val sharedHosts = remember(team.id, tick, teamVault) { teamVault?.let { VaultHostStore(it).all() } ?: emptyList() }
    val sharedSnippets = remember(team.id, tick, teamVault) { teamVault?.let { VaultSnippetStore(it).all() } ?: emptyList() }

    Txt(team.name, color = D.text, size = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    Txt(stringResource(Res.string.lib_teams_members_count, team.memberCount), color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))

    if (invited) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(D.amber.copy(alpha = 0.08f))
                .border(1.dp, D.amber.copy(alpha = 0.25f), RoundedCornerShape(11.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sym("mail", size = 18.sp, color = D.amber)
                Txt(stringResource(Res.string.lib_teams_invited_banner), color = D.text, size = 12.5.sp)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(Res.string.lib_teams_accept), onClick = onAccept, enabled = !busy)
                GhostButton(stringResource(Res.string.lib_teams_decline), onClick = onDecline, fg = D.dim)
            }
        }
        return
    }
    if (!team.hasKey) {
        Txt(stringResource(Res.string.lib_teams_no_key), color = D.amber, size = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }

    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(stringResource(Res.string.lib_teams_sync_now), onClick = onSync, icon = "sync")
        if (canAudit) GhostButton(stringResource(Res.string.lib_teams_history), onClick = onShowHistory, icon = "history")
        if (canManage) PrimaryButton(stringResource(Res.string.lib_teams_invite), onClick = onInvite, icon = "person_add", enabled = !busy)
    }

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

    MobileTeamsSectionLabel("${stringResource(Res.string.lib_teams_shared_hosts)} · ${sharedHosts.size}")
    if (sharedHosts.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = D.faint, size = 11.5.sp)
    sharedHosts.forEach { host ->
        MobileSharedRow(host.label, "${host.username}@${host.address}", canUnshare = canWrite) { onUnshare(host.id) }
    }
    if (canWrite) {
        GhostButton(stringResource(Res.string.lib_teams_share_host), onClick = { onShare(RecordType.HOST) }, icon = "add", modifier = Modifier.padding(top = 10.dp))
    }

    MobileTeamsSectionLabel("${stringResource(Res.string.lib_teams_shared_snippets)} · ${sharedSnippets.size}")
    if (sharedSnippets.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = D.faint, size = 11.5.sp)
    sharedSnippets.forEach { snippet ->
        MobileSharedRow(snippet.label, snippet.command, canUnshare = canWrite) { onUnshare(snippet.id) }
    }
    if (canWrite) {
        GhostButton(stringResource(Res.string.lib_teams_share_snippet), onClick = { onShare(RecordType.SNIPPET) }, icon = "add", modifier = Modifier.padding(top = 10.dp))
    }

    Row(Modifier.padding(top = 24.dp)) {
        if (owner) {
            GhostButton(stringResource(Res.string.lib_teams_delete), onClick = { onConfirm(MobileTeamsConfirm.Delete(team.id)) }, icon = "delete", fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
        } else {
            GhostButton(stringResource(Res.string.lib_teams_leave), onClick = { onConfirm(MobileTeamsConfirm.Leave(team.id)) }, icon = "logout", fg = D.sunset, border = D.sunset.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun MobileTeamsSectionLabel(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
}

@Composable
private fun MobileTeamChip(team: TeamUi, active: Boolean, onClick: () -> Unit) {
    val invited = team.status == TeamMemberStatus.INVITED
    val fg = when {
        active -> D.cyanBright
        invited -> D.amber
        else -> D.dim
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) D.cyan10 else Color.Transparent)
            .border(1.dp, if (active) D.cyan14 else D.line, RoundedCornerShape(18.dp))
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (isOwnerRow) D.cyan else D.moss), contentAlignment = Alignment.Center) {
            Txt(m.accountId.take(2).uppercase(), color = Color(0xFF0A1A26), size = 11.5.sp, weight = FontWeight.SemiBold)
        }
        Txt(m.accountId, color = D.text, size = 12.5.sp, font = mono, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (invited) {
            RoleBadge(stringResource(Res.string.lib_teams_status_invited), D.cyanBright, D.cyan.copy(alpha = 0.12f))
        }
        val badgeModifier = if (canManageMember) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onChangeRole) else Modifier
        RoleBadge(teamRoleLabel(m.role), roleFg, roleBg, modifier = badgeModifier)
        if (canManageMember) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(4.dp)) {
                Sym("close", size = 15.sp, color = D.faint)
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
internal fun MobileTeamHostsSections(hostsSnapshot: List<Host>) {
    val teams = LocalTeams.current ?: return
    val mono = LocalFonts.current.mono
    val connect = LocalConnectHost.current
    val teamList by teams.teams.collectAsState()
    // revision changes on every team sync — otherwise hosts live-pulled into the team vault wouldn't
    // appear until a manual sync (the personal catalog is unchanged and sections read the vault imperatively).
    val revision by teams.revision.collectAsState()
    val sections = remember(teamList, hostsSnapshot, revision) {
        teamList.filter { it.status == TeamMemberStatus.ACTIVE && it.hasKey }.mapNotNull { team ->
            val vault = teams.teamVault(team.id) ?: return@mapNotNull null
            val shared = VaultHostStore(vault).all()
            if (shared.isEmpty()) null else team.name to shared
        }
    }
    if (sections.isEmpty()) return
    // The "TEAMS" super-header separates shared hosts from the personal catalog; below it, each team
    // is a folder-level section (header like MobileFolderHeader) with hosts as cards like the personal
    // catalog (MobileHostRow), so Teams match the list's visual language.
    Txt(
        stringResource(Res.string.lib_teams_sidebar),
        color = D.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 2.dp),
    )
    sections.forEach { (name, shared) ->
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Sym("group", size = 15.sp, color = D.cyanBright)
            Txt(
                name.uppercase(),
                color = D.faint, size = 12.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Txt(shared.size.toString(), color = D.faint, size = 11.sp)
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
    val dotColor = sessionDotColor(LocalSessions.current?.statusFor(host.id))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(D.card)
            .border(1.dp, D.cyan08, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(D.cyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Sym("group", size = 21.sp, color = D.cyanBright)
        }
        Column(Modifier.weight(1f)) {
            Txt(host.label, color = D.text, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Txt("${host.username}@${host.address}", color = D.dim, size = 11.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
    }
}

@Composable
private fun MobileSharedRow(label: String, detail: String, canUnshare: Boolean, onUnshare: () -> Unit) {
    val mono = LocalFonts.current.mono
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Txt(label, color = D.textBright, size = 12.5.sp, font = mono)
        Txt(detail, color = D.faint, size = 11.sp, modifier = Modifier.weight(1f))
        if (canUnshare) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onUnshare).padding(3.dp)) {
                Sym("close", size = 14.sp, color = D.faint)
            }
        }
    }
}
