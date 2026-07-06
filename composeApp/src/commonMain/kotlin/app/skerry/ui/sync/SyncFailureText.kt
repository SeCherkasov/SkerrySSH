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
import app.skerry.ui.generated.resources.sync_fail_revoke
import app.skerry.ui.generated.resources.sync_fail_save_settings
import app.skerry.ui.generated.resources.sync_fail_sync
import app.skerry.ui.generated.resources.sync_fail_unauthorized
import app.skerry.ui.generated.resources.sync_fail_vault_locked
import app.skerry.ui.generated.resources.sync_fail_wrong_device_password
import org.jetbrains.compose.resources.stringResource

/**
 * Localized text for a [SyncStatus.Failed] reason. [SyncStatus.Failed.detail], if present, is
 * appended after a colon.
 */
@Composable
fun syncFailureText(failed: SyncStatus.Failed): String {
    val base = stringResource(
        when (failed.reason) {
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
            SyncFailureReason.SaveSettingsFailed -> Res.string.sync_fail_save_settings
            SyncFailureReason.SyncFailed -> Res.string.sync_fail_sync
            SyncFailureReason.RevokeFailed -> Res.string.sync_fail_revoke
        },
    )
    return failed.detail?.takeIf { it.isNotBlank() }?.let { "$base: $it" } ?: base
}
