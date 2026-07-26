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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamActivityCategory
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamActivityKind
import app.skerry.shared.team.TeamActivityRow
import app.skerry.shared.team.buildTeamActivityFeed
import app.skerry.shared.terminal.epochMillis
import app.skerry.shared.vault.RecordType
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_actor_you
import app.skerry.ui.generated.resources.lib_teams_event_accept
import app.skerry.ui.generated.resources.lib_teams_event_create
import app.skerry.ui.generated.resources.lib_teams_event_delete
import app.skerry.ui.generated.resources.lib_teams_event_invite
import app.skerry.ui.generated.resources.lib_teams_event_record_change
import app.skerry.ui.generated.resources.lib_teams_event_record_remove
import app.skerry.ui.generated.resources.lib_teams_event_record_share
import app.skerry.ui.generated.resources.lib_teams_event_records_bulk
import app.skerry.ui.generated.resources.lib_teams_event_rekey
import app.skerry.ui.generated.resources.lib_teams_event_remove
import app.skerry.ui.generated.resources.lib_teams_event_role_change
import app.skerry.ui.generated.resources.lib_teams_event_scope_create
import app.skerry.ui.generated.resources.lib_teams_event_scope_delete
import app.skerry.ui.generated.resources.lib_teams_event_scope_grant
import app.skerry.ui.generated.resources.lib_teams_event_scope_rekey
import app.skerry.ui.generated.resources.lib_teams_event_scope_revoke
import app.skerry.ui.generated.resources.lib_teams_event_session_open
import app.skerry.ui.generated.resources.lib_teams_event_session_record
import app.skerry.ui.generated.resources.lib_teams_event_unknown
import app.skerry.ui.generated.resources.lib_teams_feed_day_today
import app.skerry.ui.generated.resources.lib_teams_feed_day_yesterday
import app.skerry.ui.generated.resources.lib_teams_feed_duration
import app.skerry.ui.generated.resources.lib_teams_feed_duration_short
import app.skerry.ui.generated.resources.lib_teams_feed_in_scope
import app.skerry.ui.generated.resources.lib_teams_feed_record_empty
import app.skerry.ui.generated.resources.lib_teams_feed_record_title
import app.skerry.ui.generated.resources.lib_teams_feed_reported_note
import app.skerry.ui.generated.resources.lib_teams_filter_access
import app.skerry.ui.generated.resources.lib_teams_filter_all
import app.skerry.ui.generated.resources.lib_teams_filter_members
import app.skerry.ui.generated.resources.lib_teams_filter_records
import app.skerry.ui.generated.resources.lib_teams_filter_sessions
import app.skerry.ui.generated.resources.lib_teams_history_empty
import app.skerry.ui.generated.resources.lib_teams_history_title
import app.skerry.ui.generated.resources.lib_teams_record_credential
import app.skerry.ui.generated.resources.lib_teams_record_group
import app.skerry.ui.generated.resources.lib_teams_record_host
import app.skerry.ui.generated.resources.lib_teams_record_identity
import app.skerry.ui.generated.resources.lib_teams_record_other
import app.skerry.ui.generated.resources.lib_teams_record_snippet
import app.skerry.ui.generated.resources.lib_teams_record_tunnel
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Team activity feed (owner/admin): who changed which host, who was let in, whose key was rotated,
 * and — reported by the members' own apps — who opened a session on a shared host.
 *
 * Record **names** are resolved here, on the reader's device: the server stores only ids (it has no
 * way to know a name), so [recordNames] is `scopeId -> recordId -> name` built from the share spaces
 * this member can actually read. A record whose name can't be resolved (unshared since, or in a
 * scope we hold no grant for) still gets its row, keyed by a short id.
 *
 * With [focusRecordId] set the dialog is one record's history instead of the whole team's, and the
 * category filter is out of the way — the question there is already "what happened to this host".
 */
