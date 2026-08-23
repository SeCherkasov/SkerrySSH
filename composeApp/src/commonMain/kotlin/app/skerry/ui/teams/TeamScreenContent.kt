package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.VaultHostStore
import app.skerry.shared.runbook.VaultRunbookStore
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.team.shareStripFields
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.vault.RecordType
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalRunbooks
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.DropdownMenuColumn
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.boundedVisibleText
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_accept
import app.skerry.ui.generated.resources.lib_teams_decline
import app.skerry.ui.generated.resources.lib_teams_delete
import app.skerry.ui.generated.resources.lib_teams_header_subtitle
import app.skerry.ui.generated.resources.lib_teams_header_title
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_invite_unverified
import app.skerry.ui.generated.resources.lib_teams_invited_banner
import app.skerry.ui.generated.resources.lib_teams_invited_by
import app.skerry.ui.generated.resources.lib_teams_invited_fingerprint
import app.skerry.ui.generated.resources.lib_teams_leave
import app.skerry.ui.generated.resources.lib_teams_no_key
import app.skerry.ui.generated.resources.lib_teams_scopes
import app.skerry.ui.generated.resources.lib_teams_share_empty
import app.skerry.ui.generated.resources.lib_teams_share_host
import app.skerry.ui.generated.resources.lib_teams_share_host_title
import app.skerry.ui.generated.resources.lib_teams_share_runbook
import app.skerry.ui.generated.resources.lib_teams_share_runbook_title
import app.skerry.ui.generated.resources.lib_teams_share_snippet
import app.skerry.ui.generated.resources.lib_teams_share_snippet_title
import app.skerry.ui.generated.resources.lib_teams_shared_hosts_count
import app.skerry.ui.generated.resources.lib_teams_shared_runbooks_count
import app.skerry.ui.generated.resources.lib_teams_shared_snippets_count
import app.skerry.ui.generated.resources.lib_teams_synced_never
import app.skerry.ui.host.rowSubtitle
import app.skerry.ui.host.rowLabel
import app.skerry.ui.theme.Skerry
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** How often the freshness pill re-reads the clock; a minute-grained label needs no finer tick. */
private const val PILL_TICK_MS = 10_000L

// The team's own screen: header, share-space strip, member table, summary cards and the lists they
// open. TeamsView.kt keeps the state and the dialogs; everything that draws one team lives here.

/**
 * One team: the header with its freshness and its actions, the member table with the share spaces
 * above it, the summary cards, and — for owner/admin — the activity column beside them.
 */
@Composable
internal fun TeamScreen(
    tc: TeamsCoordinator,
    team: TeamUi,
    scopeId: String,
    tick: Int,
    busy: Boolean,
    onInvite: () -> Unit,
    onConfirm: (TeamsConfirm) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onSync: () -> Unit,
    onShowHistory: () -> Unit,
    onChangeRole: (TeamMember) -> Unit,
    onSelectScope: (String) -> Unit,
    onNewScope: () -> Unit,
    onScopeAccess: (TeamScopeUi) -> Unit,
    onOpenShared: (TeamSharedView) -> Unit,
) {
    val invited = team.status == TeamMemberStatus.INVITED
    val owner = team.role == TeamRole.OWNER && !invited
    val canManage = team.role.canManageMembers && !invited
    val canAudit = team.role.canViewAudit && !invited
    val syncStamps by tc.lastSyncedAt.collectAsState()
    val members by produceState(emptyList<TeamMember>(), team.id, team.memberCount, tick) {
        value = tc.members(team.id)
    }
    val grants = scopeGrants(tc, team, tick, canManage)
    val feed = teamFeed(tc, team, tick, canAudit)

    TeamHeader(
        team = team,
        lastSyncedAt = syncStamps[team.id],
        busy = busy,
        owner = owner,
        canManage = canManage,
        invited = invited,
        onSync = onSync,
        onInvite = onInvite,
        onLeave = { onConfirm(TeamsConfirm.Leave(team.id)) },
        onDelete = { onConfirm(TeamsConfirm.Delete(team.id)) },
    )
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (invited) {
                InviteBanner(tc, team, busy, onAccept = onAccept, onDecline = onDecline)
                return@Column
            }
            if (!team.hasKey) {
                Txt(
                    stringResource(Res.string.lib_teams_no_key),
                    color = Skerry.colors.amber, size = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            ScopeStrip(team, scopeId, canManage, onSelectScope, onNewScope, onScopeAccess, onDeleteScope = { onConfirm(TeamsConfirm.DeleteScope(team.id, it.id)) })
            val rows = remember(team, members, grants, canManage) {
                teamMemberRows(team, members, grants?.byScope.orEmpty(), canManage, grants?.complete ?: true)
            }
            // Read once per reread rather than per row, so every row of one paint agrees on "now".
            val now = remember(tick, members) { epochMillis() }
            TeamMemberTable(
                rows = rows,
                now = now,
                scopesLoading = canManage && grants == null,
                onChangeRole = { onChangeRole(it.member) },
                onRemove = { onConfirm(TeamsConfirm.Remove(team.id, it.member.accountId)) },
            )
            TeamSummaryCards(
                cards = teamCards(tc, team, scopeId, tick, feed),
                onOpen = onOpenShared,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            )
        }
        if (canAudit) TeamActivityPanel(feed, onOpenFull = onShowHistory)
    }
}

