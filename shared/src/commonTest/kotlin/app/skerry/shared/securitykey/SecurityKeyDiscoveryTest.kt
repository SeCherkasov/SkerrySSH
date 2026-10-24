package app.skerry.shared.securitykey

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityKeyDiscoveryTest {
    @Test fun unrelatedAuthorizedHidIsSkippedBeforeSelectingRealFidoToken() = runTest {
        val validated = mutableListOf<String>()
        val discovery = SecurityKeyDiscovery<String>(permission = { true }, validate = { validated.add(it); it == "token" })
        discovery.offer("vendor HID"); discovery.offer("token")
        assertEquals("token", discovery.await())
        assertEquals(listOf("vendor HID", "token"), validated)
    }
    @Test fun deniedCandidateDoesNotDiscardTheNextQueuedUsbToken() = runTest {
        val discovery = SecurityKeyDiscovery<String>(permission = { it != "denied" }, validate = { true })
        discovery.offer("denied"); discovery.offer("token")
        val result = try { discovery.await() } catch (_: SecurityKeyException) { "operation aborted" }
        assertEquals("token", result)
    }
    @Test fun nfcCanWinWhileUnrelatedUsbPermissionQuestionIsPending() = runTest {
        val requested = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val discovery = SecurityKeyDiscovery<String>(permission = { requested.complete(Unit); answer.await() }, validate = { true })
        discovery.offer("vendor HID")
        val result = async { discovery.await() }
        try {
            requested.await()
            discovery.offerAlternative("NFC token")
            assertEquals("NFC token", withTimeoutOrNull(100) { result.await() })
        } finally { answer.complete(false); result.cancel(); result.join() }
    }
    @Test fun candidateQueueHasABoundedCapacity() {
        val discovery = SecurityKeyDiscovery<Int>(permission = { true }, validate = { true })
        repeat(16) { assertTrue(discovery.offer(it)) }
        assertFalse(discovery.offer(16))
    }
}
