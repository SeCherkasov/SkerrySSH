package app.skerry.ui.design

import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.shortFingerprint

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
internal fun mobileKnownSubtitle(entry: KnownHostEntry): String {
    val type = mobileKnownKeyType(entry.host.keyType)
    return if (entry.status == KnownHostStatus.Changed) {
        "$type · changed"
    } else {
        "$type · ${shortFingerprint(entry.host.fingerprint)}"
    }
}

/** Иконка статуса строки: `verified` (доверен) / `error` (ключ сменился). */
internal fun mobileKnownStatusIcon(status: KnownHostStatus): String =
    if (status == KnownHostStatus.Changed) "error" else "verified"

/** Заголовок баннера смены ключа: `Key changed: <host>`. */
internal fun mobileKnownBannerTitle(mismatch: HostKeyMismatch): String = "Key changed: ${mismatch.host}"

/** Тело баннера: какой ключ сменился + призыв проверить. */
internal fun mobileKnownBannerBody(mismatch: HostKeyMismatch): String =
    "The ${mobileKnownKeyType(mismatch.keyType).uppercase()} fingerprint differs from the one recorded. Verify before reconnecting."
