package app.skerry.shared.vault

/**
 * Алгоритм генерируемой пары ключей. [label] — отображаемая метка типа (бейдж в менеджере vault).
 * Набор намеренно узкий: современный по умолчанию
 * ([ED25519]) и совместимый «legacy» RSA ([RSA_4096]) — других вариантов в UI нет.
 */
enum class SshKeyType(val label: String) {
    ED25519("ED25519"),
    RSA_4096("RSA-4096"),
}

/**
 * Открытые (не секретные) метаданные пары ключей для отображения: строка публичного ключа в формате
 * `authorized_keys` ([publicKeyOpenSsh]), отпечаток OpenSSH ([fingerprintSha256], `SHA256:`+base64 без
 * паддинга) и метка типа ([keyTypeLabel], как [SshKeyType.label]). Всё это вычисляется из публичной
 * части — секрет не нужен и здесь не лежит.
 */
data class SshPublicKeyInfo(
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val keyTypeLabel: String,
)

/**
 * Сгенерированная пара: приватный ключ в PEM ([privateKeyPem], формат `OPENSSH PRIVATE KEY`, без
 * passphrase — at-rest шифрует сам vault) и публичные метаданные ([info]). [privateKeyPem] — секрет,
 * кладётся в [CredentialSecret.PrivateKey] внутрь зашифрованного payload записи vault.
 */
data class GeneratedSshKey(
    val privateKeyPem: String,
    val info: SshPublicKeyInfo,
) {
    // Приватный ключ не должен утечь в логи/исключения (как [CredentialSecret.PrivateKey]).
    override fun toString(): String = "GeneratedSshKey(redacted, fingerprint=${info.fingerprintSha256})"
}

/**
 * Генератор/инспектор SSH-ключей. Платформенная реализация подставляется снаружи (desktop —
 * BouncyCastle поверх sshj-формата), UI обращается только к этому контракту. Чистый интерфейс без
 * корутин: генерация синхронна и редка (по действию пользователя).
 */
interface SshKeyGenerator {
    /**
     * Сгенерировать новую пару [type]. [comment] добавляется в хвост строки публичного ключа
     * (как `user@host` у `ssh-keygen`); пустой — без комментария.
     */
    fun generate(type: SshKeyType, comment: String = ""): GeneratedSshKey

    /**
     * Извлечь публичные метаданные из приватного ключа [privateKeyPem] (для показа отпечатка/типа
     * уже сохранённых identity). [passphrase] расшифровывает ключ, если он зашифрован. Возвращает
     * `null`, если ключ не разобрать (битый PEM / неверная passphrase / неподдерживаемый тип) —
     * битый ключ не должен валить список.
     */
    fun inspect(privateKeyPem: String, passphrase: String? = null): SshPublicKeyInfo?
}