/** Header strip: what team this is, how fresh it is, and the actions on the team as a whole. */
@Composable
private fun TeamHeader(
    team: TeamUi,
    lastSyncedAt: Long?,
    busy: Boolean,
    owner: Boolean,
    canManage: Boolean,
    invited: Boolean,
    onSync: () -> Unit,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionHeader(
        title = stringResource(Res.string.lib_teams_header_title),
        subtitle = stringResource(Res.string.lib_teams_header_subtitle, untrustedLabel(team.name), team.memberCount),
        actions = {
            if (!invited) SyncPill(lastSyncedAt, onSync)
            if (canManage) PrimaryButton(stringResource(Res.string.lib_teams_invite), onClick = onInvite, icon = "person_add", enabled = !busy)
            if (!invited) TeamOverflowMenu(owner = owner, onLeave = onLeave, onDelete = onDelete)
        },
    )
}

/** "synced 12 s ago" — and the way to sync now, since the two are the same question. */
@Composable
internal fun SyncPill(lastSyncedAt: Long?, onSync: () -> Unit) {
    // The pill's whole job is freshness, so it re-derives on a timer: without it the label would
    // freeze at "synced 0 s ago" for as long as nothing else recomposes the screen.
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(lastSyncedAt) {
        // Nothing to age while no sync has finished — the pill reads "not synced yet" either way.
        while (lastSyncedAt != null) {
            delay(PILL_TICK_MS)
            now = epochMillis()
        }
    }
    val ago = lastSyncedAt?.let { syncedAgoText((now - it).coerceAtLeast(0)) }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Skerry.colors.moss.copy(alpha = 0.10f))
            .border(1.dp, Skerry.colors.moss.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable(onClick = onSync)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym(if (ago == null) "cloud_off" else "cloud_done", size = 15.sp, color = Skerry.colors.moss)
        Txt(
            ago ?: stringResource(Res.string.lib_teams_synced_never),
            color = Skerry.colors.moss, size = 11.sp, weight = FontWeight.Medium,
        )
    }
}

/** Leave/Delete — rare and destructive, so they sit behind the header's overflow rather than in it. */
@Composable
private fun TeamOverflowMenu(owner: Boolean, onLeave: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = {
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).clickable { open = !open }.padding(6.dp),
            ) {
                Sym("more_vert", size = 18.sp, color = Skerry.colors.dim)
            }
        },
        menu = {
            DropdownMenuColumn(width = 190.dp) {
                val label = if (owner) stringResource(Res.string.lib_teams_delete) else stringResource(Res.string.lib_teams_leave)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = false; if (owner) onDelete() else onLeave() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Sym(if (owner) "delete" else "logout", size = 16.sp, color = Skerry.colors.sunset)
                    Txt(label, color = Skerry.colors.sunset, size = 12.5.sp)
                }
            }
        },
    )
}

