package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamRole
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Txt
import app.skerry.ui.design.CancelButton
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.lib_teams_event_accept
import app.skerry.ui.generated.resources.lib_teams_event_create
import app.skerry.ui.generated.resources.lib_teams_event_delete
import app.skerry.ui.generated.resources.lib_teams_event_invite
import app.skerry.ui.generated.resources.lib_teams_event_remove
import app.skerry.ui.generated.resources.lib_teams_event_role_change
import app.skerry.ui.generated.resources.lib_teams_event_share
import app.skerry.ui.generated.resources.lib_teams_event_unknown
import app.skerry.ui.generated.resources.lib_teams_history_empty
import app.skerry.ui.generated.resources.lib_teams_history_title
import app.skerry.ui.generated.resources.lib_teams_role_admin
import app.skerry.ui.generated.resources.lib_teams_role_editor
import app.skerry.ui.generated.resources.lib_teams_role_owner
import app.skerry.ui.generated.resources.lib_teams_role_picker_title
import app.skerry.ui.generated.resources.lib_teams_role_viewer
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/** Localized role label for badges and pickers. */
@Composable
internal fun teamRoleLabel(role: TeamRole): String = when (role) {
    TeamRole.OWNER -> stringResource(Res.string.lib_teams_role_owner)
    TeamRole.ADMIN -> stringResource(Res.string.lib_teams_role_admin)
    TeamRole.EDITOR -> stringResource(Res.string.lib_teams_role_editor)
    TeamRole.VIEWER -> stringResource(Res.string.lib_teams_role_viewer)
}

/**
 * Whether [actor] may modify/remove a member with role [target] (mirrors the server ACL for UX; the
 * server is authoritative). Owner: anyone but an owner; admin: editor/viewer.
 */
internal fun canModifyMember(actor: TeamRole, target: TeamRole): Boolean = when (actor) {
    TeamRole.OWNER -> target != TeamRole.OWNER
    TeamRole.ADMIN -> target == TeamRole.EDITOR || target == TeamRole.VIEWER
    else -> false
}

/** Role badge colors (text, background), [D] tokens only. */
@Composable
@ReadOnlyComposable
internal fun roleBadgeColors(role: TeamRole): Pair<Color, Color> = when (role) {
    TeamRole.OWNER -> Skerry.colors.amber to Skerry.colors.amber.copy(alpha = 0.14f)
    TeamRole.ADMIN -> Skerry.colors.cyanBright to Skerry.colors.cyan.copy(alpha = 0.12f)
    TeamRole.EDITOR -> Skerry.colors.moss to Skerry.colors.moss.copy(alpha = 0.14f)
    TeamRole.VIEWER -> Skerry.colors.dim to Color(0x0DFFFFFF)
}

/** Localized audit event summary; an unknown code goes into a localized fallback as a detail. */
@Composable
internal fun teamEventLabel(event: String): String = when (event) {
    "team.create" -> stringResource(Res.string.lib_teams_event_create)
    "team.invite" -> stringResource(Res.string.lib_teams_event_invite)
    "team.remove" -> stringResource(Res.string.lib_teams_event_remove)
    "team.accept" -> stringResource(Res.string.lib_teams_event_accept)
    "team.role_change" -> stringResource(Res.string.lib_teams_event_role_change)
    "team.push" -> stringResource(Res.string.lib_teams_event_share)
    "team.delete" -> stringResource(Res.string.lib_teams_event_delete)
    else -> stringResource(Res.string.lib_teams_event_unknown, event)
}

/** Member role/status badge (shared visual language for desktop and mobile). */
@Composable
internal fun RoleBadge(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 9.dp, vertical = 2.dp)) {
        Txt(text, color = fg, size = 10.sp, weight = FontWeight.SemiBold)
    }
}

/** Horizontal role segment picker (invite dialog). [options] are the roles the actor may assign. */
@Composable
internal fun RoleChips(options: List<TeamRole>, selected: TeamRole, onSelect: (TeamRole) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { role ->
            val active = role == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) Skerry.colors.cyan.copy(alpha = 0.12f) else Color.Transparent)
                    .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.line, RoundedCornerShape(7.dp))
                    .clickable { onSelect(role) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Txt(teamRoleLabel(role), color = if (active) Skerry.colors.cyanBright else Skerry.colors.dim, size = 11.5.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** Member role-change dialog: list of assignable roles, current one highlighted. */
@Composable
internal fun RolePickerDialog(
    accountId: String,
    current: TeamRole,
    assignable: List<TeamRole>,
    onPick: (TeamRole) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalFonts.current.mono
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_role_picker_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(accountId, color = Skerry.colors.dim, size = 12.sp, font = mono, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            assignable.forEach { role ->
                val active = role == current
                val (fg, bg) = roleBadgeColors(role)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.cyan08, RoundedCornerShape(7.dp))
                        .clickable { onPick(role) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RoleBadge(teamRoleLabel(role), fg, bg)
                    if (active) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Txt("✓", color = Skerry.colors.cyanBright, size = 13.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            CancelButton(stringResource(Res.string.shell_cancel), onDismiss)
        }
    }
}

/** Team audit-log dialog (owner/admin): newest events first, metadata only. */
@Composable
internal fun AuditLogDialog(entries: List<TeamActivityEntry>, onDismiss: () -> Unit) {
    val mono = LocalFonts.current.mono
    TeamsDialogCard(onDismiss) {
        Txt(stringResource(Res.string.lib_teams_history_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, modifier = Modifier.padding(bottom = 14.dp))
        if (entries.isEmpty()) {
            Txt(stringResource(Res.string.lib_teams_history_empty), color = Skerry.colors.dim, size = 12.5.sp)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { e ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt(teamEventLabel(e.event), color = Skerry.colors.textBright, size = 12.5.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Txt(formatEpochUtc(e.createdAt), color = Skerry.colors.faint, size = 10.5.sp, font = mono)
                        }
                        Txt(e.actorAccountId, color = Skerry.colors.dim, size = 11.sp, font = mono, modifier = Modifier.padding(top = 3.dp))
                        if (e.detail.isNotBlank()) {
                            Txt(e.detail, color = Skerry.colors.faint, size = 10.5.sp, font = mono, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            CancelButton(stringResource(Res.string.shell_cancel), onDismiss)
        }
    }
}

/**
 * Formats epoch-millis as `YYYY-MM-DD HH:MM` UTC with plain arithmetic (no kotlinx-datetime in the
 * UI module). Howard Hinnant's civil-from-days algorithm; server timestamps are >= 0.
 */
internal fun formatEpochUtc(millis: Long): String {
    val totalSec = millis / 1000
    val days = totalSec / 86_400
    val secOfDay = totalSec % 86_400
    val hh = secOfDay / 3600
    val mm = (secOfDay % 3600) / 60
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val year0 = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = if (month <= 2) year0 + 1 else year0
    fun p2(v: Long) = if (v < 10) "0$v" else "$v"
    return "$year-${p2(month)}-${p2(day)} ${p2(hh)}:${p2(mm)}"
}
