package app.skerry.shared.trust

import kotlinx.coroutines.delay
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockingHostTrustTest {

    private val request = HostTrustRequest(
        kind = HostTrustKind.SshHostKey,
        host = "example.com",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprint = "SHA256:aa",
    )

    @Test
    fun `passes the answer through`() {
        assertTrue(HostTrustPrompt { true }.asDecider().decide(request))
        assertFalse(HostTrustPrompt { false }.asDecider().decide(request))
    }

    @Test
    fun `a prompt that never answers refuses instead of pinning the transport thread`() {
        val decider = HostTrustPrompt {
            delay(Long.MAX_VALUE)
            true
        }.asDecider(timeoutMillis = 50)

        assertFalse(decider.decide(request))
    }

    @Test
    fun `a prompt that throws fails the connection with its own reason, not as a refusal`() {
        // Both outcomes end the connection, so the only thing at stake is what it is reported as. A
        // dialog that cannot be drawn is a bug in this client, and swallowing it here would leave
        // the user reading "host key not accepted" about a question they were never asked.
        val decider = HostTrustPrompt { error("no UI") }.asDecider()

        val failure = assertFailsWith<IllegalStateException> { decider.decide(request) }
        assertEquals("no UI", failure.message)
    }

    @Test
    fun `an interrupt refuses and leaves the flag set for the transport being torn down`() {
        // sshj interrupts its reader to stop it. runBlocking turns that into an exception and
        // clears the flag; a thread that loses it goes on waiting for a socket nobody will feed.
        var trusted = true
        var interrupted = false
        val decider = HostTrustPrompt {
            delay(Long.MAX_VALUE)
            true
        }.asDecider()
        val handshake = thread {
            trusted = decider.decide(request)
            interrupted = Thread.currentThread().isInterrupted
        }
        while (handshake.state != Thread.State.TIMED_WAITING && handshake.state != Thread.State.WAITING) {
            Thread.sleep(1)
        }
        handshake.interrupt()
        handshake.join(5_000)

        assertFalse(trusted, "an interrupted question trusted the key")
        assertTrue(interrupted, "the interrupt the transport sent was swallowed")
    }

    @Test
    fun `asks once per decision`() {
        var asked = 0
        val decider = HostTrustPrompt {
            asked++
            true
        }.asDecider()

        decider.decide(request)
        decider.decide(request)

        assertEquals(2, asked)
    }
}