/** Share spaces of the team, with the manager actions on the selected one. */
@Composable
private fun ScopeStrip(
    team: TeamUi,
    scopeId: String,
    canManage: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onAccess: (TeamScopeUi) -> Unit,
    onDeleteScope: (TeamScopeUi) -> Unit,
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
        Txt(
            stringResource(Res.string.lib_teams_scopes).uppercase(),
            color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        ScopeSection(
            scopes = team.scopes,
            selected = scopeId,
            canManage = canManage,
            onSelect = onSelect,
            onNew = onNew,
            onAccess = onAccess,
            onDelete = onDeleteScope,
        )
    }
}

/** The invite this account hasn't answered yet: who sent it, their fingerprint, accept or decline. */
@Composable
private fun InviteBanner(tc: TeamsCoordinator, team: TeamUi, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val mono = LocalFonts.current.mono
    // Verify the inviter's identity (signature + fingerprint) before offering Accept. Null = the
    // invite couldn't be authenticated (forged/tampered) — warn and don't reveal a fingerprint.
    val acceptPreview by produceState<InvitePreview?>(null, team.id) { value = tc.acceptPreview(team.id) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Skerry.colors.amber.copy(alpha = 0.08f))
            .border(1.dp, Skerry.colors.amber.copy(alpha = 0.25f), RoundedCornerShape(9.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym("mail", size = 18.sp, color = Skerry.colors.amber)
            Txt(stringResource(Res.string.lib_teams_invited_banner), color = Skerry.colors.text, size = 12.5.sp, modifier = Modifier.weight(1f))
            GhostButton(stringResource(Res.string.lib_teams_decline), onClick = onDecline, fg = Skerry.colors.dim)
            PrimaryButton(stringResource(Res.string.lib_teams_accept), onClick = onAccept, enabled = !busy)
        }
        acceptPreview.let { p ->
            if (p == null) {
                Txt(stringResource(Res.string.lib_teams_invite_unverified), color = Skerry.colors.sunset, size = 11.5.sp)
            } else {
                Txt(stringResource(Res.string.lib_teams_invited_by, untrustedLabel(p.accountId)), color = Skerry.colors.dim, size = 11.5.sp)
                Txt(stringResource(Res.string.lib_teams_invited_fingerprint, p.fingerprint), color = Skerry.colors.cyanBright, size = 11.5.sp, font = mono)
            }
        }
    }
}

