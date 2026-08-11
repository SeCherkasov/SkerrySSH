package app.skerry.ui.sync

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_fail_account_exists
import app.skerry.ui.generated.resources.sync_fail_account_not_found
import app.skerry.ui.generated.resources.sync_fail_code_invalid
import app.skerry.ui.generated.resources.sync_fail_code_malformed
import app.skerry.ui.generated.resources.sync_fail_connect
import app.skerry.ui.generated.resources.sync_fail_local_vault_corrupted
import app.skerry.ui.generated.resources.sync_fail_network
import app.skerry.ui.generated.resources.sync_fail_pairing
import app.skerry.ui.generated.resources.sync_fail_pairing_expired
import app.skerry.ui.generated.resources.sync_fail_protocol
import app.skerry.ui.generated.resources.sync_fail_reconcile_required
import app.skerry.ui.generated.resources.sync_fail_registration_refused_signin
import app.skerry.ui.generated.resources.sync_fail_rejected
import app.skerry.ui.generated.resources.stail_sync_announce_disconnected
import app.skerry.ui.generated.resources.stail_sync_announce_error
import app.skerry.ui.generated.resources.stail_sync_fail_detail
import app.skerry.ui.generated.resources.stail_sync_fail_key_adopt
import app.skerry.ui.generated.resources.stail_sync_fail_rekey
import app.skerry.ui.generated.resources.sync_password_replace_title
import app.skerry.ui.generated.resources.sync_fail_revoke
import app.skerry.ui.generated.resources.sync_fail_save_settings
import app.skerry.ui.generated.resources.sync_fail_server_error
import app.skerry.ui.generated.resources.sync_fail_sync
import app.skerry.ui.generated.resources.sync_fail_too_many_requests
import app.skerry.ui.generated.resources.sync_fail_unauthorized
import app.skerry.ui.generated.resources.sync_fail_vault_locked
import app.skerry.ui.generated.resources.sync_fail_wrong_device_password
import app.skerry.ui.generated.resources.settings_sync_syncing
import app.skerry.ui.generated.resources.settings_sync_connected
import app.skerry.ui.design.untrustedLabel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Localized text for a [SyncStatus.Failed] reason. [SyncStatus.Failed.detail] is a raw technical
 * message (server/library): it never stands alone, only as a parenthetical after the localized
 * reason, so the sentence stays translatable.
 *
 * The detail is server-authored on some paths (a 403 carries the server's own `error` string), and it
 * is the only sentence the user has to judge what went wrong with a credential form — so it goes
 * through the same filter as every other server-authored label ([untrustedLabel]): bidi overrides and
 * format characters in it would otherwise reverse or blank out the part around it.
 */
@Composable
fun syncFailureText(failed: SyncStatus.Failed): String {
    val base = stringResource(syncFailureResource(failed.reason))
    val detail = failed.detail?.let { untrustedLabel(it) }?.takeIf { it.isNotBlank() } ?: return base
    return stringResource(Res.string.stail_sync_fail_detail, base, detail)
}

/** Reason → its text. Split out of the composable so the wiring itself is unit-testable. */
internal fun syncFailureResource(reason: SyncFailureReason): StringResource =
    when (reason) {
        SyncFailureReason.VaultLocked -> Res.string.sync_fail_vault_locked
        SyncFailureReason.Unauthorized -> Res.string.sync_fail_unauthorized
        SyncFailureReason.AccountNotFound -> Res.string.sync_fail_account_not_found
        SyncFailureReason.AccountExists -> Res.string.sync_fail_account_exists
        SyncFailureReason.PairingCodeExpired -> Res.string.sync_fail_pairing_expired
        SyncFailureReason.Network -> Res.string.sync_fail_network
        SyncFailureReason.Protocol -> Res.string.sync_fail_protocol
        SyncFailureReason.ConnectFailed -> Res.string.sync_fail_connect
        SyncFailureReason.PairingCodeMalformed -> Res.string.sync_fail_code_malformed
        SyncFailureReason.PairingCodeInvalid -> Res.string.sync_fail_code_invalid
        SyncFailureReason.WrongDevicePassword -> Res.string.sync_fail_wrong_device_password
        SyncFailureReason.LocalVaultCorrupted -> Res.string.sync_fail_local_vault_corrupted
        SyncFailureReason.PairingFailed -> Res.string.sync_fail_pairing
        SyncFailureReason.VaultRekeyFailed -> Res.string.stail_sync_fail_rekey
        SyncFailureReason.AccountKeyNotAdopted -> Res.string.stail_sync_fail_key_adopt
        SyncFailureReason.SaveSettingsFailed -> Res.string.sync_fail_save_settings
        SyncFailureReason.SyncFailed -> Res.string.sync_fail_sync
        SyncFailureReason.RevokeFailed -> Res.string.sync_fail_revoke
        SyncFailureReason.TooManyRequests -> Res.string.sync_fail_too_many_requests
        SyncFailureReason.ServerError -> Res.string.sync_fail_server_error
        SyncFailureReason.Rejected -> Res.string.sync_fail_rejected
        SyncFailureReason.RegistrationRefusedSignInFailed -> Res.string.sync_fail_registration_refused_signin
        SyncFailureReason.ReconcileRequired -> Res.string.sync_fail_reconcile_required
    }

/**
 * The one line a screen reader should hear when the sync state changes: the state itself, and the reason
 * when it failed. Deliberately not the traffic counters — [SyncStatus.Online] is re-published after every
 * background cycle with new numbers, and a live region announces whatever changed, so the counters would
 * turn a silent background sync into speech every few seconds (issue #244).
 *
 * Exhaustive over the sealed type on purpose: an `else` here is how a state that changes on its own ends up
 * silent, which is the bug. [SyncStatus.Configured] is not only the resting state of a configured device —
 * it is where a session lands when its refresh token dies mid-use, so it has to be heard. Only
 * [SyncStatus.Disabled] is empty: sync that was never set up announces nothing.
 */
@Composable
fun syncAnnouncement(status: SyncStatus): String = when (status) {
    SyncStatus.Busy -> stringResource(Res.string.settings_sync_syncing)
    is SyncStatus.Online -> stringResource(Res.string.settings_sync_connected)
    is SyncStatus.Failed -> stringResource(Res.string.stail_sync_announce_error, syncFailureText(status))
    is SyncStatus.Configured -> stringResource(Res.string.stail_sync_announce_disconnected)
    // The one state that is a question: the connect is parked waiting for an answer the user has to give.
    is SyncStatus.NeedsPasswordReplaceConfirm -> stringResource(Res.string.sync_password_replace_title)
    SyncStatus.Disabled -> ""
}
