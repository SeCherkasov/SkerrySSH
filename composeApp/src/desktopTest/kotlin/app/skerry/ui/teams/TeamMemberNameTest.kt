package app.skerry.ui.teams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.skerry.shared.team.TeamMember
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.ui.desktop.runForm
import app.skerry.ui.mobile.MobileMemberRow
import kotlin.test.Test

/**
 * The account id a team screen puts in front of a decision.
 *
 * An account id is only length-checked when it registers, and the member list comes from the sync
 * server: the row is where the operator reads who they are about to grant a share space to, change
 * the role of, or remove. A bidi override in it makes one row read as another account's.
 *
 * Written as escapes, never as the characters themselves.
 */
@OptIn(ExperimentalTestApi::class)
class TeamMemberNameTest {

    @Test
    fun `a member row draws the account id flattened`() = runForm({
        TeamMemberTable(
            rows = listOf(row()),
            now = NOW,
            onChangeRole = {},
            onRemove = {},
        )
    }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }

    /** The phone draws the same row, and the removal it opens is irreversible. */
    @Test
    fun `the phone member row draws the account id flattened`() = runForm({
        MobileMemberRow(row(), now = NOW, onChangeRole = {}, onRemove = {})
    }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }

    /** The role picker names the same account, over the control that changes what it may do. */
    @Test
    fun `the role picker draws the account id flattened`() = runForm({
        RolePickerDialog(
            accountId = SPOOFED,
            current = TeamRole.EDITOR,
            assignable = listOf(TeamRole.EDITOR, TeamRole.VIEWER),
            onPick = {},
            onDismiss = {},
        )
    }) {
        onNodeWithText(FLATTENED).assertIsDisplayed()
        onNodeWithText(SPOOFED).assertDoesNotExist()
    }
}

private fun row(): TeamMemberRowUi = TeamMemberRowUi(
    member = TeamMember(
        accountId = SPOOFED,
        role = TeamRole.EDITOR,
        status = TeamMemberStatus.ACTIVE,
        createdAt = NOW,
        lastSeenAt = NOW,
    ),
    isOwner = false,
    scopes = emptyList(),
    scopesKnown = true,
    manageable = true,
)

private const val NOW = 1_700_000_000_000L

/** U+202E before the tail: the row draws as another account unless it is filtered. */
private const val SPOOFED = "alice\u202Ebob"

private const val FLATTENED = "alicebob"
