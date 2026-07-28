package app.skerry.shared.rdp

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey

/**
 * JVM/Android implementation of [RdpLicenseCrypto]. MD5 and SHA-1 come from the platform, the RSA
 * step is a plain modular exponentiation ([BigInteger]) because RDP encrypts the premaster secret
 * without a padding scheme — no JCE cipher offers that, and asking for one would silently apply
 * PKCS#1 padding the server does not expect.
 */
class JvmLicenseCrypto(private val random: SecureRandom = SecureRandom()) : RdpLicenseCrypto {

    override fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    override fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray =
        BigInteger(1, base).modPow(BigInteger(1, exponent), BigInteger(1, modulus)).toByteArray()
            // BigInteger prepends a zero byte for values whose top bit is set; the caller works in
            // fixed-width fields, so that sign byte is dropped here rather than everywhere.
            .let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    override fun rsaPublicKeyOf(certificateDer: ByteArray): RdpRsaPublicKey? = runCatching {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateDer.inputStream())
        val key = certificate.publicKey as? RSAPublicKey ?: return null
        RdpRsaPublicKey(
            modulus = key.modulus.toByteArray().dropSignByte(),
            exponent = key.publicExponent.toByteArray().dropSignByte(),
        )
    }.getOrNull()

    private fun ByteArray.dropSignByte(): ByteArray =
        if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this
}
