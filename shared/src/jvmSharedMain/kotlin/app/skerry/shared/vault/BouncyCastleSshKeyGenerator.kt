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
 * JVM SSH key generator on BouncyCastle (lightweight crypto API), shared by desktop and Android.
 * Encodes the pair in the format sshj reads on connect ([SshjTransport]):
 *  - ED25519 → private key as `openssh-key-v1` (PEM `OPENSSH PRIVATE KEY`);
 *  - RSA-4096 → PKCS#1 (PEM `RSA PRIVATE KEY`) — both parsed by [SSHClient.loadKeys].
 *
 * The public part (`authorized_keys` line + SHA256 fingerprint) is derived from the ssh-wire
 * encoding of the public key, matching OpenSSH's fingerprint. Keys are generated without a
 * passphrase; at-rest encryption is handled by the vault.
 */
class BouncyCastleSshKeyGenerator(
    private val random: SecureRandom = SecureRandom(),
) : SshKeyGenerator {

    override fun generate(type: SshKeyType, comment: String): GeneratedSshKey {
        // Android's system BouncyCastle is stripped down; register the full provider for key
        // parsing/use (idempotent, no-op on desktop), same as the SSH transport.
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
        // Wipe the plaintext key bytes from the heap once the PEM is built.
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
        // sshj loadKeys goes through the JCE "BC" provider, stripped down on Android, which breaks
        // PEM parsing. Register the full provider before parsing (idempotent).
        ensureCryptoProvider()
        val pwdf = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        // SSHClient is Closeable; loadKeys opens no connection but resources are freed via use.
        val publicKey = SSHClient().use { it.loadKeys(privateKeyPem, null, pwdf).public }
        val publicBlob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        SshPublicKeyInfo(
            // The PEM comment isn't recoverable; line has no trailing comment.
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

    /** Type label for a stored key: RSA shows actual bit length, otherwise the wire name. */
    private fun displayLabel(key: PublicKey): String = when {
        key is RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
        KeyType.fromKey(key) == KeyType.ED25519 -> "ED25519"
        else -> KeyType.fromKey(key).toString().removePrefix("ssh-").uppercase()
    }

    private fun authorizedKeysLine(keyType: String, blob: ByteArray, comment: String): String {
        val body = "$keyType ${Base64.getEncoder().encodeToString(blob)}"
        // Strip newlines from the comment; they would split the authorized_keys line into multiple entries.
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
        // Miller-Rabin iterations: 100, the industry-standard minimum for RSA-4096 (BouncyCastle default).
        const val RSA_CERTAINTY = 100
        const val PEM_LINE = 64
    }
}
