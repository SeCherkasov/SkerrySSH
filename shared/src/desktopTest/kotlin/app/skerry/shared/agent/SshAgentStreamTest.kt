package app.skerry.shared.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * The framing layer against a peer that behaves like a real `ssh`: several requests over ONE
 * connection, and messages that arrive in pieces.
 */
class SshAgentStreamTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identities = listOf(SshAgentIdentity(byteArrayOf(1, 2), "work key"))

    @AfterTest
    fun tearDown() = scope.cancel()

    private val keys = object : SshAgentKeys {
        override suspend fun identities(scope: SshAgentScope) = identities
        override suspend fun sign(keyBlob: ByteArray, data: ByteArray, flags: Int, scope: SshAgentScope): SshAgentSignature? = null
    }

    /** Wire both directions to the serving loop and hand back the peer's ends. */
    private fun serve(): Pair<PipedOutputStream, InputStream> {
        val toAgent = PipedOutputStream()
        val agentInput = PipedInputStream(toAgent, 8192)
        val agentOutput = PipedOutputStream()
        val fromAgent = PipedInputStream(agentOutput, 8192)
        scope.launch { serveSshAgent(agentInput, agentOutput, SshAgentService(keys), SshAgentOrigin.LocalSocket) }
        return toAgent to fromAgent
    }

    private fun InputStream.readReply(): ByteArray {
        val header = readNBytes(4)
        val length = (header[0].toInt() and 0xFF shl 24) or (header[1].toInt() and 0xFF shl 16) or
            (header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF)
        return readNBytes(length)
    }

    @Test
    fun `serves several requests over one connection`() = runTest {
        // OpenSSH sends session-bind, then the listing, then the signature — all on one connection.
        val (toAgent, fromAgent) = serve()
        withTimeout(TIMEOUT_MILLIS) {
            val extension = ByteArrayOutputStream().apply { write(byteArrayOf(27)) }.toByteArray()
            toAgent.write(SshAgentCodec.frame(extension))
            toAgent.flush()
            assertContentEquals(SshAgentCodec.failure(), fromAgent.readReply())

            toAgent.write(SshAgentCodec.frame(byteArrayOf(11)))
            toAgent.flush()
            assertContentEquals(SshAgentCodec.identitiesAnswer(identities), fromAgent.readReply())

            toAgent.write(SshAgentCodec.frame(byteArrayOf(11)))
            toAgent.flush()
            assertContentEquals(SshAgentCodec.identitiesAnswer(identities), fromAgent.readReply())
        }
    }

    @Test
    fun `reassembles a message split across reads`() = runTest {
        // TCP (and a pipe) may deliver the length prefix and the body separately; the reader must
        // wait for the whole message instead of treating a short read as the end.
        val (toAgent, fromAgent) = serve()
        val framed = SshAgentCodec.frame(byteArrayOf(11))
        withTimeout(TIMEOUT_MILLIS) {
            toAgent.write(framed, 0, 2)
            toAgent.flush()
            toAgent.write(framed, 2, framed.size - 2)
            toAgent.flush()
            assertContentEquals(SshAgentCodec.identitiesAnswer(identities), fromAgent.readReply())
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
