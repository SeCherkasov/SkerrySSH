package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.InitialsAvatar
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_nothing_shared
import app.skerry.ui.generated.resources.lib_teams_status_invited
import app.skerry.ui.teams.HistoryTarget
import app.skerry.ui.teams.RoleBadge
import app.skerry.ui.teams.SharedRecordUi
import app.skerry.ui.teams.TeamMemberRowUi
import app.skerry.ui.teams.TeamUi
import app.skerry.ui.teams.UNKNOWN_VALUE
import app.skerry.ui.teams.lastSeenText
import app.skerry.ui.teams.roleBadgeColors
import app.skerry.ui.teams.teamRoleLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.generated.resources.shell_tip_remove
import app.skerry.ui.generated.resources.lib_teams_history

// Rows and sections of the phone's Team screen, split out of MobileTeamsView so that file stays
// about state and dialogs rather than about pixels.

/**
 * One kind of shared record on the phone: heading with its count, the rows with their unshare and
 * history actions, and the way to share another. Hosts, snippets and runbooks differ only in the
 * three strings and the record type, so they share this.
 */
@Composable
internal fun MobileSharedSection(
    heading: String,
    items: List<SharedRecordUi>,
    shareLabel: String?,
    onShare: () -> Unit,
    canUnshare: Boolean,
    canAudit: Boolean,
    onUnshare: (String) -> Unit,
    onShowRecordHistory: (HistoryTarget) -> Unit,
) {
    MobileTeamsSectionLabel(heading)
    if (items.isEmpty()) Txt(stringResource(Res.string.lib_teams_nothing_shared), color = Skerry.colors.faint, size = 11.5.sp)
    items.forEach { item ->
        MobileSharedRow(
            item.label, item.detail,
            canUnshare = canUnshare,
            onHistory = if (canAudit) { { onShowRecordHistory(HistoryTarget(item.id, item.label)) } } else null,
            onUnshare = { onUnshare(item.id) },
        )
    }
    if (shareLabel != null) {
        GhostButton(shareLabel, onClick = onShare, icon = "add", modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
internal fun MobileTeamsSectionLabel(text: String) {
    Txt(text.uppercase(), color = Skerry.colors.faint, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
}

@Composable
internal fun MobileTeamChip(team: TeamUi, active: Boolean, onClick: () -> Unit) {
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

/**
 * A member on the phone: the desktop table's four columns folded into a card — who they are, what
 * they may do, the scopes they hold, and when they were last around.
 */
@Composable
internal fun MobileMemberRow(
    row: TeamMemberRowUi,
    now: Long,
    onChangeRole: () -> Unit,
    onRemove: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    val m = row.member
    val invited = m.status == TeamMemberStatus.INVITED
    val (roleFg, roleBg) = roleBadgeColors(m.role)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InitialsAvatar(m.accountId, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Txt(m.accountId, color = Skerry.colors.text, size = 12.5.sp, font = mono, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Same rule as the desktop table: an access list we failed to read is "?", never an
            // empty chip row — the phone is where granting and revoking actually happens.
            val tags = row.scopes.joinToString(" ") { "#$it" }
            // A grant list that didn't load leaves a "?" — whether or not other scopes did load.
            val scopes = listOf(tags, if (row.scopesKnown) "" else UNKNOWN_VALUE).filter { it.isNotEmpty() }.joinToString(" ")
            Txt(
                listOf(lastSeenText(m.lastSeenAt, now), scopes).filter { it.isNotEmpty() }.joinToString(" · "),
                color = if (row.scopesKnown) Skerry.colors.faint else Skerry.colors.amber,
                size = 10.5.sp, font = mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (invited) {
            RoleBadge(stringResource(Res.string.lib_teams_status_invited), Skerry.colors.amber, Skerry.colors.amber.copy(alpha = 0.14f))
        } else {
            val badgeModifier = if (row.manageable) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onChangeRole) else Modifier
            RoleBadge(teamRoleLabel(m.role), roleFg, roleBg, modifier = badgeModifier)
        }
        if (row.manageable) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(4.dp)) {
                Sym("close", contentDescription = stringResource(Res.string.shell_tip_remove), size = 15.sp, color = Skerry.colors.faint)
            }
        }
    }
}

@Composable
internal fun MobileSharedRow(
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
                Sym("history", contentDescription = stringResource(Res.string.lib_teams_history), size = 14.sp, color = Skerry.colors.faint)
            }
        }
        if (canUnshare) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onUnshare).padding(3.dp)) {
                Sym("close", contentDescription = stringResource(Res.string.shell_tip_remove), size = 14.sp, color = Skerry.colors.faint)
            }
        }
    }
}
