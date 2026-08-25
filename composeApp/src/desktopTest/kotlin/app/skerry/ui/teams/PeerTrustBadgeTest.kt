package app.skerry.ui.teams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_peer_confirm
import app.skerry.ui.generated.resources.lib_teams_peer_confirmed
import app.skerry.ui.generated.resources.lib_teams_peer_refused
import app.skerry.ui.mobile.MobileMemberRow
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mark a member row wears for its key, and the ceremony it opens.
 *
 * It is the only way into that ceremony from the member list — the error a refused seal raises says
 * "confirm it in the member list", and nothing else on the screen calls `onConfirmKey`. So which
 * rows offer it, and that pressing it names the right account, is the behaviour worth pinning: with
 * the affordance deleted the state tests all still passed (#323).
 */
@OptIn(ExperimentalTestApi::class)
class PeerTrustBadgeTest {

    @Test
    fun `a key nobody confirmed wears a mark that opens the ceremony for that member`() {
        var opened: String? = null
        var action = ""
        runForm({
            action = stringResource(Res.string.lib_teams_peer_confirm)
            TeamMemberTable(
                rows = listOf(row(PeerTrust.FIRST_SIGHT)),
                now = NOW,
                onChangeRole = {},
                onRemove = {},
                onConfirmKey = { opened = it.member.accountId },
            )
        }) {
            waitForIdle()
            onNodeWithContentDescription(action, substring = true).performClick()
            waitForIdle()
        }
        assertEquals(ACCOUNT, opened)
    }

    /**
     * A colleague who rotates their identity leaves a confirmed pin that no longer matches their
     * published key: every seal to them is refused from then on, and this mark is the way back. It
     * used to be the one state that drew an inert glyph.
     */
    @Test
    fun `a confirmed key still opens the ceremony`() {
        var opened: String? = null
        var confirmed = ""
        runForm({
            confirmed = stringResource(Res.string.lib_teams_peer_confirmed)
            TeamMemberTable(
                rows = listOf(row(PeerTrust.CONFIRMED)),
                now = NOW,
                onChangeRole = {},
                onRemove = {},
                onConfirmKey = { opened = it.member.accountId },
            )
        }) {
            waitForIdle()
            onNodeWithContentDescription(confirmed, substring = true).performClick()
            waitForIdle()
        }
        assertEquals(ACCOUNT, opened, "a confirmed pin that stopped matching has no other way back")
    }

    /**
     * The row a refused seal is talking about. Its own mark, not the amber one a first sight wears:
     * the error says "confirm it in the member list", and on a team of a dozen a mark shared with
     * every unrelated first sight names nobody (#326).
     */
    @Test
    fun `the refused row wears its own mark and opens the ceremony`() {
        var opened: String? = null
        var refusedMark = ""
        var plainAction = ""
        runForm({
            refusedMark = stringResource(Res.string.lib_teams_peer_refused)
            plainAction = stringResource(Res.string.lib_teams_peer_confirm)
            TeamMemberTable(
                rows = listOf(row(PeerTrust.REFUSED, ACCOUNT), row(PeerTrust.FIRST_SIGHT, OTHER)),
                now = NOW,
                onChangeRole = {},
                onRemove = {},
                onConfirmKey = { opened = it.member.accountId },
            )
        }) {
            waitForIdle()
            assertEquals(
                1,
                onAllNodesWithContentDescription(refusedMark, substring = true).fetchSemanticsNodes().size,
                "the mark names one row, not every row that is not confirmed",
            )
            onNodeWithContentDescription(refusedMark, substring = true).performClick()
            waitForIdle()
        }
        assertEquals(ACCOUNT, opened)
        assertTrue(refusedMark != plainAction, "a refusal that reads like a first sight names nobody")
    }

    @Test
    fun `the own row and a row whose owner cannot be told wear no mark`() {
        listOf(PeerTrust.SELF, PeerTrust.UNKNOWN).forEach { trust ->
            var action = ""
            runForm({
                action = stringResource(Res.string.lib_teams_peer_confirm)
                TeamMemberTable(
                    rows = listOf(row(trust)),
                    now = NOW,
                    onChangeRole = {},
                    onRemove = {},
                    onConfirmKey = {},
                )
            }) {
                waitForIdle()
                assertTrue(
                    onAllNodesWithContentDescription(action, substring = true).fetchSemanticsNodes().isEmpty(),
                    "$trust must not be offered a ceremony",
                )
            }
        }
    }

    /** The phone draws the same mark and opens the same ceremony. */
    @Test
    fun `the phone row opens the ceremony too`() {
        var opened = false
        var action = ""
        runForm({
            action = stringResource(Res.string.lib_teams_peer_confirm)
            MobileMemberRow(row(PeerTrust.NONE), now = NOW, onChangeRole = {}, onRemove = {}, onConfirmKey = { opened = true })
        }) {
            waitForIdle()
            onNodeWithContentDescription(action, substring = true).performClick()
            waitForIdle()
        }
        assertTrue(opened)
    }
}

private fun row(trust: PeerTrust, accountId: String = ACCOUNT) = TeamMemberRowUi(
    member = TeamMember(
        accountId = accountId,
        role = TeamRole.EDITOR,
        status = TeamMemberStatus.ACTIVE,
        createdAt = NOW,
        lastSeenAt = NOW,
    ),
    isOwner = false,
    scopes = emptyList(),
    scopesKnown = true,
    manageable = true,
    trust = trust,
)

private const val ACCOUNT = "bob@example.com"
private const val OTHER = "carol@example.com"
private const val NOW = 1_700_000_000_000L
