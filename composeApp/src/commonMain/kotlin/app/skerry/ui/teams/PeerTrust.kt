package app.skerry.ui.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import app.skerry.ui.generated.resources.lib_teams_peer_refused
import app.skerry.ui.generated.resources.lib_teams_peer_state_confirmed
import app.skerry.ui.generated.resources.lib_teams_peer_state_first_sight
import app.skerry.ui.generated.resources.lib_teams_peer_state_none
import app.skerry.ui.generated.resources.lib_teams_peer_state_refused
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

    /**
     * A seal to this account was just refused: the key they publish is not the fingerprint on record
     * for them, whatever that record says about itself. Not derivable from the pin — a colleague who
     * rotated their identity leaves a pin that is still confirmed, so their row drew the same quiet
     * mark as every healthy one while nothing could be sealed to them (#326).
     */
    REFUSED,

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
 *
 * [refused] outranks what the record claims, and only that: a lookup that just refused this account
 * has compared the pin against the published key, which is the one thing the pin alone cannot say.
 *
 * Two states still come first. Whose row it is — a refusal naming this account would otherwise offer
 * a ceremony with oneself. And a pin this device cannot read: the lookup refuses that exactly as it
 * refuses a key that moved, so a refusal is no evidence at all about the colleague's key, and saying
 * theirs is not the fingerprint on record sends the user to verify a change that never happened.
 */
fun peerTrust(pin: Pin, self: Boolean?, refused: Boolean = false): PeerTrust = when {
    self == null -> PeerTrust.UNKNOWN
    self -> PeerTrust.SELF
    pin == Pin.Unreadable -> PeerTrust.UNREADABLE
    refused -> PeerTrust.REFUSED
    pin is Pin.Known && pin.origin == PinOrigin.CONFIRMED -> PeerTrust.CONFIRMED
    pin is Pin.Known -> PeerTrust.FIRST_SIGHT
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
    PeerTrust.REFUSED -> stringResource(Res.string.lib_teams_peer_state_refused)
    PeerTrust.CONFIRMED -> stringResource(Res.string.lib_teams_peer_state_confirmed)
    PeerTrust.FIRST_SIGHT -> stringResource(Res.string.lib_teams_peer_state_first_sight)
    PeerTrust.NONE -> stringResource(Res.string.lib_teams_peer_state_none)
    PeerTrust.UNREADABLE -> stringResource(Res.string.lib_teams_peer_state_unreadable)
}

/**
 * What a member list needs to draw its marks: whose row is whose, what is pinned for each, and which
 * of them a seal was just refused for — the one thing the pins cannot say, since a refused key and a
 * healthy one leave the same record behind (#326).
 */
internal class MemberPins(val self: String?, val pins: Map<String, Pin>, val refused: Set<String>)

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
internal fun rememberMemberPins(tc: TeamsCoordinator, accountIds: List<String>, tick: Int): MemberPins? {
    // Collected rather than read inside the vault pass: a refusal lands when an operation ends, which
    // is not when the list is rekeyed, and a mark that waits for the next reread points at nothing
    // while the error it belongs to is already on screen.
    val refused by tc.refusedPeers.collectAsState()
    val read = produceState<Pair<String?, Map<String, Pin>>?>(null, accountIds, tick) {
        // Cleared first: `produceState` keeps the previous value across a key change, and a member
        // the refreshed list added is absent from the old map — its row would wear the amber mark of
        // an unconfirmed key until the vault answers, which is a claim about a key nothing read yet.
        value = null
        value = tc.selfAccountId() to tc.peerPins(accountIds)
    }.value
    // Remembered, not rebuilt: the screens key their row list on this value and it has no equality of
    // its own, so a fresh instance per recomposition re-sorts and re-maps every member on each frame
    // the surrounding screen redraws for anything at all — the busy flag, a sync stamp.
    return remember(read, refused) { read?.let { (self, pins) -> MemberPins(self, pins, refused) } }
}

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
    val action = stringResource(Res.string.lib_teams_peer_confirm)
    val state = when (trust) {
        PeerTrust.CONFIRMED -> stringResource(Res.string.lib_teams_peer_confirmed)
        PeerTrust.REFUSED -> stringResource(Res.string.lib_teams_peer_refused)
        else -> null
    }
    GlyphButton(
        icon = when (trust) {
            PeerTrust.CONFIRMED -> "verified_user"
            // Its own glyph and the danger tone, not the amber one every first sight wears: the error
            // says to confirm a fingerprint in the member list, and a mark shared with each unrelated
            // row names nobody (#326).
            PeerTrust.REFUSED -> "gpp_bad"
            else -> "gpp_maybe"
        },
        // The state first when there is one, then what pressing it does: the mark is read as a status
        // and used as a control, and a name carrying only the status describes no purpose (WCAG 4.1.2).
        // Joined by a format string rather than by a literal stop — the punctuation between two
        // sentences belongs to the locale, and zh does not end one with a Latin full stop.
        label = if (state != null) stringResource(Res.string.lib_teams_peer_mark, state, action) else action,
        onClick = onConfirm,
        modifier = modifier,
        // Small for a row, never below the minimum target size — this is the only way into the
        // ceremony, and the glyph alone is a 14sp mark (WCAG 2.5.8).
        box = 24.dp,
        iconSize = 14.sp,
        iconColor = when (trust) {
            PeerTrust.CONFIRMED -> Skerry.colors.cyanBright
            PeerTrust.REFUSED -> Skerry.colors.sunset
            else -> Skerry.colors.amber
        },
    )
}
