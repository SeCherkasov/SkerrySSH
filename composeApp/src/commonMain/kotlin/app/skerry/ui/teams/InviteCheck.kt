package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite_check_failed
import app.skerry.ui.generated.resources.lib_teams_invite_checking
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed
import app.skerry.ui.generated.resources.lib_teams_invite_unverified
import app.skerry.ui.generated.resources.lib_teams_invited_by
import app.skerry.ui.generated.resources.lib_teams_invited_fingerprint
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * State of the invite banner's verification: an invite is opened, its signature checked and its
 * inviter's fingerprint derived before Accept means anything.
 *
 * States, not a nullable preview: "still checking" and "could not be verified" both used to read as
 * null while the Accept button stayed live, so pressing it in either state adopted a team key with
 * no ceremony at all (#319). [Pending] is the screen's own; the other three are
 * [InviteVerdict] as the coordinator answered it.
 */
internal sealed interface InviteCheck {
    /** The round trip to the server hasn't come back yet — Accept is not offered. */
    data object Pending : InviteCheck

    /** No envelope, or one that doesn't verify: forged, tampered with, or not addressed to us. */
    data object Unverified : InviteCheck

    /** The check could not be made — [reason] says why. Retried on the next pass. */
    data class Failed(val reason: TeamsFailure) : InviteCheck

    /** Opened and verified: [preview] is the inviter and the fingerprint to confirm out of band. */
    data class Verified(val preview: InvitePreview) : InviteCheck
}

/**
 * The second, deliberate confirmation a moved key costs before Accept opens: [moved] is the
 * fingerprint that has to be acknowledged (null when the key did not move), [acknowledged] whether
 * it was, and [toggle] the control that ticks it.
 */
internal class InviteAcknowledgement(val moved: String?, val acknowledged: Boolean, val toggle: () -> Unit)

/**
 * Holds that acknowledgement for one banner. Remembered as the fingerprint that was acknowledged
 * rather than as a flag: the check is re-run by every reread of the screen and visits [Pending] on
 * the way there, so a flag keyed on the state would be cleared by an action on some other team and
 * ask the user to tick it again. A different fingerprint is a different question, and matches nothing.
 *
 * One holder for both surfaces — a second copy of this is a copy no test on the other surface reads.
 */
@Composable
internal fun rememberInviteAcknowledgement(check: InviteCheck): InviteAcknowledgement {
    var acknowledgedFor by remember { mutableStateOf<String?>(null) }
    val moved = (check as? InviteCheck.Verified)?.preview?.takeIf { it.keyChanged }?.fingerprint
    val acknowledged = moved != null && acknowledgedFor == moved
    return InviteAcknowledgement(moved, acknowledged) { acknowledgedFor = if (acknowledged) null else moved }
}

/**
 * Runs the check for [teamId] and keeps its result for the banner to draw. Re-keyed on [attempt], so
 * a check that could not be made is retried by the same reread that refreshes the rest of the screen
 * — a banner stuck on "could not be checked" until the app restarts would be a worse lie than the
 * accusation it replaced.
 */
@Composable
internal fun rememberInviteCheck(tc: TeamsCoordinator, teamId: String, attempt: Int = 0): InviteCheck =
    produceState<InviteCheck>(InviteCheck.Pending, teamId, attempt) {
        // produceState keeps the previous value across a key change, so without this a re-check
        // leaves the old verdict on screen: pressing Retry offline looked exactly like not pressing
        // it — same sentence, same announcement, no sign anything ran.
        value = InviteCheck.Pending
        value = when (val verdict = tc.acceptPreview(teamId)) {
            is InviteVerdict.Verified -> InviteCheck.Verified(verdict.preview)
            InviteVerdict.Unverified -> InviteCheck.Unverified
            is InviteVerdict.Failed -> InviteCheck.Failed(verdict.reason)
        }
    }.value

/**
 * Why the check could not be made, in one line: the fact, then the cause. The cause is the same
 * sentence [TeamsErrorLine] would have shown — said once, by the banner that asked for the check,
 * instead of twice by two live regions.
 */
@Composable
internal fun inviteCheckFailedText(reason: TeamsFailure): String =
    stringResource(Res.string.lib_teams_invite_check_failed) + " " + teamsFailureText(reason)

/**
 * What a screen reader is told when the check resolves — the same words the banner draws, in one
 * string for [app.skerry.ui.design.StatusAnnouncer].
 *
 * The banner swaps which line it composes per state, and a line that appears is an insertion rather
 * than a change: without this, the one screen that asks the user to trust a key announces neither
 * that the check finished nor that the fingerprint differs from the pinned one (WCAG 4.1.3).
 */
@Composable
internal fun inviteCheckAnnouncement(check: InviteCheck): String = when (check) {
    InviteCheck.Pending -> stringResource(Res.string.lib_teams_invite_checking)
    InviteCheck.Unverified -> stringResource(Res.string.lib_teams_invite_unverified)
    is InviteCheck.Failed -> inviteCheckFailedText(check.reason)
    is InviteCheck.Verified -> {
        val preview = check.preview
        val identity = stringResource(Res.string.lib_teams_invited_by, untrustedLabel(preview.accountId)) + " " +
            stringResource(Res.string.lib_teams_invited_fingerprint, preview.fingerprint)
        if (preview.keyChanged) identity + " " + stringResource(Res.string.lib_teams_invite_key_changed) else identity
    }
}

/** One line of the banner's body: what it says, the tone it says it in, and whether it is a key. */
internal class InviteCheckLine(val text: String, val color: Color, val mono: Boolean = false)

/**
 * The banner's body for a state — shared because there are two banners. Desktop and mobile draw the
 * same sentences at their own type scale, and a hand-copied `when` had them disagreeing about which
 * state says what; the sizes stay at the call site, the words and the tone do not.
 */
@Composable
internal fun inviteCheckLines(check: InviteCheck): List<InviteCheckLine> = when (check) {
    InviteCheck.Pending -> listOf(InviteCheckLine(stringResource(Res.string.lib_teams_invite_checking), Skerry.colors.dim))
    InviteCheck.Unverified -> listOf(InviteCheckLine(stringResource(Res.string.lib_teams_invite_unverified), Skerry.colors.sunset))
    is InviteCheck.Failed -> listOf(InviteCheckLine(inviteCheckFailedText(check.reason), Skerry.colors.amber))
    is InviteCheck.Verified -> buildList {
        val preview = check.preview
        add(InviteCheckLine(stringResource(Res.string.lib_teams_invited_by, untrustedLabel(preview.accountId)), Skerry.colors.dim))
        add(InviteCheckLine(stringResource(Res.string.lib_teams_invited_fingerprint, preview.fingerprint), Skerry.colors.cyanBright, mono = true))
        if (preview.keyChanged) add(InviteCheckLine(stringResource(Res.string.lib_teams_invite_key_changed), Skerry.colors.amber))
    }
}

/**
 * Whether Accept may be pressed — the same rule on both banners, which is the point of it being here.
 *
 * A key that moved needs [acknowledged] on top of a verified envelope: accepting then replaces the
 * pinned fingerprint, so an honest rotation and a server trying its luck look identical on screen
 * and only the person on the trusted channel can tell them apart. Costing the same single click as
 * an unchanged key made the warning decoration (#319).
 */
internal fun readyToAccept(check: InviteCheck, busy: Boolean, acknowledged: Boolean): Boolean =
    !busy && check is InviteCheck.Verified && (!check.preview.keyChanged || acknowledged)