/** The list behind a "Shared with the team" row: records of one kind, or the live session directory. */
@Composable
internal fun SharedRecordsView(
    tc: TeamsCoordinator,
    team: TeamUi,
    scopeId: String,
    view: TeamSharedView,
    tick: Int,
    onShare: (RecordType) -> Unit,
    onUnshare: (String) -> Unit,
    onRecordHistory: (HistoryTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    if (view == TeamSharedView.LIVE) {
        TeamLiveSessionsDialog(team.id, onDismiss)
        return
    }
    val invited = team.status == TeamMemberStatus.INVITED
    val activeScope = team.scopes.firstOrNull { it.id == scopeId }
    val readable = !invited && team.hasKey && (scopeId.isEmpty() || activeScope?.hasKey == true)
    val canWrite = team.role.canWrite && readable
    val canAudit = team.role.canViewAudit && !invited
    val spaceVault = if (readable) tc.spaceVault(TeamScopeRef(team.id, scopeId)) else null
    // Decrypting the space is the expensive part, so it stays in remember; the second line of a
    // runbook row is localized text and has to be built in the composition.
    val runbooks = remember(team.id, scopeId, tick, spaceVault, view) {
        if (view == TeamSharedView.RUNBOOKS) spaceVault?.let { VaultRunbookStore(it).all() }.orEmpty() else emptyList()
    }
    val records = remember(team.id, scopeId, tick, spaceVault, view) {
        when (view) {
            TeamSharedView.HOSTS -> spaceVault?.let { vault ->
                VaultHostStore(vault).all().map { SharedRecordUi(it.id, it.rowLabel(), it.rowSubtitle()) }
            }
            TeamSharedView.SNIPPETS -> spaceVault?.let { vault ->
                VaultSnippetStore(vault).all().map { SharedRecordUi(it.id, untrustedLabel(it.label), boundedVisibleText(it.command)) }
            }
            else -> null
        } ?: emptyList()
    }
    val items = if (view == TeamSharedView.RUNBOOKS) {
        // Not inside the remember above: the second line is a localized string built in the
        // composition, so the row is rebuilt on recomposition either way.
        runbooks.map { SharedRecordUi(it.id, untrustedLabel(it.label), runbookSummary(it.steps.size)) }
    } else {
        records
    }
    val kind = when (view) {
        TeamSharedView.HOSTS -> RecordType.HOST
        TeamSharedView.SNIPPETS -> RecordType.SNIPPET
        else -> RecordType.RUNBOOK
    }
    TeamSharedRecordsDialog(
        title = when (view) {
            TeamSharedView.HOSTS -> stringResource(Res.string.lib_teams_shared_hosts_count, items.size)
            TeamSharedView.SNIPPETS -> stringResource(Res.string.lib_teams_shared_snippets_count, items.size)
            else -> stringResource(Res.string.lib_teams_shared_runbooks_count, items.size)
        },
        items = items,
        shareLabel = if (canWrite) {
            when (view) {
                TeamSharedView.HOSTS -> stringResource(Res.string.lib_teams_share_host)
                TeamSharedView.SNIPPETS -> stringResource(Res.string.lib_teams_share_snippet)
                else -> stringResource(Res.string.lib_teams_share_runbook)
            }
        } else {
            null
        },
        onShare = { onShare(kind) },
        onUnshare = if (canWrite) {
            { item -> onUnshare(item.id) }
        } else {
            null
        },
        onHistory = if (canAudit) {
            { item -> onRecordHistory(HistoryTarget(item.id, item.label)) }
        } else {
            null
        },
        onDismiss = onDismiss,
    )
}

/**
 * "Share a record" picker: own hosts/snippets/runbooks minus those already shared with the team.
 * Shared with the phone screen — a record type offered on one platform and not the other would be
 * the kind of drift two copies of this function produced before.
 */
@Composable
internal fun SharePicker(
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
    val runbooks = LocalRunbooks.current
    // Across every space of the team, not just the selected one: a record lives in exactly one space.
    val sharedIds = remember(ref, kind, tick) { tc.sharedRecordIds(ref.teamId, kind) }
    val items = when (kind) {
        RecordType.HOST ->
            (hosts?.hosts ?: emptyList()).filter { it.id !in sharedIds }.map { ShareItem(it.id, it.rowLabel(), it.rowSubtitle()) }
        RecordType.SNIPPET ->
            (snippets?.snippets ?: emptyList()).filter { it.id !in sharedIds }.map { ShareItem(it.id, it.snippet.label, it.snippet.command) }
        else ->
            (runbooks?.runbooks ?: emptyList()).filter { it.id !in sharedIds }
                .map { ShareItem(it.id, it.runbook.label, runbookSummary(it.runbook.steps.size)) }
    }
    SharePickerDialog(
        title = when (kind) {
            RecordType.HOST -> stringResource(Res.string.lib_teams_share_host_title)
            RecordType.SNIPPET -> stringResource(Res.string.lib_teams_share_snippet_title)
            else -> stringResource(Res.string.lib_teams_share_runbook_title)
        },
        items = items,
        emptyText = stringResource(Res.string.lib_teams_share_empty),
        onPick = { item ->
            scope.launch2 {
                tc.shareRecord(ref, item.id, kind, shareStripFields(kind))
                onDone()
            }
        },
        onDismiss = onDismiss,
    )
}
