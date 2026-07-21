package app.skerry.shared.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ORIGIN = SshAgentOrigin.Session("bastion.example")

private class FakeKeys(
    var identities: List<SshAgentIdentity> = emptyList(),
    var signature: SshAgentSignature? = null,
) : SshAgentKeys {
    var signedData: ByteArray? = null
    var signedFlags: Int? = null
    var listedScope: SshAgentScope? = null

    override suspend fun identities(scope: SshAgentScope): List<SshAgentIdentity> {
        listedScope = scope
        return identities
    }

    override suspend fun sign(keyBlob: ByteArray, data: ByteArray, flags: Int, scope: SshAgentScope): SshAgentSignature? {
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
    fun `asks the user before signing and refuses when they decline`() = runTest {
        val keys = FakeKeys(
            identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")),
            signature = SshAgentSignature(byteArrayOf(42), "work"),
        )
        val asked = mutableListOf<SshAgentSignPrompt>()
        val uses = mutableListOf<SshAgentUsage>()
        val service = SshAgentService(keys, onUse = { uses += it }, confirm = { asked += it; false })

        val response = service.handle(signRequest(byteArrayOf(1), byteArrayOf(7)), ORIGIN)

        assertContentEquals(SshAgentCodec.failure(), response)
        assertEquals(listOf(SshAgentAction.Declined), uses.map { it.action })
        assertEquals(ORIGIN, asked.single().origin)
        assertEquals("work", asked.single().keyComment)
        assertNull(keys.signedData, "the key was used even though the user declined")
    }

    @Test
    fun `signs once the user allows it`() = runTest {
        val keys = FakeKeys(
            identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")),
            signature = SshAgentSignature(byteArrayOf(42), "work"),
        )
        val service = SshAgentService(keys, confirm = { true })

        assertContentEquals(
            SshAgentCodec.signResponse(byteArrayOf(42)),
            service.handle(signRequest(byteArrayOf(1), byteArrayOf(7)), ORIGIN),
        )
    }

    @Test
    fun `a key the agent does not hold is refused without asking the user`() = runTest {
        // Otherwise a remote could raise a confirmation dialog at will by naming keys at random.
        val keys = FakeKeys(identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")))
        var asked = false
        val uses = mutableListOf<SshAgentUsage>()
        val service = SshAgentService(keys, onUse = { uses += it }, confirm = { asked = true; true })

        val response = service.handle(signRequest(byteArrayOf(2), byteArrayOf(7)), ORIGIN)

        assertContentEquals(SshAgentCodec.failure(), response)
        assertFalse(asked, "an unknown key blob raised a confirmation prompt")
        assertEquals(listOf(SshAgentAction.Refused), uses.map { it.action })
    }

    @Test
    fun `a confirmation channel that breaks refuses instead of taking the connection down`() = runTest {
        // The prompt lives in the UI; if that path fails there is no answer, and "no answer" is a
        // refusal — not an exception thrown at the peer's serving coroutine.
        val keys = FakeKeys(
            identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")),
            signature = SshAgentSignature(byteArrayOf(42), "work"),
        )
        val uses = mutableListOf<SshAgentUsage>()
        val service = SshAgentService(keys, onUse = { uses += it }, confirm = { error("no UI here") })

        val response = service.handle(signRequest(byteArrayOf(1), byteArrayOf(7)), ORIGIN)

        assertContentEquals(SshAgentCodec.failure(), response)
        assertEquals(listOf(SshAgentAction.Refused), uses.map { it.action })
        assertNull(keys.signedData, "the key was used despite the confirmation failing")
    }

    @Test
    fun `listing keys never asks for confirmation`() = runTest {
        // Only signing uses a key; prompting on enumeration would fire on every single login.
        var asked = false
        val keys = FakeKeys(identities = listOf(SshAgentIdentity(byteArrayOf(1), "work")))

        SshAgentService(keys, confirm = { asked = true; true }).handle(byteArrayOf(11), ORIGIN)

        assertFalse(asked)
    }


    @Test
    fun `passes the host's key set down to the keyring`() = runTest {
        // Which keys a host may use is the host's setting; the service only carries it through, so
        // the keyring stays the single place that decides what a key request can reach.
        val scope = SshAgentScope(setOf("deploy"))
        val keys = FakeKeys()

        SshAgentService(keys).handle(byteArrayOf(11), ORIGIN, scope)

        assertEquals(scope, keys.listedScope)
    }

    @Test
    fun `a request with no host restriction sees every key in the agent`() = runTest {
        val keys = FakeKeys()

        SshAgentService(keys).handle(byteArrayOf(11), ORIGIN)

        assertEquals(SshAgentScope.All, keys.listedScope)
    }

    @Test
    fun `listing keys is recorded as a use so the audit shows silent probes`() = runTest {
        val uses = mutableListOf<SshAgentUsage>()
        SshAgentService(FakeKeys()) { uses += it }.handle(byteArrayOf(11), ORIGIN)
        assertTrue(uses.single().action == SshAgentAction.Listed)
    }
}
