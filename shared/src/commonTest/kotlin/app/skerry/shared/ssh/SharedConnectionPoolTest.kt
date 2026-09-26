package app.skerry.shared.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SharedConnectionPool] is what turns "a login per tab" into "a login per jump host". Covered: one
 * dial for concurrent and later sessions, the linger and its end, a dial nobody waits for, a
 * connection that died while idle, the vault lock, and that one account never rides another's.
 */
class SharedConnectionPoolTest {

    private val bastion = SshTarget(host = "jump.example", username = "me")
    private val auth = SshAuth.Interactive

    private fun TestScope.pool(transport: SshTransport) =
        SharedConnectionPool(transport, backgroundScope, lingerMillis = LINGER)

    @Test
    fun `sessions opened together share one dial`() = runTest {
        val transport = CountingTransport().apply { gate = CompletableDeferred() }
        val pool = pool(transport)
        val first = async { pool.acquire(bastion, auth) }
        val second = async { pool.acquire(bastion, auth) }
        runCurrent()
        transport.gate!!.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, transport.dials)
    }

    @Test
    fun `a later session reuses the live connection`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, auth)
        pool.acquire(bastion, auth)
        assertEquals(1, transport.dials)
    }

    @Test
    fun `different credentials never share a connection`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, SshAuth.Password("alice"))
        pool.acquire(bastion, SshAuth.Password("bob"))
        assertEquals(2, transport.dials)
    }

    @Test
    fun `the connection outlives its last session for the linger period`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, auth).disconnect()
        advanceTimeBy(LINGER - 1)
        assertEquals(0, transport.connections.single().disconnects)
        val again = pool.acquire(bastion, auth)
        assertEquals(1, transport.dials)
        again.disconnect()
        advanceTimeBy(LINGER + 1)
        assertEquals(1, transport.connections.single().disconnects)
    }

    @Test
    fun `a session closing does not close the connection under another`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        val first = pool.acquire(bastion, auth)
        pool.acquire(bastion, auth)
        first.disconnect()
        first.disconnect() // a second teardown of the same session returns nothing more
        advanceTimeBy(LINGER * 2)
        assertEquals(0, transport.connections.single().disconnects)
    }

    @Test
    fun `a dropped connection is dialed again`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, auth)
        transport.connections.single().connected = false
        pool.acquire(bastion, auth)
        assertEquals(2, transport.dials)
    }

    @Test
    fun `an idle connection that stopped answering is replaced`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, auth).disconnect()
        transport.connections.single().answers = false
        pool.acquire(bastion, auth)
        assertEquals(2, transport.dials)
        assertEquals(1, transport.connections.first().disconnects)
    }

    @Test
    fun `a failed dial is not handed to the next session`() = runTest {
        val transport = CountingTransport().apply { failWith = SshConnectionException("refused") }
        val pool = pool(transport)
        assertFailsWith<SshConnectionException> { pool.acquire(bastion, auth) }
        transport.failWith = null
        pool.acquire(bastion, auth)
        assertEquals(2, transport.dials)
    }

    @Test
    fun `one waiter giving up does not fail the others`() = runTest {
        val transport = CountingTransport().apply { gate = CompletableDeferred() }
        val pool = pool(transport)
        val quitter = async { pool.acquire(bastion, auth) }
        val stayer = async { pool.acquire(bastion, auth) }
        runCurrent()
        quitter.cancel()
        runCurrent()
        transport.gate!!.complete(Unit)
        assertTrue(stayer.await().isConnected)
        assertEquals(1, transport.dials)
    }

    @Test
    fun `a dial nobody waits for any more is cancelled`() = runTest {
        val transport = CountingTransport().apply { gate = CompletableDeferred() }
        val pool = pool(transport)
        val only = async { pool.acquire(bastion, auth) }
        runCurrent()
        only.cancel()
        runCurrent()
        // The login the dial was waiting on is answered after the tab is gone: nothing may come of it.
        transport.gate!!.complete(Unit)
        runCurrent()
        assertTrue(transport.connections.isEmpty())
        pool.acquire(bastion, auth)
        assertEquals(2, transport.dials)
    }

    @Test
    fun `the vault lock closes idle connections and keeps leased ones`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, auth).disconnect()
        val other = bastion.copy(host = "jump2.example")
        pool.acquire(other, auth)
        pool.closeIdle()
        assertEquals(1, transport.connections[0].disconnects)
        assertEquals(0, transport.connections[1].disconnects)
        pool.acquire(bastion, auth)
        assertEquals(3, transport.dials)
    }

    @Test
    fun `a session given up while the idle connection is checked hands its lease back`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion, auth).disconnect()
        transport.connections.single().roundTripGate = CompletableDeferred()
        val waiter = async { pool.acquire(bastion, auth) }
        runCurrent()
        waiter.cancel()
        runCurrent()
        // A lease stuck at one would keep the connection open past the lock for good.
        pool.closeIdle()
        assertEquals(1, transport.connections.single().disconnects)
    }

    @Test
    fun `a session ending after the vault lock takes its connection down at once`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        val session = pool.acquire(bastion, auth)
        pool.closeIdle()
        session.disconnect()
        runCurrent()
        assertEquals(1, transport.connections.single().disconnects)
        // Unlocked again: a new connection lingers as usual.
        pool.acquire(bastion, auth).disconnect()
        runCurrent()
        assertEquals(0, transport.connections[1].disconnects)
    }

    @Test
    fun `sessions differing only in keep-alive share one login`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        pool.acquire(bastion.copy(keepAliveSeconds = 30), auth)
        pool.acquire(bastion.copy(keepAliveSeconds = 0), auth)
        assertEquals(1, transport.dials)
    }

    @Test
    fun `a hop further out with other credentials is another connection`() = runTest {
        val transport = CountingTransport()
        val pool = pool(transport)
        val outer = SshJump(host = "outer", username = "me", auth = SshAuth.Password("a"))
        pool.acquire(bastion.copy(jump = outer), auth)
        pool.acquire(bastion.copy(jump = outer.copy(auth = SshAuth.Password("b"))), auth)
        pool.acquire(bastion.copy(jump = outer.copy(username = "you")), auth)
        assertEquals(3, transport.dials)
    }

    private companion object {
        const val LINGER = 60_000L
    }
}
