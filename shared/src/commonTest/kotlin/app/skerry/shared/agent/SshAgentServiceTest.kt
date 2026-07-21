package app.skerry.shared.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ORIGIN = SshAgentOrigin.Session("bastion.example")

private class FakeKeys(
    var identities: List<SshAgentIdentity> = emptyList(),
    var signature: SshAgentSignature? = null,
) : SshAgentKeys {
    var signedData: ByteArray? = null
    var signedFlags: Int? = null

    override suspend fun identities(): List<SshAgentIdentity> = identities

    override suspend fun sign(keyBlob: ByteArray, data: ByteArray, flags: Int): SshAgentSignature? {
        signedData = data
        signedFlags = flags
        return signature.takeIf { identities.any { id -> id.keyBlob.contentEquals(keyBlob) } }
    }
}

private fun signRequest(keyBlob: ByteArray, data: ByteArray, flags: Int = 0): ByteArray {
    fun uint32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun string(b: ByteArray) = uint32(b.size) + b
    return byteArrayOf(13) + string(keyBlob) + string(data) + uint32(flags)
}

class SshAgentServiceTest {

    @Test
    fun `answers the identity list from the keyring`() = runTest {
        val keys = FakeKeys(identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")))
        val response = SshAgentService(keys).handle(byteArrayOf(11), ORIGIN)
        assertContentEquals(SshAgentCodec.identitiesAnswer(keys.identities), response)
    }

    @Test
    fun `signs with a known key and reports the use`() = runTest {
        val keys = FakeKeys(
            identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")),
            signature = SshAgentSignature(byteArrayOf(42), "work"),
        )
        val uses = mutableListOf<SshAgentUsage>()
        val response = SshAgentService(keys) { uses += it }
            .handle(signRequest(byteArrayOf(1), byteArrayOf(7, 7), SshAgentCodec.FLAG_RSA_SHA2_256), ORIGIN)

        assertContentEquals(SshAgentCodec.signResponse(byteArrayOf(42)), response)
        assertContentEquals(byteArrayOf(7, 7), keys.signedData)
        assertEquals(SshAgentCodec.FLAG_RSA_SHA2_256, keys.signedFlags)
        assertEquals(listOf(SshAgentAction.Signed), uses.map { it.action })
        assertEquals("work", uses.single().keyComment)
        assertEquals(ORIGIN, uses.single().origin)
    }

    @Test
    fun `refuses to sign with a key the vault does not offer`() = runTest {
        val keys = FakeKeys(identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")))
        val uses = mutableListOf<SshAgentUsage>()
        val response = SshAgentService(keys) { uses += it }
            .handle(signRequest(byteArrayOf(2), byteArrayOf(7)), ORIGIN)

        assertContentEquals(SshAgentCodec.failure(), response)
        assertEquals(listOf(SshAgentAction.Refused), uses.map { it.action })
    }

    @Test
    fun `refuses mutating requests`() = runTest {
        // Adding, removing or locking keys is the vault's business: a forwarded remote must not be
        // able to plant a key that later sessions would offer onward.
        val service = SshAgentService(FakeKeys())
        listOf(17, 18, 19, 20, 21, 22, 25, 27).forEach { type ->
            assertContentEquals(SshAgentCodec.failure(), service.handle(byteArrayOf(type.toByte()), ORIGIN), "type $type")
        }
    }

    @Test
    fun `routine unsupported requests are answered but not recorded`() = runTest {
        // OpenSSH sends session-bind@openssh.com before every login; recording it would stamp a
        // "Refused" on every successful connection and bury the entries that matter.
        val uses = mutableListOf<SshAgentUsage>()
        val response = SshAgentService(FakeKeys()) { uses += it }.handle(byteArrayOf(27), ORIGIN)

        assertContentEquals(SshAgentCodec.failure(), response)
        assertTrue(uses.isEmpty(), "routine protocol chatter was recorded: \${uses.map { it.action }}")
    }

    @Test
    fun `refuses a malformed request without throwing`() = runTest {
        val service = SshAgentService(FakeKeys())
        assertContentEquals(SshAgentCodec.failure(), service.handle(ByteArray(0), ORIGIN))
        assertContentEquals(SshAgentCodec.failure(), service.handle(byteArrayOf(13, 0, 0), ORIGIN))
    }

    @Test
    fun `refuses an oversized request without parsing it`() = runTest {
        val response = SshAgentService(FakeKeys()).handle(ByteArray(SshAgentCodec.MAX_MESSAGE_BYTES + 1), ORIGIN)
        assertContentEquals(SshAgentCodec.failure(), response)
    }

    @Test
    fun `listing keys is recorded as a use so the audit shows silent probes`() = runTest {
        val uses = mutableListOf<SshAgentUsage>()
        SshAgentService(FakeKeys()) { uses += it }.handle(byteArrayOf(11), ORIGIN)
        assertTrue(uses.single().action == SshAgentAction.Listed)
    }
}
