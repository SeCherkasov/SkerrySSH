package app.skerry.ui.teams

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.skerry.shared.team.TeamRole
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The team dialogs, rendered without a coordinator behind them.
 *
 * The invite is the one with a rule worth defending: an invitation may only be sent to a key the
 * user has actually seen the fingerprint of, so editing the account id after the lookup has to shut
 * the send again — otherwise the fingerprint on screen belongs to someone else's account.
 */
@OptIn(ExperimentalTestApi::class)
class TeamsDialogFormTest {

    @Test
    fun `creating a team passes the typed name on`() {
        var created: String? = null
        runForm({ CreateTeamDialog(onDismiss = {}, onCreate = { created = it }) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(TEAM)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(TEAM, created)
    }

    @Test
    fun `a team name of spaces is refused`() {
        var created: String? = null
        runForm({ CreateTeamDialog(onDismiss = {}, onCreate = { created = it }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput("   ")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertNull(created)
    }

    /** The first press looks the account up; there is nothing to send until its key comes back. */
    @Test
    fun `an invite is looked up before it can be sent`() {
        var lookedUp: String? = null
        var sent: Pair<String, TeamRole>? = null
        runForm({
            InviteMemberDialog(
                preview = null,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = { lookedUp = it },
                onEdited = {},
                onSend = { id, role -> sent = id to role },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(ACCOUNT)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(ACCOUNT, lookedUp, "the first press should look the account up")
        assertNull(sent, "an invite went out before the key was seen")
    }

    /** With the looked-up key on screen the same button sends, and sends the role that is selected. */
    @Test
    fun `an invite is sent once its key is on screen`() {
        var sent: Pair<String, TeamRole>? = null
        runForm({
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = {},
                onSend = { id, role -> sent = id to role },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(ACCOUNT)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(ACCOUNT, sent?.first)
        assertEquals(TeamRole.VIEWER, sent?.second, "the invite should default to the least privilege")
    }

    /**
     * Editing the id after a lookup makes the fingerprint on screen the wrong one. The dialog says so
     * through [onEdited], and the press must go back to being a lookup rather than a send.
     */
    @Test
    fun `changing the account after a lookup does not send to the old key`() {
        var edited = false
        var sent: Pair<String, TeamRole>? = null
        runForm({
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = { edited = true },
                onSend = { id, role -> sent = id to role },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(OTHER_ACCOUNT)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertTrue(edited, "the dialog did not report the id being edited")
        assertNull(sent, "the invite went to the account whose key was on screen, not the typed one")
    }
}

private const val TEAM = "platform"
private const val ACCOUNT = "alice@example.com"
private const val OTHER_ACCOUNT = "mallory@example.com"
private const val OWN_FINGERPRINT = "SHA256:ownownownownownownownownownownownownownownow"
private const val PEER_FINGERPRINT = "SHA256:peerpeerpeerpeerpeerpeerpeerpeerpeerpeerpeer"
