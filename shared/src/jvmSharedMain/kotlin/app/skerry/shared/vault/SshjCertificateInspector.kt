package app.skerry.shared.vault

import com.hierynomus.sshj.userauth.certificate.Certificate
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date

/**
 * JVM-инспектор SSH-сертификатов на sshj (общий desktop+Android, как [BouncyCastleSshKeyGenerator]).
 * Строка `*-cert.pub` разбирается ssh-wire-декодером sshj: для cert-типа [Buffer.readPublicKey]
 * возвращает [Certificate], откуда и берутся открытые метаданные. Обычный (не-cert) публичный ключ
 * декодируется в простой `PublicKey` — такой ввод отвергается (`null`), как и любая нечитаемая строка.
 */
class SshjCertificateInspector : SshCertificateInspector {

    override fun inspect(certificate: String): SshCertificateInfo? = runCatching {
        // Формат authorized_keys: "<type> <base64> [comment]" — берём второе поле как тело сертификата.
        val blob = certificate.trim().split(Regex("\\s+")).getOrNull(1)?.let { Base64.getDecoder().decode(it) }
            ?: return null
        val cert = Buffer.PlainBuffer(blob).readPublicKey() as? Certificate<*> ?: return null
        SshCertificateInfo(
            keyTypeLabel = displayLabel(cert.key),
            keyId = cert.id,
            principals = cert.validPrincipals.toList(),
            serial = cert.serial.toString(),
            validFrom = formatDate(cert.validAfter),
            validUntil = if (isForever(cert.validBefore)) SshCertificateInfo.FOREVER else formatDate(cert.validBefore),
            expired = !isForever(cert.validBefore) && cert.validBefore.before(Date()),
            // cert.signatureKey — ssh-wire-кодировка публичного ключа CA (та же, по которой OpenSSH
            // строит отпечаток), поэтому SHA256 от неё совпадает с `ssh-keygen -l` по ключу CA.
            caFingerprintSha256 = fingerprint(cert.signatureKey),
        )
    }.getOrNull()

    /** Метка несущего ключа: RSA — с реальной разрядностью, прочее — по wire-имени (как у генератора). */
    private fun displayLabel(key: PublicKey): String = when {
        key is RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
        KeyType.fromKey(key) == KeyType.ED25519 -> "ED25519"
        else -> KeyType.fromKey(key).toString().removePrefix("ssh-").uppercase()
    }

    private fun formatDate(date: Date): String = DATE_FORMAT.format(date.toInstant())

    // OpenSSH «бессрочный» сертификат: validBefore == uint64-максимум. После *1000 при сборке Date
    // это переполняет signed long в большое отрицательное (time <= 0); на всякий случай ловим и
    // запредельный год. Легитимный cert «до 1970» нереалистичен, ложное срабатывание ничтожно.
    private fun isForever(validBefore: Date): Boolean = validBefore.time <= 0L || yearOf(validBefore) > 9999

    private fun yearOf(date: Date): Int = date.toInstant().atZone(ZoneOffset.UTC).year

    private fun fingerprint(publicKeyBlob: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        // DateTimeFormatter иммутабелен и потокобезопасен (в отличие от SimpleDateFormat) — инспектор
        // может вызываться из разных мест (список vault + валидация в диалоге импорта).
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}
