package app.skerry.ui.sync

import androidx.compose.runtime.Composable
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_fail_account_exists
import app.skerry.ui.generated.resources.sync_fail_account_not_found
import app.skerry.ui.generated.resources.sync_fail_code_invalid
import app.skerry.ui.generated.resources.sync_fail_code_malformed
import app.skerry.ui.generated.resources.sync_fail_connect
import app.skerry.ui.generated.resources.sync_fail_forbidden
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
import app.skerry.ui.i18n.LocalAppLocale
import org.jetbrains.compose.resources.stringResource

/**
 * Known English server error strings → Chinese, keyed by the exact server response.
 * When the user runs zh-CN, the detail appended after the colon is translated in-place.
 * Unrecognised messages pass through unchanged (e.g. network exceptions, proxy HTML).
 */
private val zhServerErrorMessages = mapOf(
    "registration is closed" to "注册已关闭",
    "registration limit reached" to "注册已达上限",
    "invalid or expired invitation code" to "邀请码无效或已过期",
    "identifier too long" to "标识符过长",
    "account id must be an email address" to "账户名必须为邮箱地址",
    "account already exists" to "账户已存在",
    "authentication failed" to "认证失败",
    "invalid refresh token" to "刷新令牌无效",
    "too many requests" to "请求过于频繁",
    "internal server error" to "服务器内部错误",
    "pairing code invalid or expired" to "配对码无效或已过期",
    "no such account" to "账户不存在",
    "no published key for account" to "账户未发布密钥",
    "no such team" to "团队不存在",
    "already a member or invited" to "已是成员或已被邀请",
    "no such member" to "成员不存在",
)

/**
 * Localized text for a [SyncStatus.Failed] reason. [SyncStatus.Failed.detail], if present, is
 * appended after a colon. When the app runs in Chinese, known server-side English error strings
 * are translated so the user sees a fully-localised message.
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
            SyncFailureReason.Forbidden -> Res.string.sync_fail_forbidden
        },
    )
    val detail = failed.detail?.takeIf { it.isNotBlank() } ?: return base
    val isZh = runCatching { LocalAppLocale.current.startsWith("zh") }.getOrDefault(false)
    val detailText = if (isZh) zhServerErrorMessages[detail] ?: detail else detail
    return "$base: $detailText"
}
