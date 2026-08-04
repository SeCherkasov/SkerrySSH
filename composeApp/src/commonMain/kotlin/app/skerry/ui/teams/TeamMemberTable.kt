package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.ui.design.HLine
import app.skerry.ui.design.InitialsAvatar
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_col_last_seen
import app.skerry.ui.generated.resources.lib_teams_col_member
import app.skerry.ui.generated.resources.lib_teams_col_role
import app.skerry.ui.generated.resources.lib_teams_scopes
import app.skerry.ui.generated.resources.lib_teams_status_invited
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Value cell with nothing to show — a member holding no scope, an unreported last-seen time. */
internal const val NO_VALUE = "—"

/** Value cell whose answer this device couldn't obtain — distinct from "there is nothing here". */
internal const val UNKNOWN_VALUE = "?"

private val ROLE_WIDTH = 104.dp
private val SEEN_WIDTH = 150.dp
private val ACTIONS_WIDTH = 28.dp

/**
 * The team's members as a table: who they are, what they may do, which share spaces they hold a key
 * to, and when they were last around. The role badge is the role editor and the trailing cross
 * removes — both only for a viewer the server would actually let do it ([TeamMemberRowUi.manageable]).
 */
@Composable
internal fun TeamMemberTable(
    rows: List<TeamMemberRowUi>,
    now: Long,
    /** True while the access lists are still being fetched — the scope column stays blank until then. */
    scopesLoading: Boolean = false,
    onChangeRole: (TeamMemberRowUi) -> Unit,
    onRemove: (TeamMemberRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        MemberGridRow(Modifier.padding(vertical = 9.dp)) {
            HeaderCell(stringResource(Res.string.lib_teams_col_member), Modifier.weight(1.6f))
            HeaderCell(stringResource(Res.string.lib_teams_col_role), Modifier.width(ROLE_WIDTH))
            HeaderCell(stringResource(Res.string.lib_teams_scopes), Modifier.weight(1.2f))
            HeaderCell(stringResource(Res.string.lib_teams_col_last_seen), Modifier.width(SEEN_WIDTH))
            Box(Modifier.width(ACTIONS_WIDTH))
        }
        HLine()
        rows.forEach { row ->
            MemberRow(row, now, scopesLoading, onChangeRole = { onChangeRole(row) }, onRemove = { onRemove(row) })
            HLine()
        }
    }
}

@Composable
private fun MemberRow(row: TeamMemberRowUi, now: Long, scopesLoading: Boolean, onChangeRole: () -> Unit, onRemove: () -> Unit) {
    val mono = LocalFonts.current.mono
    val member = row.member
    val invited = member.status == TeamMemberStatus.INVITED
    val (roleFg, roleBg) = roleBadgeColors(member.role)
    MemberGridRow(Modifier.padding(vertical = 11.dp)) {
        Row(
            Modifier.weight(1.6f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InitialsAvatar(member.accountId, size = 28.dp)
            Txt(
                member.accountId,
                color = if (invited) Skerry.colors.dim else Skerry.colors.text,
                size = 12.5.sp,
                font = mono,
                weight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.width(ROLE_WIDTH)) {
            if (invited) {
                RoleBadge(stringResource(Res.string.lib_teams_status_invited), Skerry.colors.amber, Skerry.colors.amber.copy(alpha = 0.14f))
            } else {
                // Clicking the badge opens the role picker — the same affordance the old list had.
                val editable = if (row.manageable) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onChangeRole) else Modifier
                RoleBadge(teamRoleLabel(member.role), roleFg, roleBg, modifier = editable)
            }
        }
        Box(Modifier.weight(1.2f)) {
            // An access list we failed to read is not the same answer as "holds nothing" — and a
            // partial read is not a complete one, so the "?" follows whatever tags did load.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.scopes.isEmpty() && row.scopesKnown && !scopesLoading) {
                    Txt(NO_VALUE, color = Skerry.colors.faint, size = 11.5.sp, font = mono)
                }
                row.scopes.forEach { ScopeTag(it) }
                if (!row.scopesKnown && !scopesLoading) {
                    Txt(UNKNOWN_VALUE, color = Skerry.colors.amber, size = 11.5.sp, font = mono)
                }
            }
        }
        Txt(
            lastSeenText(row.member.lastSeenAt, now),
            color = Skerry.colors.dim,
            size = 11.5.sp,
            font = mono,
            modifier = Modifier.width(SEEN_WIDTH),
            maxLines = 1,
        )
        Box(Modifier.width(ACTIONS_WIDTH), contentAlignment = Alignment.CenterEnd) {
            if (row.manageable) {
                Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(4.dp)) {
                    Sym("close", size = 15.sp, color = Skerry.colors.faint)
                }
            }
        }
    }
}

/** A share space a member holds, as a small tag — the "#prod" of the design. */
@Composable
private fun ScopeTag(name: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Skerry.colors.overlayMed)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Txt("#$name", color = Skerry.colors.dim, size = 10.5.sp, font = LocalFonts.current.mono, maxLines = 1)
    }
}

/** Shared geometry of the header and the rows — one place decides padding and column spacing. */
@Composable
private fun MemberGridRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Txt(text.uppercase(), color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}
