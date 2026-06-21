package app.skerry.ui.design

import app.skerry.shared.ssh.HostKeyMismatch
import app.skerry.ui.known.KnownHostEntry
import app.skerry.ui.known.KnownHostStatus
import app.skerry.ui.known.shortFingerprint

/**
 * Чистая логика мобильного экрана Known hosts (`docs/new/Skerry Mobile.html`, секция KNOWN HOSTS,
 * слайс 6) поверх живого [app.skerry.ui.known.KnownHostsController]. Телефонный макет компактнее
 * desktop-`KnownHostsView`: без таблицы и боковой панели сравнения отпечатков — строки-карточки и
 * баннер смены ключа с Accept/Reject прямо в баннере.
 */

/** Тип ключа в виде из макета: без префикса `ssh-` (ed25519, rsa, …). */
internal fun mobileKnownKeyType(keyType: String): String = keyType.removePrefix("ssh-")

/**
 * Подпись строки known-host макета: `<тип> · <короткий отпечаток>` для доверенного ключа,
 * `<тип> · changed` для строки с незакрытой сменой ключа — точного отпечатка там не показываем,
 * он под вопросом (ровно как в макете).
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

/**
 * Тело баннера: какой ключ сменился + призыв проверить. Без выдуманной даты статичного макета
 * («differs from Mar 4») — честная формулировка, как в живом desktop-баннере.
 */
internal fun mobileKnownBannerBody(mismatch: HostKeyMismatch): String =
    "The ${mobileKnownKeyType(mismatch.keyType).uppercase()} fingerprint differs from the one recorded. Verify before reconnecting."
