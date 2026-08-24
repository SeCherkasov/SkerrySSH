package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.Pin
import app.skerry.shared.team.PinNotice
import app.skerry.shared.team.PinOrigin
import app.skerry.ui.design.GlyphButton
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed
import app.skerry.ui.generated.resources.lib_teams_key_changed_unconfirmed
import app.skerry.ui.generated.resources.lib_teams_key_pin_unreadable
import app.skerry.ui.generated.resources.lib_teams_peer_confirm
import app.skerry.ui.generated.resources.lib_teams_peer_confirmed
import app.skerry.ui.generated.resources.lib_teams_peer_mark
import app.skerry.ui.generated.resources.lib_teams_peer_state_confirmed
import app.skerry.ui.generated.resources.lib_teams_peer_state_first_sight
import app.skerry.ui.generated.resources.lib_teams_peer_state_none
import app.skerry.ui.generated.resources.lib_teams_peer_state_unreadable
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// What this account holds for a colleague's Teams key, in the two shapes the screens ask about it
// (#323). Both are decided here, away from the composables, because the whole point is that one word
// — "confirmed" — may only ever describe a fingerprint a human read out loud; a `when` copied into
// two views is a `when` that drifts.

/**
 * What is on record for one member, as the member list draws it.
 *
 * [CONFIRMED] and the rest are not degrees of the same thing: a first sight is whatever the server
 * answered the first time something was sealed to the account, held to from then on but vouched for
 * by nobody. Until #322 the record could not tell the two apart, so an invite ceremony and a server's
 * first answer produced the same state and the screens called both of them confirmed.
 */
enum class PeerTrust {
    /** This account. There is no ceremony with oneself, and the member list offers none. */
    SELF,

    /** A fingerprint a human read out loud: the invite ceremony, or the member list's own confirm. */
    CONFIRMED,

    /** Whatever the server answered on the first seal to this account — nobody has confirmed it. */
    FIRST_SIGHT,

    /** Nothing on record: this account has never sealed anything to them. */
    NONE,

    /** A record exists and this device cannot read it — a locked vault, or a payload that stopped opening. */
    UNREADABLE,

    /**
     * Whose row this is cannot be told: there is no live session, so [TeamsCoordinator.selfAccountId]
     * answers nothing. The row makes no claim and offers no ceremony — treating "unknown" as "not me"
     * is what put the amber mark on the reader's own row.
     */
    UNKNOWN,
}

/**
 * What [pin] says about a member. [self] is whether this is the reader's own row — null when the
 * screen cannot tell, which is not the same answer as "no".
 */
fun peerTrust(pin: Pin, self: Boolean?): PeerTrust = when {
    self == null -> PeerTrust.UNKNOWN
    self -> PeerTrust.SELF
    pin is Pin.Known && pin.origin == PinOrigin.CONFIRMED -> PeerTrust.CONFIRMED
    pin is Pin.Known -> PeerTrust.FIRST_SIGHT
    pin == Pin.Unreadable -> PeerTrust.UNREADABLE
    else -> PeerTrust.NONE
}

/**
 * Whether the member list offers the ceremony for this member — every row but one's own and one whose
 * owner cannot be told.
 *
 * A confirmed pin is offered too, and that is the point: a colleague who rotates their identity leaves
 * a confirmed pin that no longer matches their published key, every seal to them is refused from then
 * on, and the ceremony is the only way back. Withholding it there left the error's own instruction —
 * "confirm it in the member list" — pointing at nothing (#323).
 */
val PeerTrust.confirmable: Boolean get() = this != PeerTrust.SELF && this != PeerTrust.UNKNOWN

/** The warning a notice draws next to the fingerprint, or null when there is nothing to warn about. */
@Composable
internal fun pinNoticeText(notice: PinNotice): String? = when (notice) {
    PinNotice.NOTHING -> null
    PinNotice.MOVED_FROM_CONFIRMED -> stringResource(Res.string.lib_teams_invite_key_changed)
    PinNotice.MOVED_FROM_FIRST_SIGHT -> stringResource(Res.string.lib_teams_key_changed_unconfirmed)
    PinNotice.UNREADABLE -> stringResource(Res.string.lib_teams_key_pin_unreadable)
}

/** What the confirm dialog states about the account before the user reads the fingerprint out loud. */
@Composable
internal fun peerTrustText(trust: PeerTrust): String? = when (trust) {
    PeerTrust.SELF, PeerTrust.UNKNOWN -> null
    PeerTrust.CONFIRMED -> stringResource(Res.string.lib_teams_peer_state_confirmed)
    PeerTrust.FIRST_SIGHT -> stringResource(Res.string.lib_teams_peer_state_first_sight)
    PeerTrust.NONE -> stringResource(Res.string.lib_teams_peer_state_none)
    PeerTrust.UNREADABLE -> stringResource(Res.string.lib_teams_peer_state_unreadable)
}

/** What a member list needs to draw its marks: whose row is whose, and what is pinned for each. */
internal class MemberPins(val self: String?, val pins: Map<String, Pin>)

/**
 * [MemberPins] for [accountIds], off the frame — null until the vault has answered, and then no row
 * claims anything about a key.
 *
 * Not a `remember` block: the read takes the account vault's single lock, which every write holds
 * across a whole-file re-serialize and rewrite, and a member with no readable pin costs a record-list
 * scan on top. Done in composition it blocks the frame for the length of a sync merge's commit.
 *
 * One helper for both surfaces — the phone and the desktop must not end up reading this differently.
 */
@Composable
internal fun rememberMemberPins(tc: TeamsCoordinator, accountIds: List<String>, tick: Int): MemberPins? =
    produceState<MemberPins?>(null, accountIds, tick) {
        // Cleared first: `produceState` keeps the previous value across a key change, and a member
        // the refreshed list added is absent from the old map — its row would wear the amber mark of
        // an unconfirmed key until the vault answers, which is a claim about a key nothing read yet.
        value = null
        value = MemberPins(tc.selfAccountId(), tc.peerPins(accountIds))
    }.value

/**
 * The mark a member row wears for its key: a quiet one for a fingerprint a human confirmed, an amber
 * shield for everything else — a first sight, an account nothing was ever sealed to, a record this
 * device cannot read. Both open the ceremony; the own row and a row whose owner cannot be told wear
 * neither.
 *
 * The mark is the whole point of the row change: without it "confirmed" was a word two different
 * states shared, and there was no way to promote one of them short of re-inviting the person (#323).
 */
@Composable
internal fun PeerTrustBadge(trust: PeerTrust, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    if (!trust.confirmable) return
    val confirmed = trust == PeerTrust.CONFIRMED
    val action = stringResource(Res.string.lib_teams_peer_confirm)
    GlyphButton(
        icon = if (confirmed) "verified_user" else "gpp_maybe",
        // The state first when there is one, then what pressing it does: the mark is read as a status
        // and used as a control, and a name carrying only the status describes no purpose (WCAG 4.1.2).
        // Joined by a format string rather than by a literal stop — the punctuation between two
        // sentences belongs to the locale, and zh does not end one with a Latin full stop.
        label = if (confirmed) {
            stringResource(Res.string.lib_teams_peer_mark, stringResource(Res.string.lib_teams_peer_confirmed), action)
        } else {
            action
        },
        onClick = onConfirm,
        modifier = modifier,
        // Small for a row, never below the minimum target size — this is the only way into the
        // ceremony, and the glyph alone is a 14sp mark (WCAG 2.5.8).
        box = 24.dp,
        iconSize = 14.sp,
        iconColor = if (confirmed) Skerry.colors.cyanBright else Skerry.colors.amber,
    )
}
