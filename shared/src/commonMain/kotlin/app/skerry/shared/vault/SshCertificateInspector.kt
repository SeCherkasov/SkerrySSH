package app.skerry.shared.vault

/**
 * Открытые (не секретные) метаданные SSH-сертификата для отображения в менеджере vault. Всё
 * вычисляется из публичной части сертификата (`*-cert.pub`) — приватный ключ здесь не нужен и не
 * лежит. Даты предформатированы строками (`yyyy-MM-dd`, UTC) и флагом [expired], потому что в
 * `commonMain` нет datetime-библиотеки; форматирование делает платформенный инспектор.
 *
 * [keyTypeLabel] — метка несущего ключа (как у [SshPublicKeyInfo.keyTypeLabel], напр. `ED25519`).
 * [keyId] — идентификатор сертификата (`-I` у `ssh-keygen`). [principals] — для каких имён выдан
 * (пусто == «для любого»). [serial] — серийный номер как строка (формат uint64 шире `Long`).
 * [validFrom]/[validUntil] — окно действия (`validUntil` == [FOREVER] для бессрочного).
 * [caFingerprintSha256] — отпечаток ключа удостоверяющего центра (`SHA256:`+base64 без паддинга).
 */
data class SshCertificateInfo(
    val keyTypeLabel: String,
    val keyId: String,
    val principals: List<String>,
    val serial: String,
    val validFrom: String,
    val validUntil: String,
    val expired: Boolean,
    val caFingerprintSha256: String,
) {
    companion object {
        /** Значение [validUntil] для бессрочного сертификата (OpenSSH `valid before` == максимум). */
        const val FOREVER: String = "forever"
    }
}

/**
 * Инспектор SSH-сертификатов. Платформенная реализация (desktop/Android — sshj поверх ssh-wire
 * формата) подставляется снаружи, UI обращается только к этому контракту. Чистый интерфейс без
 * корутин: разбор синхронен и дёшев (по действию пользователя).
 */
interface SshCertificateInspector {
    /**
     * Извлечь публичные метаданные из строки сертификата [certificate] (`*-cert.pub`, формат
     * `ssh-…-cert-v01@openssh.com <base64> [comment]`). Возвращает `null`, если сертификат не
     * разобрать (битая строка / не сертификат / неподдерживаемый тип) — битый секрет не должен
     * валить список vault.
     */
    fun inspect(certificate: String): SshCertificateInfo?
}
