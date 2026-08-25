package app.skerry.ui.teams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.skerry.shared.team.TeamMemberStatus
import app.skerry.shared.team.TeamRole
import app.skerry.ui.app.UiTags
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed
import app.skerry.ui.generated.resources.lib_teams_invite_check_retry
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed_ack
import app.skerry.ui.generated.resources.lib_teams_key_changed_unconfirmed
import app.skerry.ui.generated.resources.lib_teams_key_pin_unreadable
import app.skerry.ui.generated.resources.lib_teams_peer_state_confirmed
import app.skerry.ui.generated.resources.lib_teams_peer_state_first_sight
import app.skerry.ui.generated.resources.lib_teams_peer_state_refused
import app.skerry.ui.desktop.runForm
import org.jetbrains.compose.resources.stringResource
import app.skerry.shared.team.Pin
import app.skerry.shared.team.PinOrigin
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
        var sent: Pair<InvitePreview, TeamRole>? = null
        runForm({
            InviteMemberDialog(
                preview = null,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = { lookedUp = it },
                onEdited = {},
                onSend = { verified, role -> sent = verified to role },
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
        var sent: Pair<InvitePreview, TeamRole>? = null
        runForm({
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT, pinned = Pin.None),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = {},
                onSend = { verified, role -> sent = verified to role },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(ACCOUNT)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(ACCOUNT, sent?.first?.accountId)
        assertEquals(PEER_FINGERPRINT, sent?.first?.fingerprint, "the send must carry the verified fingerprint")
        assertEquals(TeamRole.VIEWER, sent?.second, "the invite should default to the least privilege")
    }

    /**
     * A fingerprint that differs from the one pinned for the account is either an honest identity
     * rotation or the server substituting a key of its own. Sending replaces the pin, so the dialog
     * has to say which fingerprint the user is about to promote to verified (#319).
     */
    @Test
    fun `an account whose key moved says so before the invite is sent`() {
        var warning = ""
        runForm({
            warning = stringResource(Res.string.lib_teams_invite_key_changed)
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT, pinned = Pin.Known(MOVED_FROM, PinOrigin.CONFIRMED)),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = {},
                onSend = { _, _ -> },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(ACCOUNT)
            waitForIdle()
            onNodeWithText(warning).assertExists()
        }
    }

    /**
     * And says it at the price of a second, deliberate gesture: sending promotes the new fingerprint
     * to the pinned one, so an honest rotation and a server trying its luck reach this dialog looking
     * the same. One click could not carry the difference the trusted channel establishes.
     */
    @Test
    fun `an account whose key moved cannot be invited on one click`() {
        var ack = ""
        var sent: InvitePreview? = null
        runForm({
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT, pinned = Pin.Known(MOVED_FROM, PinOrigin.CONFIRMED)),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = {},
                onSend = { verified, _ -> sent = verified },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(ACCOUNT)
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(PEER_FINGERPRINT, sent?.fingerprint, "the send carries the fingerprint that was acknowledged")
    }

    /** The usual case — a key nobody has pinned a different value for — must not cry wolf. */
    @Test
    fun `a first invite to an account carries no key-changed warning`() {
        var warning = ""
        runForm({
            warning = stringResource(Res.string.lib_teams_invite_key_changed)
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT, pinned = Pin.None),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = {},
                onSend = { _, _ -> },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(ACCOUNT)
            waitForIdle()
            onNodeWithText(warning).assertDoesNotExist()
        }
    }

    /**
     * The member list's own ceremony (#323). A first sight is what the server answered when something
     * was first sealed to the account: nothing about it has to be acknowledged, it simply has never
     * been vouched for, and confirming is exactly that — the fingerprint on screen, read out loud.
     */
    @Test
    fun `a first sight key is confirmed with the fingerprint that is on screen`() {
        var confirmed: InvitePreview? = null
        runForm({
            ConfirmPeerKeyDialog(
                accountId = ACCOUNT,
                check = PeerKeyVerdict.Ready(
                    InvitePreview(ACCOUNT, PEER_FINGERPRINT, pinned = Pin.Known(PEER_FINGERPRINT, PinOrigin.FIRST_SIGHT)),
                ),
                trust = PeerTrust.FIRST_SIGHT,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onRetry = {},
                onConfirm = { confirmed = it },
                onDismiss = {},
            )
        }) {
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(PEER_FINGERPRINT, confirmed?.fingerprint)
    }

    /**
     * A key that moved costs the same second gesture here as it does on the invite — and says so in
     * its own words: neither the record nor the key on screen was ever confirmed by anyone, which is
     * not the sentence a moved *confirmed* pin gets.
     */
    @Test
    fun `a key that moved from a first sight is worded as such and still costs the acknowledgement`() {
        var warning = ""
        var confirmedWording = ""
        var ack = ""
        var confirmed: InvitePreview? = null
        runForm({
            warning = stringResource(Res.string.lib_teams_key_changed_unconfirmed)
            confirmedWording = stringResource(Res.string.lib_teams_invite_key_changed)
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            ConfirmPeerKeyDialog(
                accountId = ACCOUNT,
                check = PeerKeyVerdict.Ready(
                    InvitePreview(ACCOUNT, PEER_FINGERPRINT, pinned = Pin.Known(MOVED_FROM, PinOrigin.FIRST_SIGHT)),
                ),
                trust = PeerTrust.FIRST_SIGHT,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onRetry = {},
                onConfirm = { confirmed = it },
                onDismiss = {},
            )
        }) {
            waitForIdle()
            onNodeWithText(warning).assertExists()
            onNodeWithText(confirmedWording).assertDoesNotExist()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(PEER_FINGERPRINT, confirmed?.fingerprint, "the confirm carries the fingerprint that was acknowledged")
    }

    /**
     * A record this device cannot read has nothing to compare the fingerprint against, so it asks the
     * same second question a moved pin does — the fail-closed branch `pinNotice` exists for.
     */
    @Test
    fun `a pin this device cannot read still costs the acknowledgement`() {
        var warning = ""
        var ack = ""
        var confirmed: InvitePreview? = null
        runForm({
            warning = stringResource(Res.string.lib_teams_key_pin_unreadable)
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            ConfirmPeerKeyDialog(
                accountId = ACCOUNT,
                check = PeerKeyVerdict.Ready(InvitePreview(ACCOUNT, PEER_FINGERPRINT, pinned = Pin.Unreadable)),
                trust = PeerTrust.UNREADABLE,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onRetry = {},
                onConfirm = { confirmed = it },
                onDismiss = {},
            )
        }) {
            waitForIdle()
            onNodeWithText(warning).assertExists()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(PEER_FINGERPRINT, confirmed?.fingerprint)
    }

    /**
     * The one fact the decision turns on has to be spoken. What the record already held arrives from
     * a vault read that starts when the dialog mounts, so its line is inserted after a screen reader
     * has read the dialog — and a node inserted with its text announces nothing. It goes into the
     * live region with the fingerprint, or the user confirming a key is never told whether they are
     * replacing something a human vouched for (WCAG 4.1.3).
     */
    @Test
    fun `the dialog announces what the record held, not only the fingerprint`() {
        var state by mutableStateOf(PeerTrust.UNKNOWN)
        var wording = ""
        runForm({
            wording = stringResource(Res.string.lib_teams_peer_state_first_sight)
            ConfirmPeerKeyDialog(
                accountId = ACCOUNT,
                check = PeerKeyVerdict.Ready(
                    InvitePreview(ACCOUNT, PEER_FINGERPRINT, pinned = Pin.Known(PEER_FINGERPRINT, PinOrigin.FIRST_SIGHT)),
                ),
                trust = state,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onRetry = {},
                onConfirm = {},
                onDismiss = {},
            )
        }) {
            waitForIdle()
            state = PeerTrust.FIRST_SIGHT
            waitForIdle()
            onNodeWithContentDescription(wording, substring = true).assertExists()
            onNodeWithContentDescription(PEER_FINGERPRINT, substring = true).assertExists()
        }
    }

    /** Nothing is confirmable before the key is on screen, and a lookup that failed offers a retry. */
    @Test
    fun `a key still being fetched confirms nothing, and a failed one retries`() {
        var retried = 0
        var check by mutableStateOf<PeerKeyVerdict?>(null)
        var retry = ""
        runForm({
            retry = stringResource(Res.string.lib_teams_invite_check_retry)
            ConfirmPeerKeyDialog(
                accountId = ACCOUNT,
                check = check,
                trust = PeerTrust.NONE,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onRetry = { retried += 1 },
                onConfirm = {},
                onDismiss = {},
            )
        }) {
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            check = PeerKeyVerdict.Failed(TeamsFailure.NotConnected)
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onNodeWithText(retry).performClick()
            waitForIdle()
        }
        assertEquals(1, retried)
    }

    /**
     * Editing the id after a lookup makes the fingerprint on screen the wrong one. The dialog says so
     * through [onEdited], and the press must go back to being a lookup rather than a send.
     */
    @Test
    fun `changing the account after a lookup does not send to the old key`() {
        var edited = false
        var sent: Pair<InvitePreview, TeamRole>? = null
        runForm({
            InviteMemberDialog(
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT, pinned = Pin.None),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                assignableRoles = listOf(TeamRole.ADMIN, TeamRole.VIEWER),
                onLookup = {},
                onEdited = { edited = true },
                onSend = { verified, role -> sent = verified to role },
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

    /**
     * The team list is the server's answer and is refreshed under an open dialog: it reorders on a
     * membership signal, and the team the screen shows as selected moves with it. The invite must
     * still go to the team the dialog was opened for, or the server picks whose key gets shared.
     */
    @Test
    fun `the invite goes to the team the dialog was opened for, not the first one in the list`() {
        var teams by mutableStateOf(listOf(team(TEAM_ID), team(OTHER_TEAM_ID)))
        var sentTo: String? = null
        runForm({
            InviteMemberDialogForTeam(
                teams = teams,
                teamId = TEAM_ID,
                preview = InvitePreview(accountId = ACCOUNT, fingerprint = PEER_FINGERPRINT, pinned = Pin.None),
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onLookup = {},
                onEdited = {},
                onSend = { id, _, _ -> sentTo = id },
                onDismiss = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextReplacement(ACCOUNT)
            // The server answers a refresh while the user is still reading the fingerprint out loud.
            teams = listOf(team(OTHER_TEAM_ID), team(TEAM_ID))
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(TEAM_ID, sentTo)
    }

    /**
     * A team that disappeared from the list takes its dialog with it — and the screen has to be told,
     * or it keeps the vanished id and reopens the dialog the moment a team is re-invited under it.
     */
    @Test
    fun `a team gone from the list closes its invite dialog and clears the screen's state`() {
        var dismissed = false
        runForm({
            InviteMemberDialogForTeam(
                teams = listOf(team(OTHER_TEAM_ID)),
                teamId = TEAM_ID,
                preview = null,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onLookup = {},
                onEdited = {},
                onSend = { _, _, _ -> },
                onDismiss = { dismissed = true },
            )
        }) {
            waitForIdle()
            onAllNodesWithTag(UiTags.FORM_SAVE).fetchSemanticsNodes().let {
                assertTrue(it.isEmpty(), "the dialog stayed up for a team that is gone")
            }
        }
        assertTrue(dismissed, "the screen was never told the dialog is gone")
    }

    /**
     * The row the refusal was about opens this dialog, and it has to say so: the pin behind a
     * colleague who rotated their Teams identity still reads as confirmed, so wording the state off
     * the pin alone would greet a refused key with "confirmed" (#326).
     */
    @Test
    fun `a refused key is worded as refused, not as the confirmed pin behind it`() {
        var refusedWording = ""
        var confirmedWording = ""
        var ack = ""
        var confirmed: InvitePreview? = null
        runForm({
            refusedWording = stringResource(Res.string.lib_teams_peer_state_refused)
            confirmedWording = stringResource(Res.string.lib_teams_peer_state_confirmed)
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            ConfirmPeerKeyDialog(
                accountId = ACCOUNT,
                check = PeerKeyVerdict.Ready(
                    InvitePreview(ACCOUNT, PEER_FINGERPRINT, pinned = Pin.Known(MOVED_FROM, PinOrigin.CONFIRMED)),
                ),
                trust = PeerTrust.REFUSED,
                ownFingerprint = OWN_FINGERPRINT,
                busy = false,
                onRetry = {},
                onConfirm = { confirmed = it },
                onDismiss = {},
            )
        }) {
            waitForIdle()
            onNodeWithContentDescription(refusedWording, substring = true).assertExists()
            onNodeWithContentDescription(confirmedWording, substring = true).assertDoesNotExist()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled() // the refused key still moved (#323)
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(PEER_FINGERPRINT, confirmed?.fingerprint)
    }
}

private fun team(id: String) = TeamUi(
    id = id,
    name = id,
    ownerAccountId = ACCOUNT,
    role = TeamRole.OWNER,
    status = TeamMemberStatus.ACTIVE,
    memberCount = 1,
    hasKey = true,
)

private const val TEAM = "platform"
private const val TEAM_ID = "team-opened-for"
private const val OTHER_TEAM_ID = "team-that-jumped-first"
private const val ACCOUNT = "alice@example.com"
private const val OTHER_ACCOUNT = "mallory@example.com"
private const val OWN_FINGERPRINT = "SHA256:ownownownownownownownownownownownownownownow"
private const val PEER_FINGERPRINT = "SHA256:peerpeerpeerpeerpeerpeerpeerpeerpeerpeerpeer"

/** What the account was confirmed under before — so the fingerprint on screen has moved. */
private const val MOVED_FROM = "SHA256:oldoldoldoldoldoldoldoldoldoldoldoldoldoldold"
