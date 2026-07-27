package app.skerry.ui.connection

import app.skerry.shared.ssh.KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun challenge(prompt: String = "Verification code:") = KeyboardInteractiveChallenge(
    name = "Two-factor authentication",
    instruction = "",
    prompts = listOf(KeyboardInteractivePrompt(prompt, echo = false)),
)

class KeyboardInteractivePromptControllerTest {

    @Test
    fun `answers the challenge with what the user submitted`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val answering = async { controller.responder.respond(challenge()) }
        yield()

        assertNotNull(controller.pending.value, "the prompt should be showing")
        controller.submit(listOf("424242"))

        assertEquals(listOf("424242"), answering.await())
        assertNull(controller.pending.value, "the prompt should close after answering")
    }

    @Test
    fun `dismissing the prompt aborts authentication`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val answering = async { controller.responder.respond(challenge()) }
        yield()
        controller.dismiss()

        assertNull(answering.await(), "a dismissed prompt must abort, not answer with an empty string")
        assertNull(controller.pending.value)
    }

    @Test
    fun `a second challenge waits for the first to be answered`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val first = async { controller.responder.respond(challenge("First:")) }
        yield()
        val second = async { controller.responder.respond(challenge("Second:")) }
        yield()

        assertEquals(
            "First:",
            controller.pending.value?.challenge?.prompts?.single()?.text,
            "two connections asking at once must queue, not overwrite each other's prompt",
        )

        controller.submit(listOf("one"))
        assertEquals(listOf("one"), first.await())
        yield()

        assertEquals("Second:", controller.pending.value?.challenge?.prompts?.single()?.text)
        controller.submit(listOf("two"))
        assertEquals(listOf("two"), second.await())
    }

    @Test
    fun `submitting with no prompt showing is ignored`() = runTest {
        val controller = KeyboardInteractivePromptController()

        controller.submit(listOf("stray"))
        controller.dismiss()

        assertNull(controller.pending.value)
    }

    @Test
    fun `a stale answer does not resolve the next challenge`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val first = async { controller.responder.respond(challenge("First:")) }
        yield()
        val stale = assertNotNull(controller.pending.value).id
        controller.submit(listOf("one"))
        first.await()

        val second = async { controller.responder.respond(challenge("Second:")) }
        yield()
        // The dialog for the first challenge submitting late (recomposition, double click) must not
        // put its answer into the second challenge, which belongs to another connection entirely.
        controller.submit(listOf("late"), requestId = stale)

        assertTrue(second.isActive, "the stale answer should have been dropped")
        controller.submit(listOf("two"))
        assertEquals(listOf("two"), second.await())
    }

    @Test
    fun `an unanswered prompt expires and aborts the connection`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val answering = async { controller.responder.respond(challenge()) }
        yield()
        advanceTimeBy(KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS + 1)

        assertNull(answering.await(), "an unanswered prompt must abort rather than wait forever")
        assertNull(controller.pending.value)
    }

    @Test
    fun `a queued challenge only starts its clock once it is shown`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val first = async { controller.responder.respond(challenge("First:")) }
        yield()
        val second = async { controller.responder.respond(challenge("Second:")) }
        yield()

        // The first prompt sits on screen for nearly the whole budget while the second waits its turn.
        advanceTimeBy(KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS - 1_000)
        controller.submit(listOf("one"))
        first.await()
        yield()

        assertEquals("Second:", controller.pending.value?.challenge?.prompts?.single()?.text)
        advanceTimeBy(KEYBOARD_INTERACTIVE_TIMEOUT_MILLIS - 1_000)
        // Had the clock started when the server asked, this prompt would already have expired unseen
        // and the connection would have failed as if the code had been wrong.
        controller.submit(listOf("two"))
        assertEquals(listOf("two"), second.await())
    }

    @Test
    fun `cancelPending aborts the prompt when the vault locks`() = runTest {
        val controller = KeyboardInteractivePromptController()

        val answering = async { controller.responder.respond(challenge()) }
        yield()
        controller.cancelPending()

        assertNull(answering.await(), "locking must fail the attempt, not leave it waiting off screen")
        assertNull(controller.pending.value)
    }
}
