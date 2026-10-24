package app.skerry.shared.securitykey

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OwnedSecurityKeyConnectionTest {
    @Test fun constructorFailureClosesAlreadyAcquiredTransport() = runBlocking {
        val closed = AtomicBoolean()
        assertFailsWith<IllegalStateException> {
            withOwnedSecurityKeyConnection(Closeable { closed.set(true) }) { throw IllegalStateException("synthetic setup failure") }
        }
        assertTrue(closed.get(), "raw transport leaked when CTAP getInfo failed")
    }
    @Test fun cancellationClosesTransportBeforeWaitingForBlockedConstructor() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val resource = Closeable { closed.countDown(); release.countDown() }
        val job = launch(Dispatchers.IO) {
            withOwnedSecurityKeyConnection(resource) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(closed.await(1, TimeUnit.SECONDS), "cancellation waited for CTAP constructor instead of closing the raw connection")
        } finally { release.countDown(); job.cancel(); job.join() }
    }
}
