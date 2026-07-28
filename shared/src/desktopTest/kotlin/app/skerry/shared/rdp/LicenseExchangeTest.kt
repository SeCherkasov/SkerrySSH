package app.skerry.shared.rdp

import app.skerry.shared.rdp.nla.Rc4
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The licensing exchange (MS-RDPELE) against a model licence server that does what a real one does:
 * decrypt the premaster secret with its private key, derive the same keys independently, encrypt a
 * challenge and verify the client's MAC over the answer.
 *
 * That independence is the point. A round-trip through our own code would pass with the key schedule
 * mirrored wrongly on both sides; the server here re-implements MS-RDPELE 5.1.2/5.1.5 from the
 * specification text, so agreeing means the client follows the specification and not itself.
 *
 * Lives in desktopTest because it needs real MD5/SHA-1/RSA — the whole reason the crypto is injected.
 */
class LicenseExchangeTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey
    private val crypto = JvmLicenseCrypto()
    private val logon = RdpLogonInfo(domain = "CORP", username = "elton")

    @Test
    fun `answers a license request with an encrypted premaster secret the server can read`() {
        val exchange = LicenseExchange(crypto, logon)
        val serverRandom = ByteArray(32) { it.toByte() }

        val request = exchange.newLicenseRequest(RdpReader(licenseRequest(serverRandom)))

        val body = licenseBody(request, LicenseExchange.NEW_LICENSE_REQUEST)
        assertEquals(0x00000001, body.u32le(), "RSA key exchange")
        body.u32le() // platform id
        val clientRandom = body.bytes(32)
        val premaster = decryptPremaster(readBlob(body))
        assertEquals(48, premaster.size)
        // The user and machine names travel as ANSI strings, which is what a licence server indexes on.
        assertEquals("elton", readBlob(body).decodeToString().trimEnd(Char(0)))
        assertTrue(readBlob(body).isNotEmpty())

        // …and the server can now derive the same keys, which the challenge below proves.
        assertTrue(clientRandom.any { it != 0.toByte() }, "client random is random, not zeros")
        assertTrue(premaster.any { it != 0.toByte() }, "premaster secret is random, not zeros")
    }

    @Test
    fun `answers a platform challenge with the decrypted challenge and a valid MAC`() {
        val exchange = LicenseExchange(crypto, logon)
        val serverRandom = ByteArray(32) { (it * 7).toByte() }
        val request = licenseBody(exchange.newLicenseRequest(RdpReader(licenseRequest(serverRandom))), LicenseExchange.NEW_LICENSE_REQUEST)
        request.u32le()
        request.u32le()
        val clientRandom = request.bytes(32)
        val premaster = decryptPremaster(readBlob(request))
        val keys = ServerKeys(premaster, clientRandom, serverRandom)

        val challenge = "TEST-CHALLENGE-01".encodeToByteArray()
        val response = exchange.platformChallengeResponse(
            RdpReader(platformChallenge(Rc4(keys.licensingEncryptionKey).process(challenge))),
        )

        val body = licenseBody(response, LicenseExchange.PLATFORM_CHALLENGE_RESPONSE)
        val responseData = Rc4(keys.licensingEncryptionKey).process(readBlob(body))
        val hardwareId = Rc4(keys.licensingEncryptionKey).process(readBlob(body))
        val mac = body.bytes(16)

        val data = RdpReader(responseData)
        assertEquals(0x0100, data.u16le(), "wVersion")
        assertEquals(0x0100, data.u16le(), "wClientType = Win32")
        assertEquals(0x0003, data.u16le(), "wLicenseDetailLevel = detail")
        assertEquals(challenge.size, data.u16le())
        assertContentEquals(challenge, data.bytes(challenge.size), "the challenge is echoed back decrypted")
        assertEquals(20, hardwareId.size)
        assertContentEquals(keys.mac(responseData + hardwareId), mac, "MAC over response data + hardware id")
    }

    @Test
    fun `the hardware id is stable across connections from the same client`() {
        // A licence server indexes issued licences by this; a random one would ask for a new licence
        // on every connect and drain the pool.
        val first = hardwareIdOf(LicenseExchange(crypto, logon))
        val second = hardwareIdOf(LicenseExchange(crypto, logon))
        assertContentEquals(first, second)
    }

    private fun hardwareIdOf(exchange: LicenseExchange): ByteArray {
        val serverRandom = ByteArray(32) { 3 }
        val request = licenseBody(exchange.newLicenseRequest(RdpReader(licenseRequest(serverRandom))), LicenseExchange.NEW_LICENSE_REQUEST)
        request.u32le()
        request.u32le()
        val clientRandom = request.bytes(32)
        val keys = ServerKeys(decryptPremaster(readBlob(request)), clientRandom, serverRandom)
        val response = licenseBody(
            exchange.platformChallengeResponse(RdpReader(platformChallenge(Rc4(keys.licensingEncryptionKey).process(ByteArray(8))))),
            LicenseExchange.PLATFORM_CHALLENGE_RESPONSE,
        )
        readBlob(response)
        return Rc4(keys.licensingEncryptionKey).process(readBlob(response))
    }

    /** The server side of MS-RDPELE 5.1.2/5.1.5, written from the specification rather than reused. */
    private class ServerKeys(premaster: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray) {
        private val macSaltKey: ByteArray
        val licensingEncryptionKey: ByteArray

        init {
            fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)
            fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)
            fun salted(salt: ByteArray, tag: String, a: ByteArray, b: ByteArray) =
                md5(salt + sha1(tag.encodeToByteArray() + salt + a + b))

            val master = salted(premaster, "A", clientRandom, serverRandom) +
                salted(premaster, "BB", clientRandom, serverRandom) +
                salted(premaster, "CCC", clientRandom, serverRandom)
            val sessionKeyBlob = salted(master, "A", serverRandom, clientRandom) +
                salted(master, "BB", serverRandom, clientRandom) +
                salted(master, "CCC", serverRandom, clientRandom)
            macSaltKey = sessionKeyBlob.copyOfRange(0, 16)
            licensingEncryptionKey = md5(sessionKeyBlob.copyOfRange(16, 32) + clientRandom + serverRandom)
        }

        fun mac(data: ByteArray): ByteArray {
            val md5 = MessageDigest.getInstance("MD5")
            val sha1 = MessageDigest.getInstance("SHA-1")
            val length = byteArrayOf(
                data.size.toByte(),
                (data.size ushr 8).toByte(),
                (data.size ushr 16).toByte(),
                (data.size ushr 24).toByte(),
            )
            val inner = sha1.digest(macSaltKey + ByteArray(40) { 0x36 } + length + data)
            return md5.digest(macSaltKey + ByteArray(48) { 0x5C } + inner)
        }
    }

    /** A Server Licence Request carrying a proprietary certificate around the test's RSA key. */
    private fun licenseRequest(serverRandom: ByteArray): ByteArray {
        val modulusLe = publicKey.modulus.toByteArray().dropSign().reversedArray()
        val exponentLe = publicKey.publicExponent.toByteArray().dropSign().reversedArray().copyOf(4)
        val keyBlob = RdpWriter(modulusLe.size + 32).apply {
            u32le(0x31415352) // "RSA1"
            u32le(modulusLe.size + 8) // keylen counts the trailing padding
            u32le(modulusLe.size * 8)
            u32le(modulusLe.size)
            bytes(exponentLe)
            bytes(modulusLe)
            zeros(8)
        }.toByteArray()
        val certificate = RdpWriter(keyBlob.size + 32).apply {
            u32le(1) // CERT_CHAIN_VERSION_1
            u32le(0) // dwSigAlgId
            u32le(0) // dwKeyAlgId
            u16le(0x0006) // wPublicKeyBlobType
            u16le(keyBlob.size)
            bytes(keyBlob)
            u16le(0x0008) // wSignatureBlobType
            u16le(0)
        }.toByteArray()

        return RdpWriter(certificate.size + 128).apply {
            bytes(serverRandom)
            u32le(0x00040000) // ProductInfo: dwVersion
            u32le(4)
            bytes(byteArrayOf(1, 2, 3, 4)) // company name
            u32le(2)
            bytes(byteArrayOf(5, 6)) // product id
            u16le(0x000D).u16le(4).u32le(1) // KeyExchangeList: RSA
            u16le(0x0003).u16le(certificate.size).bytes(certificate)
            u32le(1).u16le(0x000E).u16le(2).bytes(byteArrayOf(7, 8)) // ScopeList
        }.toByteArray()
    }

    /** A Server Platform Challenge around [encryptedChallenge] (its MAC is not checked by the client). */
    private fun platformChallenge(encryptedChallenge: ByteArray): ByteArray =
        RdpWriter(encryptedChallenge.size + 32).apply {
            u32le(0) // ConnectFlags
            u16le(0x0009).u16le(encryptedChallenge.size).bytes(encryptedChallenge)
            zeros(16) // MACData
        }.toByteArray()

    /** Strips the security header and preamble, asserting the message type. */
    private fun licenseBody(message: ByteArray, expectedType: Int): RdpReader {
        val reader = RdpReader(message)
        assertEquals(RdpSecurityHeader.SEC_LICENSE_PKT, reader.u16le())
        reader.u16le() // flagsHi
        assertEquals(expectedType, reader.u8())
        reader.u8() // preamble version
        reader.u16le() // wMsgSize
        return reader
    }

    private fun readBlob(reader: RdpReader): ByteArray {
        reader.u16le()
        return reader.bytes(reader.u16le())
    }

    /** Undo the client's little-endian, zero-padded RSA field and decrypt it with the private key. */
    private fun decryptPremaster(blob: ByteArray): ByteArray {
        val cipher = blob.copyOfRange(0, blob.size - 8).reversedArray()
        val plain = BigInteger(1, cipher).modPow(privateKey.privateExponent, privateKey.modulus).toByteArray()
        return plain.dropSign().reversedArray()
    }

    private fun ByteArray.dropSign(): ByteArray =
        if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this
}
