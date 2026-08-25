package app.skerry.ui.teams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import app.skerry.shared.team.TeamKeyStore
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_peer_refused
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The link the issue is about, end to end: a seal the coordinator refused, and the row the member
 * list draws a mark on because of it.
 *
 * The state tests either side of it — which account the coordinator names, and which state a row
 * takes from a name — both stay green with the refusal dropped on the way between them
 * ([rememberMemberPins] is where the vault pass and the refusal flow meet). This is the test that
 * does not.
 */
@OptIn(ExperimentalTestApi::class)
class MemberPinsMarkTest : TeamsPeerPinFixture() {

    @Test
    fun `the member the coordinator refused is the row the member list marks`() {
        val f = runBlocking { initializeVaultCrypto(); newFixture() }
        TeamKeyStore(f.vault).put(teamId, "Ops", TeamRole.OWNER, crypto.newDataKey(), epoch = 0)
        val client = FakePeerClient(self, teamId)
        listOf(bob, carol).forEach {
            client.published[it] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
        }
        client.teams = listOf(activeTeam(TeamRole.OWNER, members = 3))
        val coord = coordinator(f, client)
        val members = listOf(
            TeamMember(self, TeamRole.OWNER, TeamMemberStatus.ACTIVE, 0),
            TeamMember(bob, TeamRole.EDITOR, TeamMemberStatus.ACTIVE, 0),
            TeamMember(carol, TeamRole.VIEWER, TeamMemberStatus.ACTIVE, 0),
        )
        runBlocking {
            coord.createScope(teamId, "Production")
            coord.grantScope(teamId, "prod", bob) // first sight pins both
            coord.grantScope(teamId, "prod", carol)
            // Bob rotates his Teams identity: the pin for him stays confirmed and stops matching.
            client.published[bob] = Published(keysOf(crypto.newSharingKeyPair(), crypto.newSigningKeyPair()))
            coord.grantScope(teamId, "prod", bob)
        }

        var refusedMark = ""
        var marked: String? = null
        runForm({
            refusedMark = stringResource(Res.string.lib_teams_peer_refused)
            val marks = rememberMemberPins(coord, members.map { it.accountId }, tick = 0)
            val rows = teamMemberRows(
                team = teamUi(),
                members = members,
                scopeGrants = emptyMap(),
                canManage = true,
                selfAccountId = marks?.self,
                pins = marks?.pins.orEmpty(),
                refused = marks?.refused.orEmpty(),
            )
            marked = rows.singleOrNull { it.trust == PeerTrust.REFUSED }?.member?.accountId
            TeamMemberTable(rows = rows, now = 0, onChangeRole = {}, onRemove = {}, onConfirmKey = {})
        }) {
            waitForIdle()
            assertEquals(
                1,
                onAllNodesWithContentDescription(refusedMark, substring = true).fetchSemanticsNodes().size,
                "one row wears the mark, and it is drawn from what the coordinator refused",
            )
        }
        assertEquals(bob, marked)
    }

    private fun teamUi() = TeamUi(
        id = teamId,
        name = "Ops",
        ownerAccountId = self,
        role = TeamRole.OWNER,
        status = TeamMemberStatus.ACTIVE,
        memberCount = 3,
        hasKey = true,
    )
}
