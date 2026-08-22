package app.skerry.ui.trust

import app.skerry.shared.trust.HOST_TRUST_TIMEOUT_MILLIS
import app.skerry.shared.trust.HostTrustKind
import app.skerry.shared.trust.HostTrustRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun request(host: String = "example.com", recorded: String? = null) = HostTrustRequest(
    kind = HostTrustKind.SshHostKey,
    host = host,
    port = 22,
    keyType = "ssh-ed25519",
    fingerprint = "SHA256:aa",
    recordedFingerprint = recorded,
)

class HostTrustPromptControllerTest {

    @Test
    fun `accepting trusts the key`() = runTest {
        val controller = HostTrustPromptController()

        val asking = async { controller.confirm(request()) }
        yield()

        assertNotNull(controller.pending.value, "the question should be showing")
        controller.accept()

        assertTrue(asking.await())
        assertNull(controller.pending.value, "the dialog should close after answering")
    }

    @Test
    fun `refusing fails the connection`() = runTest {
        val controller = HostTrustPromptController()

        val asking = async { controller.confirm(request()) }
        yield()
        controller.refuse()

        assertFalse(asking.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun `a second question waits for the first to be answered`() = runTest {
        val controller = HostTrustPromptController()

        val first = async { controller.confirm(request("first.example")) }
        yield()
        val second = async { controller.confirm(request("second.example")) }
        yield()

        assertEquals(
            "first.example",
            controller.pending.value?.request?.host,
            "two connections asking at once must queue, not overwrite each other's dialog",
        )

        controller.accept()
        assertTrue(first.await())
        yield()

        assertEquals("second.example", controller.pending.value?.request?.host)
        controller.refuse()
        assertFalse(second.await())
    }

    @Test
    fun `an answer from a replaced dialog is ignored`() = runTest {
        val controller = HostTrustPromptController()

        val asking = async { controller.confirm(request()) }
        yield()
        val stale = controller.pending.value!!.id - 1

        controller.accept(stale)
        yield()
        assertTrue(asking.isActive, "an answer for another question must not settle this one")

        controller.refuse()
        assertFalse(asking.await())
    }

    @Test
    fun `a question nobody answers refuses rather than trusting`() = runTest {
        val controller = HostTrustPromptController()

        val asking = async { controller.confirm(request()) }
        yield()
        advanceTimeBy(HOST_TRUST_TIMEOUT_MILLIS + 1)

        assertFalse(asking.await(), "an unanswered key must not be trusted")
        assertNull(controller.pending.value)
    }

    @Test
    fun `locking the vault refuses what is on screen`() = runTest {
        val controller = HostTrustPromptController()

        val asking = async { controller.confirm(request()) }
        yield()
        controller.cancelPending()

        assertFalse(asking.await())
    }

    @Test
    fun `answering with nothing on screen is ignored`() = runTest {
        val controller = HostTrustPromptController()

        controller.accept()
        controller.refuse()

        assertNull(controller.pending.value)
    }
}
