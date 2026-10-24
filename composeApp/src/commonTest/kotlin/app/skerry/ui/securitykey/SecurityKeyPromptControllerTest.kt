package app.skerry.ui.securitykey

import app.skerry.shared.securitykey.SecurityKeyOperation
import app.skerry.shared.securitykey.SecurityKeyRequestContext
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityKeyPromptControllerTest {
    @Test fun answersStayBoundToRequestAndLockDrainsQueue() = runTest {
        val controller = SecurityKeyPromptController()
        val context = SecurityKeyRequestContext(endpoint = "host:22", fingerprint = "SHA256:test", application = "ssh:", operation = SecurityKeyOperation.Sign)
        val first = async { controller.forRequest(context).requestPin(3) }
        runCurrent()
        val question = controller.pending.value!!
        assertEquals(context, question.context)
        val second = async { controller.forRequest(context.copy(endpoint = "other:22")).requestPin() }
        runCurrent()
        val stalePin = "wrong".toCharArray()
        controller.answer(question.id + 1, stalePin)
        assertContentEquals(CharArray(5), stalePin)
        assertEquals(question.id, controller.pending.value!!.id)
        controller.answer(question.id, "1234".toCharArray())
        runCurrent()
        assertContentEquals("1234".toCharArray(), first.await())
        controller.cancelPending()
        runCurrent()
        assertNull(second.await())
        assertNull(controller.pending.value)
    }
    @Test fun cancellationRemovesVisibleSecretQuestion() = runTest {
        val controller = SecurityKeyPromptController()
        val task = async { controller.requestPin() }
        runCurrent()
        task.cancel()
        runCurrent()
        assertNull(controller.pending.value)
    }
}
