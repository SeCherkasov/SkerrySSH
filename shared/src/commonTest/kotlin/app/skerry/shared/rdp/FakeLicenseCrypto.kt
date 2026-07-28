package app.skerry.shared.rdp

/**
 * Stand-in crypto for the protocol tests: deterministic, cheap and wrong on purpose. The connection
 * sequence only has to build and send well-formed licensing messages, and the model server never
 * verifies them — real MD5/SHA-1/RSA belong to `LicenseExchangeTest`, which checks the key schedule
 * against an independent implementation.
 */
object FakeLicenseCrypto : RdpLicenseCrypto {

    override fun md5(data: ByteArray): ByteArray = digest(data, 16)

    override fun sha1(data: ByteArray): ByteArray = digest(data, 20)

    override fun randomBytes(count: Int): ByteArray = ByteArray(count) { (it + 1).toByte() }

    /** No modular arithmetic: the tests care that a field of the right width goes out, not its value. */
    override fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray =
        ByteArray(modulus.size) { index -> base.getOrElse(index) { 0 } }

    override fun rsaPublicKeyOf(certificateDer: ByteArray): RdpRsaPublicKey =
        RdpRsaPublicKey(modulus = ByteArray(64) { 1 }, exponent = byteArrayOf(1, 0, 1))

    private fun digest(data: ByteArray, size: Int): ByteArray {
        var state = 0x1234_5678
        for (byte in data) state = state * 31 + (byte.toInt() and 0xFF)
        return ByteArray(size) { index -> (state ushr (index % 4 * 8)).toByte() }
    }
}
