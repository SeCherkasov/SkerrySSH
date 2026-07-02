package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shtail_known_changed
import app.skerry.ui.generated.resources.shtail_known_key_changed_title
import app.skerry.ui.generated.resources.shtail_known_mismatch_body
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.shortFingerprint
import org.jetbrains.compose.resources.stringResource

/**
 * Чистая логика мобильного экрана Known hosts поверх живого [app.skerry.ui.known.KnownHostsController]:
 * без таблицы и боковой панели сравнения отпечатков — строки-карточки и баннер смены ключа с
 * Accept/Reject прямо в баннере.
 */

/** Тип ключа без префикса `ssh-` (ed25519, rsa, …). */
internal fun mobileKnownKeyType(keyType: String): String = keyType.removePrefix("ssh-")

/**
 * Подпись строки known-host: `<тип> · <короткий отпечаток>` для доверенного ключа,
 * `<тип> · changed` для строки с незакрытой сменой ключа — точного отпечатка не показываем,
 * он под вопросом.
 */
@Composable
internal fun mobileKnownSubtitle(entry: KnownHostEntry): String {
    val type = mobileKnownKeyType(entry.host.keyType)
    return if (entry.status == KnownHostStatus.Changed) {
        "$type · ${stringResource(Res.string.shtail_known_changed)}"
    } else {
        "$type · ${shortFingerprint(entry.host.fingerprint)}"
    }
}

/** Иконка статуса строки: `verified` (доверен) / `error` (ключ сменился). */
internal fun mobileKnownStatusIcon(status: KnownHostStatus): String =
    if (status == KnownHostStatus.Changed) "error" else "verified"

/** Заголовок баннера смены ключа: `Key changed: <host>`. */
@Composable
internal fun mobileKnownBannerTitle(mismatch: HostKeyMismatch): String =
    stringResource(Res.string.shtail_known_key_changed_title, mismatch.host)

/** Тело баннера: какой ключ сменился + призыв проверить. */
@Composable
internal fun mobileKnownBannerBody(mismatch: HostKeyMismatch): String =
    stringResource(Res.string.shtail_known_mismatch_body, mobileKnownKeyType(mismatch.keyType).uppercase())
