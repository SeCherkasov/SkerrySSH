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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.team.HOST_SHARE_STRIP
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.vault.RecordType
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.HLine
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
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
import app.skerry.ui.generated.resources.lib_teams_err_forbidden
import app.skerry.ui.generated.resources.lib_teams_err_key_missing
import app.skerry.ui.generated.resources.lib_teams_err_network
import app.skerry.ui.generated.resources.lib_teams_err_no_recipient_key
import app.skerry.ui.generated.resources.lib_teams_err_no_such_account
import app.skerry.ui.generated.resources.lib_teams_err_not_connected
import app.skerry.ui.generated.resources.lib_teams_err_protocol
import app.skerry.ui.generated.resources.lib_teams_err_vault_locked
import app.skerry.ui.generated.resources.lib_teams_err_vault_unreadable
import app.skerry.ui.generated.resources.lib_teams_history
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_invited_banner
import app.skerry.ui.generated.resources.lib_teams_invited_by
import app.skerry.ui.generated.resources.lib_teams_invited_fingerprint
import app.skerry.ui.generated.resources.lib_teams_invite_unverified
import app.skerry.ui.generated.resources.lib_teams_leave
import app.skerry.ui.generated.resources.lib_teams_leave_message
import app.skerry.ui.generated.resources.lib_teams_members
import app.skerry.ui.generated.resources.lib_teams_members_count
import app.skerry.ui.generated.resources.lib_teams_need_sync
import app.skerry.ui.generated.resources.lib_teams_no_key
import app.skerry.ui.generated.resources.lib_teams_nothing_shared
import app.skerry.ui.generated.resources.lib_teams_remove_member_message
import app.skerry.ui.generated.resources.lib_teams_remove_member_title
import app.skerry.ui.generated.resources.lib_teams_share_empty
import app.skerry.ui.generated.resources.lib_teams_share_host
import app.skerry.ui.generated.resources.lib_teams_share_host_title
import app.skerry.ui.generated.resources.lib_teams_share_snippet
import app.skerry.ui.generated.resources.lib_teams_share_snippet_title
import app.skerry.ui.generated.resources.lib_teams_shared_hosts
import app.skerry.ui.generated.resources.lib_teams_shared_snippets
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.generated.resources.lib_teams_status_invited
import app.skerry.ui.generated.resources.lib_teams_sync_now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Teams: E2E sharing of hosts/snippets. Live data from [LocalTeams]; null — mock preview. */
@Composable
fun TeamsView() {
    val coordinator = LocalTeams.current
    if (coordinator == null) TeamsMockView() else TeamsLiveView(coordinator)
}

/** Destructive Teams actions requiring [ConfirmActionDialog]. */
private sealed interface TeamsConfirm {
    data class Leave(val teamId: String) : TeamsConfirm
    data class Delete(val teamId: String) : TeamsConfirm
    data class Remove(val teamId: String, val accountId: String) : TeamsConfirm
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
    var rolePicker by remember { mutableStateOf<TeamMember?>(null) }

