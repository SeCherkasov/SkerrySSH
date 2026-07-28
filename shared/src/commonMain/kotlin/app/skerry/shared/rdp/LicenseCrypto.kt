package app.skerry.shared.rdp

/** An RSA public key as the licensing exchange needs it: both values big-endian, sign-free. */
data class RdpRsaPublicKey(val modulus: ByteArray, val exponent: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RdpRsaPublicKey && modulus.contentEquals(other.modulus) && exponent.contentEquals(other.exponent)

    override fun hashCode(): Int = 31 * modulus.contentHashCode() + exponent.contentHashCode()
}

/**
 * Primitives the licensing exchange (MS-RDPELE) needs, injected for the same reason as
 * [app.skerry.shared.rdp.nla.NtlmCrypto]: the whole state machine stays in `commonMain` and stays
 * testable, while the platform supplies the maths.
 *
 * MD5 and SHA-1 are here because the licensing key schedule is built on them (MS-RDPELE 5.1.2) and
 * a client cannot choose otherwise; they are used for nothing else.
 */
interface RdpLicenseCrypto {
    fun md5(data: ByteArray): ByteArray

    fun sha1(data: ByteArray): ByteArray

    /** Cryptographically secure random bytes (client random, premaster secret). */
    fun randomBytes(count: Int): ByteArray

    /**
     * Textbook RSA: `base^exponent mod modulus`, every value big-endian. RDP encrypts the premaster
     * secret with no padding scheme (MS-RDPBCGR 5.3.1), so this is the raw operation — the result is
     * left-padded to the modulus length by the caller.
     */
    fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray

    /**
     * The RSA public key of a DER-encoded X.509 certificate, or `null` when the certificate can't be
     * read or isn't RSA. Used for the newer of the two certificate formats a licence server sends.
     */
    fun rsaPublicKeyOf(certificateDer: ByteArray): RdpRsaPublicKey?
}

/**
 * Stand-in for paths where no licensing can happen: the reactivation sequence, which re-runs the
 * capability exchange inside a session that is already licensed. Any call means a server asked for
 * a licence where the protocol says it cannot, so each one fails loudly instead of returning
 * plausible-looking bytes.
 */
object UnavailableLicenseCrypto : RdpLicenseCrypto {
    override fun md5(data: ByteArray): ByteArray = unavailable()

    override fun sha1(data: ByteArray): ByteArray = unavailable()

    override fun randomBytes(count: Int): ByteArray = unavailable()

    override fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray = unavailable()

    override fun rsaPublicKeyOf(certificateDer: ByteArray): RdpRsaPublicKey? = unavailable()

    private fun unavailable(): Nothing =
        throw RdpAuthException("the server asked for a license outside the licensing phase")
}
