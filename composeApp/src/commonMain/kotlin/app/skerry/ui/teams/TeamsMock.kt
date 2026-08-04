package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.TeamActivityEntry
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.team.buildTeamActivityFeed
import app.skerry.shared.terminal.epochMillis
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.SIDEBAR_WIDTH
import app.skerry.ui.design.SectionHeader
import app.skerry.ui.design.SidebarSectionTitle
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.VLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_header_subtitle
import app.skerry.ui.generated.resources.lib_teams_header_title
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_sidebar
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// Preview path (LocalTeams == null): the same composables the live screen is built from, fed with
// placeholder rows — so a layout change can't quietly apply to only one of the two.

private const val MOCK_TEAM = "skerry-ops"
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

private val MOCK_SCOPES = listOf(
    TeamScopeUi(id = "s-prod", name = "prod", memberCount = 2, hasKey = true),
    TeamScopeUi(id = "s-db", name = "db", memberCount = 2, hasKey = true),
)

private fun mockTeam() = TeamUi(
    id = "team-mock",
    name = MOCK_TEAM,
    ownerAccountId = "sergey@skerry.dev",
    role = TeamRole.OWNER,
    status = TeamMemberStatus.ACTIVE,
    memberCount = 5,
    hasKey = true,
    scopes = MOCK_SCOPES,
)

private fun mockMembers(now: Long) = listOf(
    TeamMember("sergey@skerry.dev", TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0, now),
    TeamMember("anna@skerry.dev", TeamRole.ADMIN, TeamMemberStatus.ACTIVE, 0, now - 3 * HOUR_MS),
    TeamMember("dmitry@skerry.dev", TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0, now - DAY_MS),
    TeamMember("marina@skerry.dev", TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0, now - 6 * DAY_MS),
    TeamMember("pavel@skerry.dev", TeamRole.VIEWER, TeamMemberStatus.INVITED, 0, null),
)

private fun mockGrants() = mapOf(
    "s-prod" to setOf("anna@skerry.dev"),
    "s-db" to setOf("anna@skerry.dev", "marina@skerry.dev"),
)

private fun mockEntries(now: Long) = listOf(
    TeamActivityEntry("anna@skerry.dev", "team.record_share", "", now - 2 * HOUR_MS, recordId = "h1", recordType = "HOST"),
    TeamActivityEntry("sergey@skerry.dev", "team.rekey", "", now - DAY_MS),
    TeamActivityEntry("sergey@skerry.dev", "team.invite", "pavel@skerry.dev", now - 2 * DAY_MS),
)

/** Static Teams layout — mock/preview path, rendered from the same building blocks as the live one. */
@Composable
internal fun TeamsMockView() {
    val now = epochMillis()
    val team = mockTeam()
    val feed = buildTeamActivityFeed(mockEntries(now), selfAccountId = team.ownerAccountId)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(Skerry.colors.surface2).padding(horizontal = 8.dp, vertical = 14.dp)) {
            SidebarSectionTitle(stringResource(Res.string.lib_teams_sidebar), modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
            MockTeamRow(MOCK_TEAM, active = true)
            MockTeamRow("data-team")
            Spacer(Modifier.weight(1f))
        }
        VLine(Skerry.colors.line)
        Column(Modifier.weight(1f).fillMaxHeight().background(Skerry.colors.bg)) {
            SectionHeader(
                title = stringResource(Res.string.lib_teams_header_title),
                subtitle = stringResource(Res.string.lib_teams_header_subtitle, team.name, team.memberCount),
                actions = { PrimaryButton(stringResource(Res.string.lib_teams_invite), onClick = {}, icon = "person_add") },
            )
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    TeamMemberTable(
                        rows = teamMemberRows(team, mockMembers(now), mockGrants(), canManage = true),
                        now = now,
                        onChangeRole = {},
                        onRemove = {},
                    )
                    TeamSummaryCards(
                        cards = TeamCards(
                            items = 33,
                            lastRekeyAt = now - DAY_MS,
                            endpoint = "sync.skerry.local",
                            serverVersion = "0.2.1",
                            devices = 9,
                            hosts = 14,
                            snippets = 18,
                            runbooks = 4,
                            liveSessions = 2,
                        ),
                        onOpen = {},
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    )
                }
                TeamActivityPanel(feed, onOpenFull = {})
            }
        }
    }
}

@Composable
private fun MockTeamRow(name: String, active: Boolean = false) {
    val fg = if (active) Skerry.colors.cyanBright else Skerry.colors.dim
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (active) Skerry.colors.cyan10 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym("group", size = 16.sp, color = fg)
        Txt(name, color = fg, size = 12.5.sp)
    }
}