    val selected = teams.firstOrNull { it.id == selectedId } ?: teams.firstOrNull()
    fun afterOp() {
        tick++
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(222.dp).fillMaxHeight().background(D.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            Txt(stringResource(Res.string.lib_teams_sidebar), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            teams.forEach { team ->
                LiveTeamRow(team, active = team.id == selected?.id) { selectedId = team.id }
            }
            Spacer(Modifier.weight(1f))
            error?.let { Txt(teamsFailureText(it), color = D.sunset, size = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) }
            GhostButton(stringResource(Res.string.lib_teams_create), onClick = { showCreate = true }, icon = "group_add", modifier = Modifier.fillMaxWidth())
        }
        VLine(D.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(D.bg).verticalScroll(rememberScrollState())) {
            when {
                selected == null && error == TeamsFailure.NotConnected -> TeamsEmptyState(stringResource(Res.string.lib_teams_need_sync))
                selected == null -> TeamsEmptyState(stringResource(Res.string.lib_teams_empty_subtitle))
                else -> TeamDetail(
                    tc = tc,
                    team = selected,
                    tick = tick,
                    busy = busy,
                    onInvite = { showInvite = true; invitePreview = null },
                    onShare = { sharePicker = it },
                    onConfirm = { confirm = it },
                    onAccept = { scope.launch2 { tc.accept(selected.id); afterOp() } },
                    onDecline = { scope.launch2 { tc.decline(selected.id); afterOp() } },
                    onSync = { scope.launch2 { tc.refresh(); tc.syncTeam(selected.id); afterOp() } },
                    onUnshare = { recordId -> scope.launch2 { tc.unshareRecord(selected.id, recordId); afterOp() } },
                    onShowHistory = { showHistory = true },
                    onChangeRole = { member -> rolePicker = member },
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
        SharePicker(tc, shareTeam.id, shareKind, tick, onDone = { sharePicker = null; afterOp() }, onDismiss = { sharePicker = null })
    }
    confirm?.let { c ->
        val (title, message) = when (c) {
            is TeamsConfirm.Leave -> stringResource(Res.string.lib_teams_leave) to stringResource(Res.string.lib_teams_leave_message)
            is TeamsConfirm.Delete -> stringResource(Res.string.lib_teams_delete) to stringResource(Res.string.lib_teams_delete_message)
            is TeamsConfirm.Remove -> stringResource(Res.string.lib_teams_remove_member_title) to stringResource(Res.string.lib_teams_remove_member_message, c.accountId)
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
                    }
                    afterOp()
                }
            },
            onDismiss = { confirm = null },
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
            onPick = { newRole -> rolePicker = null; scope.launch2 { tc.changeRole(roleTeam.id, roleTarget.accountId, newRole); afterOp() } },
            onDismiss = { rolePicker = null },
        )
    }
}

/** "Share a record" picker: own hosts/snippets minus those already shared with the team. */
@Composable
private fun SharePicker(
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
            scope.launch2 {
                tc.shareRecord(teamId, item.id, kind, if (kind == RecordType.HOST) HOST_SHARE_STRIP else emptySet())
                onDone()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun TeamDetail(
    tc: TeamsCoordinator,
    team: TeamUi,
    tick: Int,
    busy: Boolean,
    onInvite: () -> Unit,
    onShare: (RecordType) -> Unit,
    onConfirm: (TeamsConfirm) -> Unit,
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

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Txt(team.name, color = D.text, size = 16.sp, weight = FontWeight.SemiBold)
            Txt(stringResource(Res.string.lib_teams_members_count, team.memberCount), color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!invited) GhostButton(stringResource(Res.string.lib_teams_sync_now), onClick = onSync, icon = "sync")
            if (canAudit) GhostButton(stringResource(Res.string.lib_teams_history), onClick = onShowHistory, icon = "history")
            if (canManage) PrimaryButton(stringResource(Res.string.lib_teams_invite), onClick = onInvite, icon = "person_add", enabled = !busy)
            if (owner) {
                GhostButton(stringResource(Res.string.lib_teams_delete), onClick = { onConfirm(TeamsConfirm.Delete(team.id)) }, icon = "delete", fg = D.sunset)
            } else if (!invited) {
                GhostButton(stringResource(Res.string.lib_teams_leave), onClick = { onConfirm(TeamsConfirm.Leave(team.id)) }, icon = "logout", fg = D.sunset)
            }
        }
    }
    HLine()
    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        if (invited) {
            // Verify the inviter's identity (signature + fingerprint) before offering Accept. Null =
            // the invite couldn't be authenticated (forged/tampered) — warn and don't reveal a fingerprint.
            val acceptPreview by produceState<InvitePreview?>(null, team.id) { value = tc.acceptPreview(team.id) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(D.amber.copy(alpha = 0.08f))
                    .border(1.dp, D.amber.copy(alpha = 0.25f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Sym("mail", size = 18.sp, color = D.amber)
                    Txt(stringResource(Res.string.lib_teams_invited_banner), color = D.text, size = 12.5.sp, modifier = Modifier.weight(1f))
                    GhostButton(stringResource(Res.string.lib_teams_decline), onClick = onDecline, fg = D.dim)
                    PrimaryButton(stringResource(Res.string.lib_teams_accept), onClick = onAccept, enabled = !busy)
                }
                acceptPreview.let { p ->
                    if (p == null) {
                        Txt(stringResource(Res.string.lib_teams_invite_unverified), color = D.sunset, size = 11.5.sp)
                    } else {
                        Txt(stringResource(Res.string.lib_teams_invited_by, p.accountId), color = D.dim, size = 11.5.sp)
                        Txt(stringResource(Res.string.lib_teams_invited_fingerprint, p.fingerprint), color = D.cyanBright, size = 11.5.sp, font = mono)
                    }
                }
            }
            Box(Modifier.padding(top = 24.dp))
        } else if (!team.hasKey) {
            Txt(stringResource(Res.string.lib_teams_no_key), color = D.amber, size = 12.sp, modifier = Modifier.padding(bottom = 16.dp))
        }
        LiveSectionLabel(stringResource(Res.string.lib_teams_members))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            members.forEach { m ->
                val modifiable = canManage && m.accountId != team.ownerAccountId && canModifyMember(team.role, m.role)
                LiveMemberRow(
                    m,
                    isOwnerRow = m.accountId == team.ownerAccountId,
                    canManageMember = modifiable,
                    onChangeRole = { onChangeRole(m) },
                    onRemove = { onConfirm(TeamsConfirm.Remove(team.id, m.accountId)) },
                )
            }
        }
        if (!invited) {
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    LiveSectionLabel("${stringResource(Res.string.lib_teams_shared_hosts)} · ${sharedHosts.size}")
                    if (sharedHosts.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = D.faint, size = 11.5.sp)
                    sharedHosts.forEach { host ->
                        SharedRecordRow(host.label, "${host.username}@${host.address}", mono, canUnshare = canWrite) { onUnshare(host.id) }
                    }
                    if (canWrite) {
                        GhostButton(stringResource(Res.string.lib_teams_share_host), onClick = { onShare(RecordType.HOST) }, icon = "add", modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    LiveSectionLabel("${stringResource(Res.string.lib_teams_shared_snippets)} · ${sharedSnippets.size}")
                    if (sharedSnippets.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = D.faint, size = 11.5.sp)
                    sharedSnippets.forEach { snippet ->
                        SharedRecordRow(snippet.label, snippet.command, mono, canUnshare = canWrite) { onUnshare(snippet.id) }
                    }
                    if (canWrite) {
                        GhostButton(stringResource(Res.string.lib_teams_share_snippet), onClick = { onShare(RecordType.SNIPPET) }, icon = "add", modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamsEmptyState(subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Sym("groups", size = 34.sp, color = D.faint)
        Txt(stringResource(Res.string.lib_teams_empty_title), color = D.text, size = 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Txt(subtitle, color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun LiveTeamRow(team: TeamUi, active: Boolean, onClick: () -> Unit) {
    val invited = team.status == TeamMemberStatus.INVITED
    val fg = when {
        active -> D.cyanBright
        invited -> D.amber
        else -> D.dim
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (active) D.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(if (invited) "mail" else "group", size = 16.sp, color = fg)
        Txt(team.name, color = fg, size = 12.5.sp)
    }
}

@Composable
private fun LiveSectionLabel(text: String) {
    Txt(text.uppercase(), color = D.faint, size = 11.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun LiveMemberRow(
    m: TeamMember,
    isOwnerRow: Boolean,
    canManageMember: Boolean,
    onChangeRole: () -> Unit,
    onRemove: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    val initials = m.accountId.take(2).uppercase()
    val invited = m.status == TeamMemberStatus.INVITED
    val (roleFg, roleBg) = roleBadgeColors(m.role)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, D.cyan08, RoundedCornerShape(9.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (isOwnerRow) D.cyan else D.moss), contentAlignment = Alignment.Center) {
            Txt(initials, color = Color(0xFF0A1A26), size = 13.sp, weight = FontWeight.SemiBold)
        }
        Txt(m.accountId, color = D.text, size = 13.sp, font = mono, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (invited) {
            RoleBadge(stringResource(Res.string.lib_teams_status_invited), D.cyanBright, D.cyan.copy(alpha = 0.12f))
        }
        // Clicking the role badge changes it (owner/admin within anti-escalation limits).
        val badgeModifier = if (canManageMember) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onChangeRole) else Modifier
        RoleBadge(teamRoleLabel(m.role), roleFg, roleBg, modifier = badgeModifier)
        if (canManageMember) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(4.dp)) {
                Sym("close", size = 15.sp, color = D.faint)
            }
        }
    }
}

@Composable
private fun SharedRecordRow(label: String, detail: String, mono: androidx.compose.ui.text.font.FontFamily, canUnshare: Boolean, onUnshare: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Txt(label, color = D.textBright, size = 12.sp, font = mono)
        Txt(detail, color = D.faint, size = 10.5.sp, modifier = Modifier.weight(1f))
        if (canUnshare) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onUnshare).padding(3.dp)) {
                Sym("close", size = 14.sp, color = D.faint)
            }
        }
    }
}

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
}

/** launch from click handlers: a param-less suspend block, shorter than a lambda with CoroutineScope. */
private fun CoroutineScope.launch2(block: suspend () -> Unit) {
    launch { block() }
}
