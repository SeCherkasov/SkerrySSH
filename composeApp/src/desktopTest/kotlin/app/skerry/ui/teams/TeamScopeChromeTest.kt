package app.skerry.ui.teams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.skerry.ui.desktop.runForm
import app.skerry.ui.mobile.MobileTeamActions
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite
import app.skerry.ui.generated.resources.lib_teams_scope_access
import app.skerry.ui.generated.resources.lib_teams_scope_delete
import app.skerry.ui.generated.resources.lib_teams_scope_new
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the scope chrome puts its actions. Creating a scope is a team action and belongs in the
 * screen header beside Invite; the chip row draws chips and nothing else. A manager is the only
 * viewer who could see the button, so that is the case worth pinning — this ran green before the
 * move with the button in the wrong place, because nothing tested it with `canManage = true`.
 */
@OptIn(ExperimentalTestApi::class)
class TeamScopeChromeTest {

    private val newScope = runBlocking { getString(Res.string.lib_teams_scope_new) }
    private val access = runBlocking { getString(Res.string.lib_teams_scope_access) }
    private val invite = runBlocking { getString(Res.string.lib_teams_invite) }
    private val deleteScope = runBlocking { getString(Res.string.lib_teams_scope_delete) }

    @Test
    fun `a manager's chip row offers no create button — that lives in the header`() = runForm({
        ScopeSection(
            scopes = listOf(TeamScopeUi(id = "s1", name = "prod", memberCount = 2, hasKey = true)),
            selected = "s1",
            canManage = true,
            onSelect = {},
            onAccess = {},
            onDelete = {},
        )
    }) {
        // The manager actions on the selected scope are still there — this is the chip row's own
        // chrome, and its absence would mean the test proved nothing.
        onNodeWithText(access).assertIsDisplayed()
        onNodeWithText(newScope).assertDoesNotExist()
    }

    @Test
    fun `the header is where a manager creates a share space`() = runForm({
        Row {
            TeamHeaderActions(
                lastSyncedAt = null,
                busy = false,
                owner = true,
                canManage = true,
                invited = false,
                onSync = {},
                onNewScope = {},
                onInvite = {},
                onLeave = {},
                onDelete = {},
            )
        }
    }) {
        onNodeWithText(newScope).assertIsDisplayed()
    }

    @Test
    fun `a member who cannot manage the team is not offered a share space`() = runForm({
        Row {
            TeamHeaderActions(
                lastSyncedAt = null,
                busy = false,
                owner = false,
                canManage = false,
                invited = false,
                onSync = {},
                onNewScope = {},
                onInvite = {},
                onLeave = {},
                onDelete = {},
            )
        }
    }) {
        onNodeWithText(newScope).assertDoesNotExist()
    }

    /**
     * The phone's action row at a phone's width. Four actions in a plain Row measure the last one —
     * Invite, the screen's primary — into whatever is left, and it lands off the edge; the row wraps
     * instead. Asserted at 300 dp because that is narrower than the four laid out side by side.
     */
    @Test
    fun `the phone's actions wrap instead of pushing Invite off the edge`() = runForm({
        Box(Modifier.width(300.dp)) {
            MobileTeamActions(
                lastSyncedAt = null,
                busy = false,
                canManage = true,
                canAudit = true,
                onSync = {},
                onShowHistory = {},
                onNewScope = {},
                onInvite = {},
            )
        }
    }) {
        onNodeWithText(invite).assertIsDisplayed()
        onNodeWithText(newScope).assertIsDisplayed()
    }

    /**
     * The scope manager's own actions at a phone's width. Side by side they need ~210 dp, so at 160
     * a plain Row squeezes "Delete scope" into what is left of the line and the label is cut; the
     * row wraps instead. Asserted on the geometry rather than on visibility because a squeezed
     * button still has bounds — only its position says whether the wrap happened.
     */
    @Test
    fun `the scope actions wrap instead of cutting Delete short`() = runForm({
        Box(Modifier.width(160.dp)) {
            ScopeSection(
                scopes = listOf(TeamScopeUi(id = "s1", name = "prod", memberCount = 2, hasKey = true)),
                selected = "s1",
                canManage = true,
                onSelect = {},
                onAccess = {},
                onDelete = {},
            )
        }
    }) {
        val first = onNodeWithText(access).fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText(deleteScope).fetchSemanticsNode().boundsInRoot
        assertTrue(second.top >= first.bottom, "Delete scope sits beside Access instead of below it")
    }
}
