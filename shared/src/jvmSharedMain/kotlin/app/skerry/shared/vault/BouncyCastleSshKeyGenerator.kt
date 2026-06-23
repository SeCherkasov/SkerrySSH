package app.skerry.shared.vault

import app.skerry.shared.ssh.ensureCryptoProvider
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * JVM-генератор SSH-ключей на BouncyCastle (lightweight crypto API), общий для desktop и Android.
 * Пара кодируется в тот же формат, что читает sshj при коннекте ([SshjTransport]), поэтому
 * сгенерированный ключ сразу пригоден для аутентификации:
 *  - ED25519 → приватный ключ в `openssh-key-v1` (PEM `OPENSSH PRIVATE KEY`);
 *  - RSA-4096 → PKCS#1 (PEM `RSA PRIVATE KEY`) — обе формы [SSHClient.loadKeys] разбирает.
 *
 * Публичная часть (строка `authorized_keys` + SHA256-отпечаток) считается из ssh-wire-кодировки
 * публичного ключа — той же, по которой OpenSSH строит отпечаток. Ключи генерируются без passphrase:
 * шифрование at-rest обеспечивает сам vault.
 */
class BouncyCastleSshKeyGenerator(
    private val random: SecureRandom = SecureRandom(),
) : SshKeyGenerator {

    override fun generate(type: SshKeyType, comment: String): GeneratedSshKey {
        // На Android системный BouncyCastle урезан — для совместимости разбора/использования ключа
        // регистрируем полный провайдер (idempotent, no-op на desktop), как и SSH-транспорт.
        ensureCryptoProvider()
        val pair = when (type) {
            SshKeyType.ED25519 -> Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(random))
            }.generateKeyPair()
            SshKeyType.RSA_4096 -> RSAKeyPairGenerator().apply {
                init(RSAKeyGenerationParameters(rsaPublicExponent, random, RSA_KEY_SIZE, RSA_CERTAINTY))
            }.generateKeyPair()
        }
        val privateKeyBytes = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        val sshKeyType = sshTypeString(type)
        val pem = pem(pemHeader(type), privateKeyBytes)
        // Затираем plaintext-байты ключа из heap, как только PEM построен (дисциплина FileVault).
        privateKeyBytes.fill(0)
        return GeneratedSshKey(
            privateKeyPem = pem,
            info = SshPublicKeyInfo(
                publicKeyOpenSsh = authorizedKeysLine(sshKeyType, publicBlob, comment),
                fingerprintSha256 = fingerprint(publicBlob),
                keyTypeLabel = type.label,
            ),
        )
    }

    override fun inspect(privateKeyPem: String, passphrase: String?): SshPublicKeyInfo? = runCatching {
        // sshj loadKeys идёт через JCE-провайдер «BC»; на Android он урезан и парсинг PEM падает
        // («Key could not be read» в Vault). Регистрируем полный провайдер до разбора (idempotent).
        ensureCryptoProvider()
        val pwdf = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        // SSHClient — Closeable; для loadKeys соединение не открывается, но ресурсы освобождаем через use.
        val publicKey = SSHClient().use { it.loadKeys(privateKeyPem, null, pwdf).public }
        val publicBlob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        SshPublicKeyInfo(
            // Комментарий из PEM не восстанавливается — строка без хвоста.
            publicKeyOpenSsh = authorizedKeysLine(KeyType.fromKey(publicKey).toString(), publicBlob, comment = ""),
            fingerprintSha256 = fingerprint(publicBlob),
            keyTypeLabel = displayLabel(publicKey),
        )
    }.getOrNull()

    private fun pemHeader(type: SshKeyType): String = when (type) {
        SshKeyType.ED25519 -> "OPENSSH PRIVATE KEY"
        SshKeyType.RSA_4096 -> "RSA PRIVATE KEY"
    }

    private fun sshTypeString(type: SshKeyType): String = when (type) {
        SshKeyType.ED25519 -> "ssh-ed25519"
        SshKeyType.RSA_4096 -> "ssh-rsa"
    }

    /** Метка типа для уже сохранённого ключа: RSA — с реальной разрядностью, прочее — по wire-имени. */
    private fun displayLabel(key: PublicKey): String = when {
        key is RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
        KeyType.fromKey(key) == KeyType.ED25519 -> "ED25519"
        else -> KeyType.fromKey(key).toString().removePrefix("ssh-").uppercase()
    }

    private fun authorizedKeysLine(keyType: String, blob: ByteArray, comment: String): String {
        val body = "$keyType ${Base64.getEncoder().encodeToString(blob)}"
        // Перенос строки в comment разорвал бы строку authorized_keys на несколько записей — гасим.
        val safeComment = comment.replace(Regex("[\\r\\n]"), " ").trim()
        return if (safeComment.isEmpty()) body else "$body $safeComment"
    }

    private fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun pem(header: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $header-----\n$body\n-----END $header-----\n"
    }

    private companion object {
        val rsaPublicExponent: BigInteger = BigInteger.valueOf(65537L)
        const val RSA_KEY_SIZE = 4096
        // Итерации Miller–Rabin: 100 — индустриальный минимум для RSA-4096 (как дефолт BouncyCastle).
        const val RSA_CERTAINTY = 100
        const val PEM_LINE = 64
    }
}