@Composable
internal fun TeamActivityDialog(
    entries: List<TeamActivityEntry>,
    selfAccountId: String?,
    recordNames: Map<String, Map<String, String>>,
    scopeNames: Map<String, String>,
    onDismiss: () -> Unit,
    focusRecordId: String? = null,
    focusRecordLabel: String? = null,
) {
    val mono = LocalFonts.current.mono
    var category by remember { mutableStateOf(TeamActivityCategory.ALL) }
    val effectiveCategory = if (focusRecordId != null) TeamActivityCategory.ALL else category
    val days = remember(entries, effectiveCategory, focusRecordId, recordNames, scopeNames, selfAccountId) {
        buildTeamActivityFeed(
            entries = entries,
            selfAccountId = selfAccountId,
            category = effectiveCategory,
            onlyRecordId = focusRecordId,
            resolveRecordName = { scopeId, recordId -> recordNames[scopeId]?.get(recordId) },
            resolveScopeName = { scopeId -> scopeNames[scopeId] },
        )
    }
    // "Today"/"Yesterday" need a reference point; read once per dialog opening, not per row.
    val todayIndex = remember { epochMillis().floorDiv(MILLIS_PER_DAY) }

    TeamsDialogCard(onDismiss) {
        Txt(
            focusRecordLabel?.let { stringResource(Res.string.lib_teams_feed_record_title, it) }
                ?: stringResource(Res.string.lib_teams_history_title),
            color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (focusRecordId == null) {
            ActivityFilterChips(category, onSelect = { category = it })
        }
        if (days.isEmpty()) {
            Txt(
                if (focusRecordId != null) stringResource(Res.string.lib_teams_feed_record_empty)
                else stringResource(Res.string.lib_teams_history_empty),
                color = Skerry.colors.dim, size = 12.5.sp, modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                days.forEach { day ->
                    Txt(
                        dayLabel(day.dayIndex, todayIndex, day.rows.first().createdAt),
                        color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    day.rows.forEach { row -> ActivityRow(row, mono) }
                }
            }
        }
        if (days.any { day -> day.rows.any { it.clientReported } }) {
            Txt(
                stringResource(Res.string.lib_teams_feed_reported_note),
                color = Skerry.colors.faint, size = 10.5.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            CancelButton(stringResource(Res.string.shell_cancel), onDismiss)
        }
    }
}

@Composable
private fun ActivityFilterChips(selected: TeamActivityCategory, onSelect: (TeamActivityCategory) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamActivityCategory.entries.forEach { category ->
            val active = category == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) Skerry.colors.cyan.copy(alpha = 0.12f) else Color.Transparent)
                    .border(1.dp, if (active) Skerry.colors.cyan else Skerry.colors.line, RoundedCornerShape(6.dp))
                    .clickable { onSelect(category) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Txt(
                    categoryLabel(category),
                    color = if (active) Skerry.colors.cyanBright else Skerry.colors.dim,
                    size = 10.5.sp, weight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** One event: icon, what happened (with the record's name), who and where, and the time. */
@Composable
private fun ActivityRow(row: TeamActivityRow, mono: androidx.compose.ui.text.font.FontFamily) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym(rowIcon(row.kind), size = 15.sp, color = rowColor(row.kind))
            Txt(eventLabel(row), color = Skerry.colors.textBright, size = 12.5.sp, weight = FontWeight.Medium)
            row.subject?.let { subject ->
                Txt(
                    subject,
                    // A name we resolved reads as a name; a bare short id is shown as the id it is.
                    color = if (row.subjectResolved) Skerry.colors.cyanBright else Skerry.colors.faint,
                    size = 12.sp, font = mono, modifier = Modifier.weight(1f),
                )
            } ?: Box(Modifier.weight(1f))
            Txt(timeOfDay(row.createdAt), color = Skerry.colors.faint, size = 10.5.sp, font = mono)
        }
        Txt(
            metaLine(row),
            color = Skerry.colors.dim, size = 10.5.sp, font = mono,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Actor, share space, and reported length — everything about the row that isn't the event itself. */
@Composable
private fun metaLine(row: TeamActivityRow): String {
    val actor = if (row.isSelf) stringResource(Res.string.lib_teams_actor_you) else row.actorAccountId
    val parts = mutableListOf(actor)
    row.scopeName?.let { parts += stringResource(Res.string.lib_teams_feed_in_scope, it) }
    row.durationSec?.let { seconds ->
        parts += if (seconds < 60) stringResource(Res.string.lib_teams_feed_duration_short)
        else stringResource(Res.string.lib_teams_feed_duration, (seconds / 60).toString())
    }
    // A bulk summary and an unknown event carry the server's own wording; nothing else does.
    if (row.kind == TeamActivityKind.UNKNOWN && row.detail.isNotBlank()) parts += row.detail
    return parts.joinToString(" · ")
}

/**
 * What happened, in words. A record event names the type of thing it touched ("Changed host") and
 * the row shows the name beside it; everything else is a complete sentence on its own.
 */
@Composable
private fun eventLabel(row: TeamActivityRow): String = when (row.kind) {
    TeamActivityKind.TEAM_CREATE -> stringResource(Res.string.lib_teams_event_create)
    TeamActivityKind.TEAM_DELETE -> stringResource(Res.string.lib_teams_event_delete)
    TeamActivityKind.MEMBER_INVITE -> stringResource(Res.string.lib_teams_event_invite)
    TeamActivityKind.MEMBER_JOIN -> stringResource(Res.string.lib_teams_event_accept)
    TeamActivityKind.MEMBER_REMOVE -> stringResource(Res.string.lib_teams_event_remove)
    TeamActivityKind.MEMBER_ROLE -> stringResource(Res.string.lib_teams_event_role_change)
    TeamActivityKind.KEY_ROTATE -> stringResource(Res.string.lib_teams_event_rekey)
    TeamActivityKind.SCOPE_CREATE -> stringResource(Res.string.lib_teams_event_scope_create)
    TeamActivityKind.SCOPE_DELETE -> stringResource(Res.string.lib_teams_event_scope_delete)
    TeamActivityKind.SCOPE_GRANT -> stringResource(Res.string.lib_teams_event_scope_grant)
    TeamActivityKind.SCOPE_REVOKE -> stringResource(Res.string.lib_teams_event_scope_revoke)
    TeamActivityKind.SCOPE_KEY_ROTATE -> stringResource(Res.string.lib_teams_event_scope_rekey)
    TeamActivityKind.RECORD_SHARE ->
        "${stringResource(Res.string.lib_teams_event_record_share)} ${recordNoun(row.recordType)}"
    TeamActivityKind.RECORD_CHANGE ->
        "${stringResource(Res.string.lib_teams_event_record_change)} ${recordNoun(row.recordType)}"
    TeamActivityKind.RECORD_REMOVE ->
        "${stringResource(Res.string.lib_teams_event_record_remove)} ${recordNoun(row.recordType)}"
    // The count lives in the server's summary text; a plain number is all it needs to carry.
    TeamActivityKind.RECORDS_BULK ->
        stringResource(Res.string.lib_teams_event_records_bulk, row.detail.takeWhile { it.isDigit() }.toIntOrNull() ?: 0)
    TeamActivityKind.SESSION_OPEN -> stringResource(Res.string.lib_teams_event_session_open)
    TeamActivityKind.SESSION_RECORD -> stringResource(Res.string.lib_teams_event_session_record)
    TeamActivityKind.UNKNOWN -> stringResource(Res.string.lib_teams_event_unknown, row.event)
}

/** The kind of thing a record event touched, as a noun to follow the verb. */
@Composable
private fun recordNoun(type: RecordType?): String = when (type) {
    RecordType.HOST -> stringResource(Res.string.lib_teams_record_host)
    RecordType.SNIPPET -> stringResource(Res.string.lib_teams_record_snippet)
    RecordType.CREDENTIAL -> stringResource(Res.string.lib_teams_record_credential)
    RecordType.IDENTITY -> stringResource(Res.string.lib_teams_record_identity)
    RecordType.TUNNEL -> stringResource(Res.string.lib_teams_record_tunnel)
    RecordType.GROUP -> stringResource(Res.string.lib_teams_record_group)
    else -> stringResource(Res.string.lib_teams_record_other)
}

@Composable
private fun categoryLabel(category: TeamActivityCategory): String = when (category) {
    TeamActivityCategory.ALL -> stringResource(Res.string.lib_teams_filter_all)
    TeamActivityCategory.RECORDS -> stringResource(Res.string.lib_teams_filter_records)
    TeamActivityCategory.MEMBERS -> stringResource(Res.string.lib_teams_filter_members)
    TeamActivityCategory.ACCESS -> stringResource(Res.string.lib_teams_filter_access)
    TeamActivityCategory.SESSIONS -> stringResource(Res.string.lib_teams_filter_sessions)
}

private fun rowIcon(kind: TeamActivityKind): String = when (kind) {
    TeamActivityKind.TEAM_CREATE, TeamActivityKind.TEAM_DELETE -> "group"
    TeamActivityKind.MEMBER_INVITE -> "person_add"
    TeamActivityKind.MEMBER_JOIN -> "how_to_reg"
    TeamActivityKind.MEMBER_REMOVE -> "person_remove"
    TeamActivityKind.MEMBER_ROLE -> "badge"
    TeamActivityKind.KEY_ROTATE, TeamActivityKind.SCOPE_KEY_ROTATE -> "sync_lock"
    TeamActivityKind.SCOPE_CREATE, TeamActivityKind.SCOPE_DELETE -> "workspaces"
    TeamActivityKind.SCOPE_GRANT -> "key"
    TeamActivityKind.SCOPE_REVOKE -> "key_off"
    TeamActivityKind.RECORD_SHARE -> "add_circle"
    TeamActivityKind.RECORD_CHANGE -> "edit"
    // `remove_circle` is absent from the bundled Material Symbols subset — a missing ligature would
    // render as its own name in plain text, so every icon here must exist in the shipped font.
    TeamActivityKind.RECORD_REMOVE -> "do_not_disturb_on"
    TeamActivityKind.RECORDS_BULK -> "sync"
    TeamActivityKind.SESSION_OPEN -> "terminal"
    TeamActivityKind.SESSION_RECORD -> "radio_button_checked"
    TeamActivityKind.UNKNOWN -> "help"
}

@Composable
private fun rowColor(kind: TeamActivityKind): Color = when (kind) {
    TeamActivityKind.RECORD_REMOVE, TeamActivityKind.MEMBER_REMOVE, TeamActivityKind.SCOPE_REVOKE,
    TeamActivityKind.TEAM_DELETE, TeamActivityKind.SCOPE_DELETE -> Skerry.colors.sunset
    TeamActivityKind.RECORD_SHARE, TeamActivityKind.MEMBER_JOIN, TeamActivityKind.SCOPE_GRANT -> Skerry.colors.moss
    TeamActivityKind.KEY_ROTATE, TeamActivityKind.SCOPE_KEY_ROTATE -> Skerry.colors.amber
    else -> Skerry.colors.cyanBright
}

/** Day header: Today/Yesterday, else the date (UTC, like the row times). */
@Composable
private fun dayLabel(dayIndex: Long, todayIndex: Long, anyTimestampOfDay: Long): String = when (todayIndex - dayIndex) {
    0L -> stringResource(Res.string.lib_teams_feed_day_today)
    1L -> stringResource(Res.string.lib_teams_feed_day_yesterday)
    else -> formatEpochUtc(anyTimestampOfDay).substringBefore(' ')
}

/** `HH:MM` of a timestamp, from the same UTC formatter the rest of the feed uses. */
private fun timeOfDay(millis: Long): String = formatEpochUtc(millis).substringAfter(' ')

private const val MILLIS_PER_DAY = 86_400_000L
