package app.skerry.ui.teams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.Pin
import app.skerry.shared.team.pinNotice
import app.skerry.ui.app.UiTags
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.ToggleRow
import app.skerry.ui.design.Txt
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_teams_invite_check_retry
import app.skerry.ui.generated.resources.lib_teams_invite_fingerprint
import app.skerry.ui.generated.resources.lib_teams_invite_key_changed_ack
import app.skerry.ui.generated.resources.lib_teams_peer_check_failed
import app.skerry.ui.generated.resources.lib_teams_peer_checking
import app.skerry.ui.generated.resources.lib_teams_peer_confirm
import app.skerry.ui.generated.resources.lib_teams_peer_confirm_action
import app.skerry.ui.generated.resources.lib_teams_peer_verify
import app.skerry.ui.generated.resources.lib_teams_your_fingerprint
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * The fingerprint block both ceremonies draw: the key, the instruction to read it back over a
 * channel the server does not own, whatever the record has to say against it, and this account's own
 * fingerprint so the other side can check back.
 *
 * One composable because the two ceremonies are the same ceremony — the invite dialog's second step
 * and the member list's confirmation (#323). A second copy is a copy that drifts, and the thing it
 * would drift on is which of these lines a key that moved is allowed to skip.
 */
@Composable
internal fun FingerprintCeremony(
    fingerprint: String,
    /** What the user is asked to do with the fingerprint — the one line that differs between the two. */
    instruction: String,
    /** What the record says against this fingerprint; null when it agrees or there is none. */
    notice: String?,
    acknowledged: Boolean,
    onAcknowledge: () -> Unit,
    ownFingerprint: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.cyan.copy(alpha = 0.06f))
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp))
            .padding(12.dp),
    ) {
        Txt(
            stringResource(Res.string.lib_teams_invite_fingerprint).uppercase(),
            color = Skerry.colors.faint, size = 10.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        )
        Txt(fingerprint, color = Skerry.colors.cyanBright, size = 14.sp, font = LocalFonts.current.mono, modifier = Modifier.padding(top = 4.dp))
        Txt(instruction, color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        // A record that disagrees costs a second, deliberate gesture: confirming replaces it, and an
        // honest rotation and a server trying its luck reach this screen looking identical. One
        // click could not carry the difference — the person on the trusted channel can (#319).
        if (notice != null) {
            Txt(notice, color = Skerry.colors.amber, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
            ToggleRow(
                label = stringResource(Res.string.lib_teams_invite_key_changed_ack),
                on = acknowledged,
                onToggle = onAcknowledge,
                labelSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (ownFingerprint != null) {
            Txt(
                stringResource(Res.string.lib_teams_your_fingerprint, ownFingerprint),
                color = Skerry.colors.faint, size = 11.sp, font = LocalFonts.current.mono,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Fetches the published key of [accountId] for the confirm dialog and keeps the answer. Re-keyed on
 * [attempt] so Retry re-runs it; the value is cleared first because `produceState` keeps the previous
 * one across a key change, and a retry that redraws the same failure looks like a button that does
 * nothing.
 */
@Composable
internal fun rememberPeerKeyCheck(tc: TeamsCoordinator, accountId: String, attempt: Int): PeerKeyVerdict? =
    produceState<PeerKeyVerdict?>(null, accountId, attempt) {
        value = null
        value = tc.peerKey(accountId)
    }.value

/**
 * Confirm a colleague's Teams key from the member list — the ceremony that was only reachable by
 * inviting someone (#323).
 *
 * A scope grant and a key rotation seal to whatever the server first answered with and show no
 * fingerprint at all, so the record could only ever say "this is what we saw". This is where a human
 * reads one out loud and the record starts saying "confirmed" — the word the screens use.
 *
 * [check] null means the lookup has not answered yet. Nothing is confirmable until it has, and a
 * failure is stated here rather than in the screen's error line: the dialog is over that line, and
 * two live regions saying one thing is what the invite banner was fixed for (WCAG 4.1.3).
 */
@Composable
internal fun ConfirmPeerKeyDialog(
    accountId: String,
    check: PeerKeyVerdict?,
    /** What the record held when the dialog opened, stated before the fingerprint is read out loud. */
    trust: PeerTrust,
    ownFingerprint: String?,
    busy: Boolean,
    onRetry: () -> Unit,
    onConfirm: (InvitePreview) -> Unit,
    onDismiss: () -> Unit,
) {
    val ready = (check as? PeerKeyVerdict.Ready)?.preview
    // Keyed on the fingerprint, not on the dialog: the tick belongs to the key it was given for, and
    // a retry answering with a different one asks the question again.
    var acknowledged by remember(ready?.fingerprint) { mutableStateOf(false) }
    val notice = ready?.let { pinNoticeText(pinNotice(it.pinned, it.fingerprint)) }
    val blocked = busy || ready == null || (notice != null && !acknowledged)
    val title = stringResource(Res.string.lib_teams_peer_confirm)
    val failed = (check as? PeerKeyVerdict.Failed)?.let {
        stringResource(Res.string.lib_teams_peer_check_failed) + " " + teamsFailureText(it.reason)
    }
    val state = peerTrustText(trust)
    TeamsDialogCard(onDismiss, label = title) {
        Txt(title, color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
        Txt(
            untrustedLabel(accountId),
            color = Skerry.colors.dim, size = 12.5.sp, font = LocalFonts.current.mono,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state != null) {
            Txt(
                state,
                color = if (trust == PeerTrust.CONFIRMED) Skerry.colors.dim else Skerry.colors.amber,
                size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        // Composed above the block it describes, not inside it: the fingerprint arrives after the
        // dialog is already up, and a node inserted together with its text announces nothing. The
        // state line is carried here for the same reason and no other — it is the one fact the
        // decision turns on, and it lands from a vault read that starts when the dialog mounts.
        StatusAnnouncer(
            listOfNotNull(
                state,
                when {
                    failed != null -> failed
                    ready == null -> stringResource(Res.string.lib_teams_peer_checking)
                    else -> stringResource(Res.string.lib_teams_invite_fingerprint) + " " + ready.fingerprint +
                        (notice?.let { " $it" } ?: "")
                },
            ).joinToString(" "),
        )
        when {
            failed != null -> Txt(failed, color = Skerry.colors.amber, size = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp))
            ready == null -> Txt(stringResource(Res.string.lib_teams_peer_checking), color = Skerry.colors.dim, size = 12.sp, modifier = Modifier.padding(top = 12.dp))
            else -> FingerprintCeremony(
                fingerprint = ready.fingerprint,
                instruction = stringResource(Res.string.lib_teams_peer_verify),
                notice = notice,
                acknowledged = acknowledged,
                onAcknowledge = { acknowledged = !acknowledged },
                ownFingerprint = ownFingerprint,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (check is PeerKeyVerdict.Failed) {
                GhostButton(stringResource(Res.string.lib_teams_invite_check_retry), onClick = onRetry, fg = Skerry.colors.amber)
            }
            CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss, modifier = Modifier.testTag(UiTags.FORM_CANCEL))
            PrimaryButton(
                stringResource(Res.string.lib_teams_peer_confirm_action),
                onClick = { ready?.let(onConfirm) },
                enabled = !blocked,
                modifier = Modifier.testTag(UiTags.FORM_SAVE),
            )
        }
    }
}

/**
 * [ConfirmPeerKeyDialog] wired to its own lookup and its own Retry — the shape both screens mount,
 * so the phone and the desktop cannot end up asking the question differently.
 */
@Composable
internal fun ConfirmPeerKeyDialogFor(
    tc: TeamsCoordinator,
    accountId: String,
    busy: Boolean,
    onConfirm: (InvitePreview) -> Unit,
    onDismiss: () -> Unit,
) {
    var attempt by remember(accountId) { mutableIntStateOf(0) }
    // Read once, when the dialog opens: it is a statement about what the record held *before* this
    // ceremony, and re-reading it after the confirm lands would have the dialog claim the answer it
    // was opened to obtain.
    val trust = produceState<PeerTrust?>(null, accountId) {
        value = peerTrust(tc.peerPins(listOf(accountId))[accountId] ?: Pin.None, self = false)
    }.value
    ConfirmPeerKeyDialog(
        accountId = accountId,
        check = rememberPeerKeyCheck(tc, accountId, attempt),
        trust = trust ?: PeerTrust.UNKNOWN,
        ownFingerprint = tc.ownFingerprint(),
        busy = busy,
        onRetry = { attempt += 1 },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
