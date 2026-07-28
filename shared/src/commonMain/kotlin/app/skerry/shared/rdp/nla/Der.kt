package app.skerry.shared.rdp.nla

import app.skerry.shared.rdp.Ber
import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpReader
import app.skerry.shared.rdp.RdpWriter

/**
 * The DER subset the CredSSP structures need: SEQUENCE, INTEGER, OCTET STRING and the
 * context-specific `[n]` wrappers of TSRequest/TSCredentials (MS-CSSP 2.2.1).
 *
 * Shares [Ber]'s length encoding, which is already minimal and therefore DER-conformant. Only the
 * explicit tagging CredSSP uses is added here.
 */
object Der {
    const val TAG_INTEGER = 0x02
    const val TAG_BIT_STRING = 0x03
    const val TAG_OCTET_STRING = 0x04
    const val TAG_SEQUENCE = 0x30

    /** Context-specific constructed tag `[n]`, the form every CredSSP field uses. */
    fun contextTag(index: Int): Int = 0xA0 or index

    /** Wrap [content] in tag [tag] with its length. */
    fun tagged(tag: Int, content: ByteArray): ByteArray {
        val writer = RdpWriter(content.size + 4)
        writer.u8(tag)
        Ber.length(writer, content.size)
        writer.bytes(content)
        return writer.toByteArray()
    }

    fun sequence(content: ByteArray): ByteArray = tagged(TAG_SEQUENCE, content)

    fun octetString(content: ByteArray): ByteArray = tagged(TAG_OCTET_STRING, content)

    /**
     * DER INTEGER, two's complement and minimally encoded. Negative values matter: an NTSTATUS
     * error code travels in this field.
     */
    fun integer(value: Int): ByteArray {
        val body = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
        var start = 0
        // Drop leading bytes that only repeat the sign of the byte after them.
        while (start < body.size - 1) {
            val lead = body[start].toInt() and 0xFF
            val nextHasHighBit = body[start + 1].toInt() and 0x80 != 0
            if ((lead == 0x00 && !nextHasHighBit) || (lead == 0xFF && nextHasHighBit)) start++ else break
        }
        return tagged(TAG_INTEGER, body.copyOfRange(start, body.size))
    }

    /** Read a tag byte and its length, returning a reader bounded to the content. */
    fun readTagged(reader: RdpReader, expectedTag: Int): RdpReader {
        val tag = reader.u8()
        if (tag != expectedTag) {
            throw RdpProtocolException("expected DER tag 0x${expectedTag.toString(16)}, got 0x${tag.toString(16)}")
        }
        return reader.slice(Ber.readLength(reader))
    }

    /** Peek at the next tag without consuming it; -1 when the reader is exhausted. */
    fun peekTag(reader: RdpReader): Int = if (reader.remaining == 0) -1 else reader.peekU8()

    /**
     * DER INTEGER as a 32-bit value. Error codes are NTSTATUS values with the top bit set, which a
     * conformant encoder prefixes with a zero byte; truncating to [Int] restores the familiar
     * negative form either way.
     */
    fun readInteger(reader: RdpReader): Int {
        val content = readTagged(reader, TAG_INTEGER)
        if (content.remaining > 5) throw RdpProtocolException("DER integer wider than 32 bits")
        var value = 0L
        while (content.remaining > 0) value = (value shl 8) or content.u8().toLong()
        return value.toInt()
    }

    fun readOctetString(reader: RdpReader): ByteArray = readTagged(reader, TAG_OCTET_STRING).rest()

    /**
     * The `subjectPublicKey` BIT STRING content of a DER SubjectPublicKeyInfo — the exact bytes
     * CredSSP binds its exchange to (MS-CSSP 3.1.5, "the ASN.1-encoded SubjectPublicKey sub-field").
     * Passing the whole SubjectPublicKeyInfo instead is the classic way to get a server that
     * silently rejects every logon.
     */
    fun subjectPublicKey(subjectPublicKeyInfo: ByteArray): ByteArray {
        val outer = readTagged(RdpReader(subjectPublicKeyInfo), TAG_SEQUENCE)
        readTagged(outer, TAG_SEQUENCE) // AlgorithmIdentifier
        val bitString = readTagged(outer, TAG_BIT_STRING)
        val unusedBits = bitString.u8()
        if (unusedBits != 0) throw RdpProtocolException("public key BIT STRING has $unusedBits unused bits")
        return bitString.rest()
    }
}
