package app.skerry.ui.teams

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_accept
import app.skerry.ui.generated.resources.lib_teams_invite_check_failed
import app.skerry.ui.generated.resources.lib_teams_invite_check_retry
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed_ack
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The invite banner is where the invitee's half of the ceremony happens: the inviter's fingerprint is
 * confirmed over a trusted channel, and only then is the team key adopted. Accept used to be live
 * while the check was still running and while it had failed outright, so the ceremony could be
 * skipped by pressing the button early (#319). The coordinator refuses such an accept too — this is
 * the half the user can see.
 */
@OptIn(ExperimentalTestApi::class)
class InviteBannerGateTest {

    @Test
    fun `accept is closed while the invite is still being checked`() {
        var accept = ""
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            InviteBanner(InviteCheck.Pending, busy = false, onAccept = {}, onDecline = {})
        }) {
            onNodeWithText(accept).assertIsNotEnabled()
        }
    }

    @Test
    fun `accept is closed for an invite that does not verify`() {
        var accept = ""
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            InviteBanner(InviteCheck.Unverified, busy = false, onAccept = {}, onDecline = {})
        }) {
            onNodeWithText(accept).assertIsNotEnabled()
        }
    }

    /**
     * The same closed button, a different sentence: a check that could not run says so, instead of
     * telling the user their colleague sent something forged.
     */
    @Test
    fun `a check that could not run says so, names the cause, and still closes accept`() {
        var accept = ""
        var failed = ""
        var cause = ""
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            failed = stringResource(Res.string.lib_teams_invite_check_failed)
            cause = teamsFailureText(TeamsFailure.NotConnected)
            InviteBanner(InviteCheck.Failed(TeamsFailure.NotConnected), busy = false, onAccept = {}, onDecline = {})
        }) {
            onNodeWithText(accept).assertIsNotEnabled()
            // Both halves in one line: this banner is the only thing that says it, so it has to say
            // what could not be done and why.
            onNodeWithText(failed, substring = true).assertExists()
            onNodeWithText(cause, substring = true).assertExists()
        }
    }

    @Test
    fun `a verified invite opens accept and shows the fingerprint to confirm`() {
        var accept = ""
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            InviteBanner(
                InviteCheck.Verified(InvitePreview(INVITER, FINGERPRINT)),
                busy = false,
                onAccept = {},
                onDecline = {},
            )
        }) {
            onNodeWithText(accept).assertIsEnabled()
            onNodeWithText(FINGERPRINT, substring = true).assertExists()
        }
    }

    /** An inviter whose key differs from the pinned one is still acceptable — after a warning. */
    @Test
    fun `a fingerprint that moved is called out on the banner`() {
        var warning = ""
        runForm({
            warning = stringResource(Res.string.lib_teams_invite_key_changed)
            InviteBanner(
                InviteCheck.Verified(InvitePreview(INVITER, FINGERPRINT, keyChanged = true)),
                busy = false,
                onAccept = {},
                onDecline = {},
            )
        }) {
            onNodeWithText(warning).assertExists()
        }
    }

    /**
     * A key that moved is the one case where Accept is a decision rather than a formality: it
     * replaces the pinned fingerprint. Costing the same single click as an unchanged key made the
     * warning decoration — the acknowledgement is the gesture that carries what the trusted channel
     * said.
     */
    @Test
    fun `an inviter whose key moved is acknowledged before accept opens`() {
        var accept = ""
        var ack = ""
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            InviteBanner(
                InviteCheck.Verified(InvitePreview(INVITER, FINGERPRINT, keyChanged = true)),
                busy = false,
                onAccept = {},
                onDecline = {},
            )
        }) {
            onNodeWithText(accept).assertIsNotEnabled()
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()
            onNodeWithText(accept).assertIsEnabled()
        }
    }

    /**
     * The screen behind an unanswered invite offers no sync of its own, so a check that could not be
     * made had nothing to re-run it: the banner carries its own retry.
     */
    @Test
    fun `a check that could not run can be run again`() {
        var retry = ""
        var attempts = 0
        runForm({
            retry = stringResource(Res.string.lib_teams_invite_check_retry)
            InviteBanner(
                InviteCheck.Failed(TeamsFailure.NotConnected),
                busy = false,
                onAccept = {},
                onDecline = {},
                onRetry = { attempts += 1 },
            )
        }) {
            onNodeWithText(retry).performClick()
            waitForIdle()
        }
        assertEquals(1, attempts)
    }

    /**
     * And keeps it across a re-check. The check re-runs on every reread of the Teams screen — an
     * action on any other team bumps the same counter — and visits Pending on the way there. A tick
     * remembered per state was cleared by that trip and asked again for a fingerprint that had not
     * moved since the user confirmed it over the phone.
     */
    @Test
    fun `an acknowledgement survives a re-check that answers the same fingerprint`() {
        var accept = ""
        var ack = ""
        val verified = InviteCheck.Verified(InvitePreview(INVITER, FINGERPRINT, keyChanged = true))
        val check = mutableStateOf<InviteCheck>(verified)
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            InviteBanner(check.value, busy = false, onAccept = {}, onDecline = {})
        }) {
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()
            onNodeWithText(accept).assertIsEnabled()

            runOnIdle { check.value = InviteCheck.Pending }
            waitForIdle()
            runOnIdle { check.value = InviteCheck.Verified(InvitePreview(INVITER, FINGERPRINT, keyChanged = true)) }
            waitForIdle()

            onNodeWithText(accept).assertIsEnabled()
        }
    }

    /** A fingerprint that moved again is a new question, and the old tick answers none of it. */
    @Test
    fun `an acknowledgement does not carry over to another fingerprint`() {
        var accept = ""
        var ack = ""
        val check = mutableStateOf<InviteCheck>(InviteCheck.Verified(InvitePreview(INVITER, FINGERPRINT, keyChanged = true)))
        runForm({
            accept = stringResource(Res.string.lib_teams_accept)
            ack = stringResource(Res.string.lib_teams_invite_key_changed_ack)
            InviteBanner(check.value, busy = false, onAccept = {}, onDecline = {})
        }) {
            onNodeWithContentDescription(ack).performClick()
            waitForIdle()

            runOnIdle { check.value = InviteCheck.Verified(InvitePreview(INVITER, OTHER_FINGERPRINT, keyChanged = true)) }
            waitForIdle()

            onNodeWithText(accept).assertIsNotEnabled()
        }
    }

    private companion object {
        const val INVITER = "bob@example.com"
        const val OTHER_FINGERPRINT = "SKY-9999 8888 7777 6666"
        const val FINGERPRINT = "SKY-1111 2222 3333 4444"
    }
}
