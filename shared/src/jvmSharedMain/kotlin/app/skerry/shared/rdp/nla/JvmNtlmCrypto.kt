package app.skerry.shared.rdp.nla

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.MD4Digest

/**
 * [NtlmCrypto] over the JVM providers, identical on desktop and Android.
 *
 * MD4 comes from Bouncy Castle rather than the platform: it was removed from the JDK's providers
 * long ago (rightly — it is broken), while NTLM's NT hash is defined as MD4 of the password and no
 * server will accept anything else. Bouncy Castle is already on the classpath for the SSH stack.
 */
class JvmNtlmCrypto(private val random: SecureRandom = SecureRandom()) : NtlmCrypto {

    override fun md4(data: ByteArray): ByteArray {
        val digest = MD4Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    override fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    override fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(key, "HmacMD5")) }.doFinal(data)

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}
