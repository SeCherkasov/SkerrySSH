package app.skerry.shared.rdp

import app.skerry.shared.rdp.nla.Rc4

/**
 * The licensing exchange of MS-RDPELE, the part that actually issues a licence.
 *
 * A server with nothing to license answers the first request with "valid client" and the session
 * continues — that path needs none of this. A terminal server that hands out per-device CALs instead
 * answers with a platform challenge, and a client that cannot answer it never reaches the desktop.
 * The exchange is: server licence request (server random + its certificate) → client new licence
 * request (client random + RSA-encrypted premaster secret) → server platform challenge (RC4 over a
 * key derived from the two randoms and the premaster) → client challenge response (the decrypted
 * challenge plus a hardware id, both re-encrypted, with a MAC) → new licence.
 *
 * The issued licence is not stored: a per-device CAL would have to survive restarts to be reused,
 * and the server issues a fresh one on the next connection anyway. What matters is completing the
 * exchange so the session proceeds.
 *
 * Pure protocol over an injected [RdpLicenseCrypto], like the NLA state machine next door.
 */
class LicenseExchange(
    private val crypto: RdpLicenseCrypto,
    private val logon: RdpLogonInfo,
    private val machineName: String = "SKERRY",
) {
    private var clientRandom: ByteArray = ByteArray(0)
    private var premasterSecret: ByteArray = ByteArray(0)
    private var macSaltKey: ByteArray = ByteArray(0)
    private var licensingEncryptionKey: ByteArray = ByteArray(0)

    /**
     * Answer a Server Licence Request with a Client New Licence Request, deriving the session keys
     * on the way.
     *
     * A server whose traffic is already protected by TLS may leave its licensing certificate out
     * (MS-RDPELE 2.2.2.1), and under enhanced security there is no other certificate to fall back
     * on. Then the exchange runs on a zero client random and a zero premaster secret — the server
     * does the same, so both sides still derive the same licensing key, and the secrecy that key
     * would have added is already provided by the transport.
     */
    fun newLicenseRequest(reader: RdpReader): ByteArray {
        val request = parseLicenseRequest(reader)
        val publicKey = request.publicKey

        clientRandom = if (publicKey != null) crypto.randomBytes(RANDOM_SIZE) else ByteArray(RANDOM_SIZE)
        premasterSecret = if (publicKey != null) crypto.randomBytes(PREMASTER_SIZE) else ByteArray(PREMASTER_SIZE)
        deriveKeys(request.serverRandom)

        val body = RdpWriter(512)
        body.u32le(KEY_EXCHANGE_ALG_RSA)
        body.u32le(PLATFORM_ID)
        body.bytes(clientRandom)
        // With no key to encrypt under, the field keeps the shape a server expects and carries the
        // zero secret both sides agreed on by omission.
        blob(body, BB_RANDOM_BLOB, publicKey?.let(::encryptPremaster) ?: ByteArray(NULL_PREMASTER_FIELD))
        blob(body, BB_CLIENT_USER_NAME_BLOB, nullTerminatedAscii(logon.username))
        blob(body, BB_CLIENT_MACHINE_NAME_BLOB, nullTerminatedAscii(machineName))
        return message(NEW_LICENSE_REQUEST, body.toByteArray())
    }

    /**
     * Answer a Server Platform Challenge. The challenge itself is opaque — it is decrypted only to
     * be handed back inside the response, which is what proves the client derived the same keys.
     *
     * @throws RdpProtocolException the challenge arrived before the licence request that keys it,
     *   or its MAC does not cover the challenge that arrived with it
     */
    fun platformChallengeResponse(reader: RdpReader): ByteArray {
        if (licensingEncryptionKey.isEmpty()) {
            throw RdpProtocolException("a platform challenge arrived before the license request")
        }
        reader.u32le() // ConnectFlags, reserved
        val encryptedChallenge = readBlob(reader)
        val serverMac = reader.bytes(MAC_SIZE)
        val challenge = Rc4(licensingEncryptionKey).process(encryptedChallenge)
        // MS-RDPELE 2.2.2.4: the MAC covers the decrypted challenge. Nothing an attacker who can
        // reach these bytes gains by flipping them — they are already inside the TLS tunnel — but a
        // mismatch is the one signal that says the tunnel, or our own key schedule, is not what we
        // think it is, and answering a challenge we cannot authenticate would hide it.
        if (!constantTimeEquals(mac(challenge), serverMac)) {
            throw RdpProtocolException("the platform challenge's MAC does not match the challenge")
        }

        val responseData = RdpWriter(challenge.size + 8).apply {
            u16le(PLATFORM_CHALLENGE_VERSION)
            u16le(WIN32_PLATFORMCHALLENGE_TYPE)
            u16le(LICENSE_DETAIL_DETAIL)
            u16le(challenge.size)
            bytes(challenge)
        }.toByteArray()
        val hardwareId = hardwareId()

        val body = RdpWriter(256)
        blob(body, BB_ENCRYPTED_DATA_BLOB, Rc4(licensingEncryptionKey).process(responseData))
        blob(body, BB_ENCRYPTED_DATA_BLOB, Rc4(licensingEncryptionKey).process(hardwareId))
        body.bytes(mac(responseData + hardwareId))
        return message(PLATFORM_CHALLENGE_RESPONSE, body.toByteArray())
    }

    /** What a Server Licence Request carried, of the fields this client acts on. */
    private class LicenseRequest(val serverRandom: ByteArray, val publicKey: RdpRsaPublicKey?)

    private fun parseLicenseRequest(reader: RdpReader): LicenseRequest {
        val serverRandom = reader.bytes(RANDOM_SIZE)
        // ProductInfo: version, then two length-prefixed strings we have no use for.
        reader.u32le()
        reader.skip(reader.u32le().boundedLength())
        reader.skip(reader.u32le().boundedLength())
        readBlob(reader) // KeyExchangeList — RSA is the only algorithm a server offers
        val certificate = readBlob(reader)
        // ScopeList follows and is only needed to look up a stored licence, which this client has none of.
        return LicenseRequest(serverRandom, publicKeyOf(certificate))
    }

    /**
     * The public key of the terminal server certificate (MS-RDPBCGR 2.2.1.4.3.1). Two formats exist:
     * the legacy proprietary one, which carries a bare RSA key little-endian, and a chain of X.509
     * certificates, whose last entry is the server's. `null` when the server sent no certificate —
     * under TLS it may leave it out, and then there is nothing to encrypt the premaster secret with.
     */
    private fun publicKeyOf(certificate: ByteArray): RdpRsaPublicKey? {
        if (certificate.isEmpty()) return null
        val reader = RdpReader(certificate)
        return when (val version = reader.u32le() and CERT_CHAIN_VERSION_MASK) {
            CERT_CHAIN_VERSION_1 -> proprietaryPublicKey(certificate, reader)
            CERT_CHAIN_VERSION_2 -> x509ChainPublicKey(reader)
            else -> throw RdpProtocolException("unknown server certificate version $version")
        }
    }

    private fun proprietaryPublicKey(certificate: ByteArray, reader: RdpReader): RdpRsaPublicKey? {
        reader.u32le() // dwSigAlgId
        reader.u32le() // dwKeyAlgId
        reader.u16le() // wPublicKeyBlobType
        val keyBlob = reader.slice(reader.u16le().boundedLength())
        // Everything read so far is what the signature covers, and it is checked before the key it
        // carries is used for anything.
        verifyProprietarySignature(certificate.copyOfRange(0, reader.position), reader)
        if (keyBlob.u32le() != RSA1_MAGIC) return null
        val keyLength = keyBlob.u32le().boundedLength()
        keyBlob.u32le() // bitlen
        keyBlob.u32le() // datalen
        val exponent = keyBlob.bytes(4)
        // The modulus field ends with eight bytes of padding, and both values are little-endian.
        val modulus = keyBlob.bytes(keyLength)
        // keylen counts the eight trailing padding bytes, and it is only ever bounded from above, so
        // a server may declare a length that leaves no modulus at all. Trimming the padding off that
        // reads past the array, and a modulus shorter than its own padding is not a key anything can
        // be sealed with — both are protocol errors rather than arithmetic to paper over.
        if (modulus.size <= RSA_PADDING) {
            throw RdpProtocolException("a licensing RSA modulus of ${modulus.size} bytes")
        }
        return RdpRsaPublicKey(
            modulus = modulus.copyOfRange(0, modulus.size - RSA_PADDING).reversedArray(),
            exponent = exponent.reversedArray(),
        )
    }

    /**
     * The signature of a proprietary certificate (MS-RDPBCGR 5.3.3.1.1): MD5 over [signed], wrapped
     * in fixed padding and RSA-signed with the Terminal Services key. Microsoft published that key's
     * private half, so this says nothing about who the server is — it says the key blob is the one
     * whoever wrote the certificate meant to send, which is the bit worth keeping.
     *
     * @throws RdpProtocolException the signature is absent, the wrong size, or over other bytes
     */
    private fun verifyProprietarySignature(signed: ByteArray, reader: RdpReader) {
        reader.u16le() // wSignatureBlobType
        val blob = reader.bytes(reader.u16le().boundedLength())
        if (blob.size != TSSK_KEY_SIZE + RSA_PADDING || blob.copyOfRange(TSSK_KEY_SIZE, blob.size).any { it != ZERO }) {
            throw RdpProtocolException("proprietary certificate signature of ${blob.size} bytes")
        }
        val value = crypto.modPow(blob.copyOfRange(0, TSSK_KEY_SIZE).reversedArray(), TSSK_EXPONENT, TSSK_MODULUS)
            .toLittleEndianField(TSSK_KEY_SIZE)
        // The signed value is the hash, then 0x00, then 0xFF up to a trailing 0x01 — a fixed shape
        // that leaves an attacker who cannot produce it nowhere to hide a second hash.
        val wellFormed = value[HASH_SIZE] == ZERO && value[SIGNATURE_TAIL] == 1.toByte() &&
            (HASH_SIZE + 1 until SIGNATURE_TAIL).all { value[it] == 0xFF.toByte() } &&
            constantTimeEquals(value.copyOfRange(0, HASH_SIZE), crypto.md5(signed))
        if (!wellFormed) {
            throw RdpProtocolException("the proprietary certificate is not signed by the Terminal Services key")
        }
    }

    private fun x509ChainPublicKey(reader: RdpReader): RdpRsaPublicKey? {
        val count = reader.u32le()
        if (count !in 1..MAX_CERTIFICATES) throw RdpProtocolException("certificate chain of $count entries")
        var leaf: ByteArray? = null
        repeat(count) { leaf = reader.bytes(reader.u32le().boundedLength()) }
        return leaf?.let(crypto::rsaPublicKeyOf)
    }

    /**
     * RSA over the premaster secret (MS-RDPELE 5.1.1.1). Everything on the wire is little-endian and
     * no padding scheme is applied, so the value is reversed into a number, exponentiated, and
     * reversed back into a field of the modulus' width — followed by the eight zero bytes every RDP
     * client appends.
     */
    private fun encryptPremaster(key: RdpRsaPublicKey): ByteArray =
        crypto.modPow(premasterSecret.reversedArray(), key.exponent, key.modulus)
            .toLittleEndianField(key.modulus.size) + ByteArray(RSA_PADDING)

    /**
     * A big-endian RSA result as the little-endian field of [width] bytes the wire carries: left-pad
     * (a product with leading zero bytes comes back short), then flip.
     */
    private fun ByteArray.toLittleEndianField(width: Int): ByteArray {
        val field = ByteArray(width)
        val count = minOf(size, width)
        copyInto(field, width - count, size - count, size)
        return field.reversedArray()
    }

    /**
     * The key schedule of MS-RDPELE 5.1.2: a master secret from the premaster and the two randoms,
     * a session key blob from that, then the MAC salt key and the licensing encryption key. Note the
     * randoms swap places between the two salted hashes — that asymmetry is the specification's.
     */
    private fun deriveKeys(serverRandom: ByteArray) {
        fun saltedHash(salt: ByteArray, input: ByteArray, first: ByteArray, second: ByteArray): ByteArray =
            crypto.md5(salt + crypto.sha1(input + salt + first + second))

        fun expand(salt: ByteArray, first: ByteArray, second: ByteArray): ByteArray =
            saltedHash(salt, "A".encodeToByteArray(), first, second) +
                saltedHash(salt, "BB".encodeToByteArray(), first, second) +
                saltedHash(salt, "CCC".encodeToByteArray(), first, second)

        val masterSecret = expand(premasterSecret, clientRandom, serverRandom)
        val sessionKeyBlob = expand(masterSecret, serverRandom, clientRandom)
        macSaltKey = sessionKeyBlob.copyOfRange(0, 16)
        licensingEncryptionKey = crypto.md5(sessionKeyBlob.copyOfRange(16, 32) + clientRandom + serverRandom)
    }

    /** MS-RDPELE 5.1.5: MD5(salt + pad2 + SHA-1(salt + pad1 + length + data)). */
    private fun mac(data: ByteArray): ByteArray {
        val pad1 = ByteArray(40) { 0x36 }
        val pad2 = ByteArray(48) { 0x5C }
        val length = RdpWriter(4).u32le(data.size).toByteArray()
        return crypto.md5(macSaltKey + pad2 + crypto.sha1(macSaltKey + pad1 + length + data))
    }

    /**
     * A hardware id (MS-RDPELE 2.2.2.3.1). The licence server uses it to index the licences it has
     * issued; it is derived from the client name rather than random so reconnecting from this
     * machine asks for the same licence instead of exhausting the pool one connection at a time.
     */
    private fun hardwareId(): ByteArray {
        val digest = crypto.md5(machineName.encodeToByteArray() + logon.username.encodeToByteArray())
        return RdpWriter(20).u32le(PLATFORM_ID).bytes(digest, 0, 16).toByteArray()
    }

    private fun message(type: Int, body: ByteArray): ByteArray {
        val writer = RdpWriter(body.size + 8)
        RdpSecurityHeader.write(writer, RdpSecurityHeader.SEC_LICENSE_PKT)
        writer.u8(type)
        writer.u8(PREAMBLE_VERSION_3_0)
        writer.u16le(body.size + 4) // wMsgSize covers the preamble
        writer.bytes(body)
        return writer.toByteArray()
    }

    private fun blob(writer: RdpWriter, type: Int, data: ByteArray) {
        writer.u16le(type)
        writer.u16le(data.size)
        writer.bytes(data)
    }

    /** LICENSE_BINARY_BLOB (MS-RDPBCGR 2.2.1.12.1.2): a type, a length and that many bytes. */
    private fun readBlob(reader: RdpReader): ByteArray {
        reader.u16le() // wBlobType
        return reader.bytes(reader.u16le().boundedLength())
    }

    /** Bounds a length the server chose before it is used to size a read. */
    private fun Int.boundedLength(): Int {
        if (this < 0 || this > MAX_FIELD_SIZE) throw RdpProtocolException("licensing field of $this bytes")
        return this
    }

    /** The licensing structures carry ANSI strings, unlike the rest of the protocol's UTF-16. */
    private fun nullTerminatedAscii(text: String): ByteArray {
        val ascii = text.map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }
        return ByteArray(ascii.size + 1) { index -> if (index < ascii.size) ascii[index] else 0 }
    }

    companion object {
        const val LICENSE_REQUEST = 0x01
        const val PLATFORM_CHALLENGE = 0x02
        const val NEW_LICENSE = 0x03
        const val UPGRADE_LICENSE = 0x04
        const val NEW_LICENSE_REQUEST = 0x13
        const val PLATFORM_CHALLENGE_RESPONSE = 0x15
        const val ERROR_ALERT = 0xFF

        private const val PREAMBLE_VERSION_3_0 = 0x03
        private const val KEY_EXCHANGE_ALG_RSA = 0x00000001

        /** Windows NT 5.2 or later, Microsoft build — what the field is expected to look like. */
        private const val PLATFORM_ID = 0x04000000 or 0x00010000

        private const val RANDOM_SIZE = 32
        private const val PREMASTER_SIZE = 48

        /** Trailing zero padding RDP appends to an RSA-encrypted field (MS-RDPBCGR 5.3.4.1). */
        private const val RSA_PADDING = 8

        /** Width of the premaster field when there is no key: a 512-bit block plus that padding. */
        private const val NULL_PREMASTER_FIELD = 64 + RSA_PADDING

        private const val BB_RANDOM_BLOB = 0x0002
        private const val BB_ENCRYPTED_DATA_BLOB = 0x0009
        private const val BB_CLIENT_USER_NAME_BLOB = 0x000F
        private const val BB_CLIENT_MACHINE_NAME_BLOB = 0x0010

        private const val CERT_CHAIN_VERSION_MASK = 0x7FFFFFFF
        private const val CERT_CHAIN_VERSION_1 = 1
        private const val CERT_CHAIN_VERSION_2 = 2
        private const val RSA1_MAGIC = 0x31415352 // "RSA1", little-endian

        private const val PLATFORM_CHALLENGE_VERSION = 0x0100
        private const val WIN32_PLATFORMCHALLENGE_TYPE = 0x0100
        private const val LICENSE_DETAIL_DETAIL = 0x0003

        private const val MAX_CERTIFICATES = 16

        /** Generous next to any real licensing field (a certificate chain is the largest). */
        private const val MAX_FIELD_SIZE = 16384

        private const val MAC_SIZE = 16
        private const val HASH_SIZE = 16
        private const val ZERO = 0.toByte()

        /** Width of the Terminal Services key, and so of the signature it produces. */
        private const val TSSK_KEY_SIZE = 64

        /** Where the 0x01 that closes a proprietary certificate's signature sits. */
        private const val SIGNATURE_TAIL = 62

        /**
         * The Terminal Services signing key (MS-RDPBCGR 5.3.3.1.1), big-endian as [RdpLicenseCrypto]
         * takes it. Its private half is published in the same section — see
         * [verifyProprietarySignature] for what the signature is and is not worth.
         */
        private val TSSK_MODULUS = hex(
            "87EA6D05 5F099320 BB61F51A 09065E6C 7D5CF63D FEBFE77C EFFE3A58 6B6563CE" +
                "954552F2 9A6BB7D7 E2C1F5EF 8720883E CB5FBA4A 1EC1BB4D C93E4372 BD5E3A3D",
        )
        private val TSSK_EXPONENT = hex("C0887B5B")

        private fun hex(text: String): ByteArray {
            val digits = text.filterNot { it == ' ' }
            return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
