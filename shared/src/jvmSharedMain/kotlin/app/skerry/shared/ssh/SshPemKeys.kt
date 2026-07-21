package app.skerry.shared.ssh

import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory

/**
 * One way in for every stored private key, whichever shape the user pasted.
 *
 * sshj reads OpenSSH (`openssh-key-v1`) and the OpenSSL PKCS#1/SEC1 PEMs, which covers keys made by
 * `ssh-keygen`. It cannot read PKCS#8 (`BEGIN PRIVATE KEY`) — its parser fails with an index error
 * deep inside — and that is exactly what `openssl genpkey`, cloud consoles and Java tooling produce,
 * so such a key would look "damaged" to the user for no reason. PKCS#8 (plain and encrypted) is
 * therefore parsed here instead, and the public half is derived from the private one, since PKCS#8
 * carries no public key of its own.
 */
fun loadSshKeyPair(pem: String, passphrase: String?): KeyPair {
    // Android's system BouncyCastle is stripped down and cannot parse PEM keys (idempotent, no-op
    // on desktop) — as the transport, the generator and the agent keyring do.
    ensureCryptoProvider()
    val viaSshj = runCatching {
        val finder = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        SSHClient().use { client ->
            val keys = client.loadKeys(pem, null, finder)
            KeyPair(keys.public, keys.private)
        }
    }.getOrNull()
    if (viaSshj != null) return viaSshj
    return pkcs8KeyPair(pem, passphrase)
        ?: throw IOException("unsupported or damaged private key")
}

/**
 * [loadSshKeyPair] for the authentication path, where the failure has to reach the user as what it
 * is. A key that cannot be opened (wrong passphrase, unsupported or damaged PEM) is not a transport
 * problem, and reporting it as one sends the user looking at the network for a typo.
 */
internal fun loadAuthKey(pem: String, passphrase: String?): KeyPair =
    runCatching { loadSshKeyPair(pem, passphrase) }.getOrElse {
        throw SshAuthenticationException("The stored SSH key could not be read (wrong passphrase or unsupported format)", it)
    }

private fun pkcs8KeyPair(pem: String, passphrase: String?): KeyPair? = runCatching {
    val header = PKCS8_HEADER.find(pem) ?: return null
    val encrypted = header.groupValues[1] == "ENCRYPTED "
    val body = pem.substringAfter(header.value).substringBefore("-----END").filterNot { it.isWhitespace() }
    val der = Base64.getDecoder().decode(body)
    val pkcs8 = if (encrypted) decrypt(der, passphrase ?: return null) else der
    val parameters = PrivateKeyFactory.createKey(pkcs8)
    val factory = KeyFactory.getInstance(algorithmOf(parameters))
    KeyPair(publicKeyOf(parameters, factory), factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as PrivateKey)
}.getOrNull()

/**
 * Undo the PBES wrapper of an `ENCRYPTED PRIVATE KEY`. The algorithm (and its parameters) are named
 * in the blob itself, so whatever produced the key decides the KDF — we only supply the passphrase.
 */
private fun decrypt(der: ByteArray, passphrase: String): ByteArray {
    val info = EncryptedPrivateKeyInfo(der)
    val key = SecretKeyFactory.getInstance(info.algName).generateSecret(PBEKeySpec(passphrase.toCharArray()))
    val cipher = Cipher.getInstance(info.algName).apply { init(Cipher.DECRYPT_MODE, key, info.algParameters) }
    return cipher.doFinal(info.encryptedData)
}

/**
 * The public half, computed from the private key: RSA carries its public exponent, an EC private
 * scalar gives the point back as `d·G`, and Ed25519 derives it by hashing. Encoded through
 * SubjectPublicKeyInfo so one JCA path covers all three.
 */
private fun publicKeyOf(parameters: AsymmetricKeyParameter, factory: KeyFactory): PublicKey {
    val public = when (parameters) {
        is RSAPrivateCrtKeyParameters -> RSAKeyParameters(false, parameters.modulus, parameters.publicExponent)
        is ECPrivateKeyParameters ->
            ECPublicKeyParameters(parameters.parameters.g.multiply(parameters.d).normalize(), parameters.parameters)
        is Ed25519PrivateKeyParameters -> parameters.generatePublicKey()
        else -> throw IOException("unsupported key type")
    }
    return factory.generatePublic(X509EncodedKeySpec(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(public).encoded))
}

private fun algorithmOf(parameters: AsymmetricKeyParameter): String = when (parameters) {
    is RSAKeyParameters -> "RSA"
    is ECPrivateKeyParameters -> "EC"
    is Ed25519PrivateKeyParameters -> "Ed25519"
    else -> throw IOException("unsupported key type")
}

private val PKCS8_HEADER = Regex("-----BEGIN (ENCRYPTED )?PRIVATE KEY-----")
