package app.skerry.ui.tunnel

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_err_hostkey_changed
import app.skerry.ui.generated.resources.conn_err_hostkey_untrusted
import app.skerry.ui.generated.resources.ptail_err_host_not_trusted
import org.jetbrains.compose.resources.getString
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.ssh.HostKeyRefusal
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.ExecResult
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.TunnelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelManagerTest {

    private val target = SshTarget(host = "h", port = 22, username = "u")
    private val auth = SshAuth.Password("pw")

    private fun managerWith(
        transport: SshTransport,
        store: TunnelStore = FakeTunnelStore(),
        resolve: (String) -> TunnelResolution = { TunnelResolution.Ready(target, auth) },
        ids: List<String> = List(20) { "id-$it" },
        scanTransport: SshTransport? = null,
    ): TunnelManager {
        val scope = TestScope(UnconfinedTestDispatcher())
        val it = ids.iterator()
        return TunnelManager(store, transport, resolve, scope, scanTransport = scanTransport ?: transport) { it.next() }
    }

    private fun localDraft(label: String = "web", hostId: String = "h1") = TunnelDraft(
        label = label, hostId = hostId, direction = TunnelDirection.Local,
        bindHost = "127.0.0.1", bindPort = 8080, destHost = "10.0.0.5", destPort = 80,
    )

    @Test
    fun `the service scan dials through the scan transport, not the one that raises forwards`() = runTest {
        // The scan is a probe the user pressed and is watching, so an unknown host key is theirs to
        // accept; a forward runs unattended and may not accept one. Both once shared a single
        // transport, so tightening the forward silently made the scan refuse hosts it used to reach —
        // and nothing in this suite noticed, which is why the distinction is pinned here.
        val forwards = FakeTunnelTransport()
        val scans = FakeTunnelTransport()
        val manager = managerWith(forwards, scanTransport = scans)

        manager.services.scan("h1")

        assertEquals(target, scans.lastTarget, "the scan must ride the scan transport")
        assertNull(forwards.lastTarget, "the scan must not touch the transport that raises forwards")
    }

    @Test
    fun `save persists a new tunnel and lists it inactive`() = runTest {
        val store = FakeTunnelStore()
        val manager = managerWith(FakeTunnelTransport(), store)

        val id = manager.save(localDraft())

        assertEquals("id-0", id)
        val entry = manager.tunnels.single()
        assertEquals("web", entry.tunnel.label)
        assertEquals(TunnelStatus.Inactive, entry.status)
        assertEquals(listOf(id), store.all().map { it.id }) // reached the store
    }

    @Test
    fun `save with existing id updates config in place`() = runTest {
        val manager = managerWith(FakeTunnelTransport())
        val id = manager.save(localDraft(label = "old"))

        manager.save(localDraft(label = "renamed").copy(id = id))

        val entry = manager.tunnels.single()
        assertEquals("renamed", entry.tunnel.label)
    }

    @Test
    fun `delete removes the tunnel from store and list`() = runTest {
        val store = FakeTunnelStore()
        val manager = managerWith(FakeTunnelTransport(), store)
        val id = manager.save(localDraft())

        manager.delete(id)

        assertTrue(manager.tunnels.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `activate opens a connection and raises a local forward`() = runTest {
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50001))
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id)

        val entry = manager.tunnels.single()
        assertEquals(TunnelStatus.Active(50001), entry.status)
        assertEquals(target, transport.lastTarget)
        assertEquals(auth, transport.lastAuth)
        assertEquals(
            LocalForwardSpec(bindHost = "127.0.0.1", bindPort = 8080, destHost = "10.0.0.5", destPort = 80),
            transport.connection.lastLocalSpec,
        )
    }

    @Test
    fun `activate dynamic raises a SOCKS forward`() = runTest {
        val transport = FakeTunnelTransport(FakeTunnelConnection(dynamicPort = 1080))
        val manager = managerWith(transport)
        val id = manager.save(
            TunnelDraft(label = "socks", hostId = "h1", direction = TunnelDirection.Dynamic, bindPort = 1080),
        )

        manager.activate(id)

        val entry = manager.tunnels.single()
        assertEquals(TunnelStatus.Active(1080), entry.status)
        assertEquals(DynamicForwardSpec(bindHost = "127.0.0.1", bindPort = 1080), transport.connection.lastDynamicSpec)
    }

    @Test
    fun `activate fails fast when resolution is unavailable and never connects`() = runTest {
        val transport = FakeTunnelTransport()
        val manager = managerWith(transport, resolve = { TunnelResolution.Unavailable(TunnelUnavailable.NoCredential) })
        val id = manager.save(localDraft())

        manager.activate(id)

        val status = assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)
        assertEquals(TunnelUnavailable.NoCredential, status.reason)
        assertNull(transport.lastTarget) // transport untouched
    }

    @Test
    fun `activate maps a rejected host key to a clear message and closes the connection`() = runTest {
        val conn = FakeTunnelConnection(localError = SshHostKeyRejectedException("bad"))
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id)

        val status = assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)
        // Message is localized (strings_ptail); compare against the resource itself so the test
        // doesn't depend on the machine locale.
        assertEquals(getString(Res.string.ptail_err_host_not_trusted), status.message)
        assertTrue(conn.disconnected) // connection not leaked
    }

    @Test
    fun `a host with no known key tells the user how to make it known`() = runTest {
        // The refusal a tunnel actually hits: it dials with UnknownHost.Refuse, so a host nobody
        // has ever opened a terminal to is turned away. Without the hint the failure is unreadable —
        // the tunnel form never asked about host keys.
        val conn = FakeTunnelConnection(
            localError = SshHostKeyRejectedException("no", HostKeyRefusal.NotTrustedYet),
        )
        val manager = managerWith(FakeTunnelTransport(conn))
        val id = manager.save(localDraft())

        manager.activate(id)

        val status = assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)
        assertEquals(getString(Res.string.conn_err_hostkey_untrusted), status.message)
    }

    @Test
    fun `a changed host key is not reported as an untrusted one`() = runTest {
        val conn = FakeTunnelConnection(
            localError = SshHostKeyRejectedException("changed", HostKeyRefusal.KeyChanged),
        )
        val manager = managerWith(FakeTunnelTransport(conn))
        val id = manager.save(localDraft())

        manager.activate(id)

        val status = assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)
        assertEquals(getString(Res.string.conn_err_hostkey_changed), status.message)
    }

    @Test
    fun `deactivate closes the forward and the connection`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50002)
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())
        manager.activate(id)

        manager.deactivate(id)

        assertEquals(TunnelStatus.Inactive, manager.tunnels.single().status)
        assertEquals(1, conn.lastForward!!.closeCount)
        assertTrue(conn.disconnected)
    }

    @Test
    fun `telemetry poll snapshots bytes and computes per-second rate`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50003)
        val manager = managerWith(FakeTunnelTransport(conn))
        val id = manager.save(localDraft())
        manager.activate(id)
        val entry = manager.tunnels.single()
        val handle = conn.lastForward!!

        handle.bytesUp = 5000
        handle.bytesDown = 200
        manager.pollTelemetry()

        assertEquals(5000, entry.bytesUp)
        assertEquals(200, entry.bytesDown)
        assertEquals(5000, entry.upRate)
        assertEquals(200, entry.downRate)
    }

    @Test
    fun `deactivate while connecting cancels the raise and never leaks the connection`() = runTest {
        // Gate in forwardLocal: connect already returned a live connection, the forward is still
        // being raised — this is the leak window (connection open, not yet recorded in entry.connection).
        val gate = CompletableDeferred<Unit>()
        val conn = FakeTunnelConnection(localPort = 50010, raiseGate = gate)
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id)
        assertEquals(TunnelStatus.Connecting, manager.tunnels.single().status)

        // Deactivate while the forward is stuck on the gate, then release the gate.
        manager.deactivate(id)
        gate.complete(Unit)

        assertEquals(TunnelStatus.Inactive, manager.tunnels.single().status)
        assertTrue(conn.disconnected) // the opened connection was closed, not left as an orphan
    }

    /**
     * A tunnel deleted on another device reaches this one as a store write plus a reload — no
     * `delete()` call is ever made here. Dropping the row without deactivating it leaves the local
     * port bound and the SSH connection open, forwarding traffic with no row left to stop it.
     */
    @Test
    fun `reload tears down a tunnel that has gone from the store`() = runTest {
        val store = FakeTunnelStore()
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50060))
        val manager = managerWith(transport, store)
        val id = manager.save(localDraft())
        manager.activate(id)
        assertIs<TunnelStatus.Active>(manager.find(id)!!.status)

        store.remove(id) // the deletion synced in from another device
        manager.reload()

        assertTrue(manager.tunnels.isEmpty())
        val forward = transport.connection.lastForward!!
        assertEquals(1, forward.closeCount, "the forward kept its port bound after the row was gone")
        assertTrue(transport.connection.disconnected, "the SSH connection outlived its row")
    }

    /**
     * The other half of the same rule: a record that is still there but cannot be read — what
     * adopting an account dataKey leaves behind for everything not yet pushed — is NOT a deletion.
     * Tearing down on it would stop a live tunnel the moment this desktop joins an existing sync
     * account, and dropping its row would leave the forward running with nothing to stop it.
     */
    @Test
    fun `reload keeps a tunnel whose record is present but unreadable`() = runTest {
        val store = FakeTunnelStore()
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50062))
        val manager = managerWith(transport, store)
        val id = manager.save(localDraft())
        manager.activate(id)

        store.unreadable += id // sealed under a superseded key: absent from all(), present in liveIds()
        manager.reload()

        assertIs<TunnelStatus.Active>(manager.find(id)?.status, "a live tunnel was torn down")
        assertEquals(0, transport.connection.lastForward!!.closeCount)
        assertTrue(manager.tunnels.any { it.id == id }, "the row vanished while its forward kept running")
    }

    /**
     * Stop-then-Start while the first attempt is still inside a blocking connect: the cancelled
     * attempt must not clear the *new* attempt's job on its way out, or nothing can cancel the new
     * one afterwards — neither the user's next Stop nor the vault lock, which is the same call.
     */
    @Test
    fun `a stop after a restart still cancels the attempt that is in flight`() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        // Uncancellable, like the real blocking connect: cancelling the first attempt does not stop
        // it, it only makes it throw once the dial finally returns.
        val conn = FakeTunnelConnection(localPort = 50061, raiseGate = second)
        val transport = FakeTunnelTransport(conn, connectGates = listOf(first, CompletableDeferred(Unit)))
        val manager = managerWith(transport)
        val id = manager.save(localDraft())

        manager.activate(id) // attempt A, stuck in connect
        manager.deactivate(id) // Stop: A is cancelled but cannot notice yet
        manager.activate(id) // Start again: attempt B is now the one in flight
        first.complete(Unit) // A's dial returns, A unwinds as cancelled

        manager.deactivate(id) // Stop again (or the vault locking, which calls the same thing)
        second.complete(Unit) // whatever B was waiting on comes back

        assertEquals(TunnelStatus.Inactive, manager.find(id)!!.status, "a tunnel came up after it was stopped")
    }

    /**
     * Nothing ever looked at a running tunnel. The server rebooting, or the laptop changing
     * network, drops the SSH connection while the local listener stays bound: the row keeps saying
     * Active, every application connecting through the port is closed immediately, and the failure
     * never reaches the journal. The telemetry poll is the one thing that visits a live tunnel.
     */
    @Test
    fun `the poll catches a tunnel whose connection died and says so`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50070)
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())
        manager.activate(id)
        assertIs<TunnelStatus.Active>(manager.find(id)!!.status)

        conn.disconnect() // the link drops underneath the forward
        manager.pollTelemetry()

        assertIs<TunnelStatus.Failed>(manager.find(id)!!.status)
        assertEquals(1, transport.connection.lastForward!!.closeCount, "the dead forward kept its port bound")
        assertTrue(
            manager.telemetry.events.any { it.tunnelId == id },
            "a tunnel that died on its own left nothing in the journal",
        )
    }

    /**
     * The poll is the only thing that visits a running tunnel, and it now does more than arithmetic
     * — it asks the connection whether it is still alive. A row that throws must not take the loop
     * with it (every other tunnel's rates and the dead-tunnel detection would go too, silently),
     * and must not be left Active either: it would throw again every second, adding an event a
     * second while its port stays bound.
     */
    @Test
    fun `a row that throws is torn down, and the rest of the poll survives`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50080)
        val transport = FakeTunnelTransport(conn)
        val manager = managerWith(transport)
        val id = manager.save(localDraft())
        manager.activate(id)

        conn.failLiveness = true
        manager.pollTick() // must not throw out of the loop

        assertIs<TunnelStatus.Failed>(manager.find(id)!!.status, "the row was left Active to throw again")
        assertEquals(1, transport.connection.lastForward!!.closeCount, "the forward kept its port bound")
        assertEquals(1, manager.telemetry.events.count { it.tunnelId == id }, "one failure, not one per tick")

        manager.pollTick() // the next tick still works, and adds nothing new
        assertEquals(1, manager.telemetry.events.count { it.tunnelId == id })
    }

    /**
     * A link that dropped and a user who pressed Stop are the same end state, and the poll must not
     * turn the second into the first: a tunnel stopped by hand stays stopped, and the journal keeps
     * no failure for something the user did on purpose.
     */
    @Test
    fun `a tunnel stopped by hand is not marked failed by the next tick`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50090)
        val manager = managerWith(FakeTunnelTransport(conn))
        val id = manager.save(localDraft())
        manager.activate(id)
        conn.disconnect() // the link drops
        manager.deactivate(id) // ...and the user presses Stop before the poll gets there
        manager.pollTick()

        assertEquals(TunnelStatus.Inactive, manager.find(id)!!.status, "a stopped tunnel was marked failed")
        assertTrue(
            manager.telemetry.events.none { it.tunnelId == id },
            "a tunnel the user stopped was journalled as a failure",
        )
    }

    @Test
    fun `closeAll deactivates every active tunnel`() = runTest {
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50020))
        val manager = managerWith(transport)
        val a = manager.save(localDraft(label = "a"))
        val b = manager.save(localDraft(label = "b"))
        manager.activate(a)
        manager.activate(b)

        manager.closeAll()

        assertTrue(manager.tunnels.all { it.status == TunnelStatus.Inactive })
    }

    @Test
    fun `closeAll also drops the service scan`() = runTest {
        // closeAll is what a vault lock calls: a scan result left behind would keep listing a host's
        // services behind the lock screen, and an in-flight one holds a connection opened with the
        // decrypted secret.
        val manager = managerWith(FakeTunnelTransport(FakeTunnelConnection(localPort = 50040)))
        manager.services.scan("h1")

        manager.closeAll()

        assertEquals(ServiceScanState.Idle, manager.services.state)
        assertNull(manager.services.scannedHostId)
    }

    @Test
    fun `activate after a failure resets the status to connecting then active`() = runTest {
        // First resolution is unavailable, second is ready — status must be able to leave Failed.
        var available = false
        val transport = FakeTunnelTransport(FakeTunnelConnection(localPort = 50030))
        val manager = managerWith(transport, resolve = {
            if (available) TunnelResolution.Ready(target, auth) else TunnelResolution.Unavailable(TunnelUnavailable.NoCredential)
        })
        val id = manager.save(localDraft())

        manager.activate(id)
        assertIs<TunnelStatus.Failed>(manager.tunnels.single().status)

        available = true
        manager.activate(id)
        assertEquals(TunnelStatus.Active(50030), manager.tunnels.single().status)
    }

    @Test
    fun `flipping autostart through a draft keeps every other field`() = runTest {
        // What the autostart list does on a toggle: round-trip the saved tunnel through a draft and
        // change one flag. A field dropped from `toDraft` would silently rewrite the tunnel.
        val store = FakeTunnelStore()
        val manager = managerWith(FakeTunnelTransport(), store)
        val id = manager.save(localDraft(label = "web"))
        val before = manager.find(id)!!.tunnel

        manager.save(before.toDraft().copy(autostart = true))

        val after = store.all().single()
        assertEquals(before.copy(autostart = true), after)
    }

    @Test
    fun `startAutostart raises only the flagged tunnels`() = runTest {
        val manager = managerWith(FakeTunnelTransport(FakeTunnelConnection(localPort = 50050)))
        val flagged = manager.save(localDraft(label = "flagged").copy(autostart = true))
        val plain = manager.save(localDraft(label = "plain"))

        manager.startAutostart()

        assertEquals(TunnelStatus.Active(50050), manager.find(flagged)!!.status)
        assertEquals(TunnelStatus.Inactive, manager.find(plain)!!.status)
    }

    @Test
    fun `startAutostart runs once per unlock and does not re-raise what the user stopped`() = runTest {
        // reloadManagers fires on every synced change, so autostart has to be a one-shot per unlock:
        // otherwise a tunnel switched off by hand would come back up on the next sync tick.
        val manager = managerWith(FakeTunnelTransport(FakeTunnelConnection(localPort = 50051)))
        val id = manager.save(localDraft().copy(autostart = true))

        manager.startAutostart()
        manager.deactivate(id)
        manager.startAutostart()

        assertEquals(TunnelStatus.Inactive, manager.find(id)!!.status)
    }

    @Test
    fun `closeAll re-arms autostart for the next unlock`() = runTest {
        val manager = managerWith(FakeTunnelTransport(FakeTunnelConnection(localPort = 50052)))
        val id = manager.save(localDraft().copy(autostart = true))
        manager.startAutostart()

        manager.closeAll() // vault lock
        manager.startAutostart() // unlocked again

        assertEquals(TunnelStatus.Active(50052), manager.find(id)!!.status)
    }

    @Test
    fun `autostart failures are reported as a set, not left one per row`() = runTest {
        // Unlock succeeding and every autostart tunnel being up are two different things. Without an
        // aggregate, a user who doesn't open the section has nothing to notice.
        var available = false
        val manager = managerWith(
            FakeTunnelTransport(FakeTunnelConnection(localPort = 50080)),
            resolve = {
                if (available) TunnelResolution.Ready(target, auth) else TunnelResolution.Unavailable(TunnelUnavailable.NoCredential)
            },
        )
        val broken = manager.save(localDraft(label = "broken").copy(autostart = true))
        manager.save(localDraft(label = "plain"))

        manager.startAutostart()

        assertEquals(listOf(broken), manager.autostartFailures.map { it.id })
    }

    @Test
    fun `a tunnel that came up is not reported as an autostart failure`() = runTest {
        val manager = managerWith(FakeTunnelTransport(FakeTunnelConnection(localPort = 50081)))
        manager.save(localDraft().copy(autostart = true))

        manager.startAutostart()

        assertTrue(manager.autostartFailures.isEmpty())
    }

    @Test
    fun `a tunnel that came up is not blamed for a later manual failure`() = runTest {
        // The banner claims the autostart run left something down. A tunnel the user toggled by
        // hand hours later, against a server that has since gone away, is not that.
        var available = true
        val manager = managerWith(
            FakeTunnelTransport(FakeTunnelConnection(localPort = 50090)),
            resolve = {
                if (available) TunnelResolution.Ready(target, auth) else TunnelResolution.Unavailable(TunnelUnavailable.NoCredential)
            },
        )
        val id = manager.save(localDraft().copy(autostart = true))
        manager.startAutostart()

        manager.deactivate(id)
        available = false
        manager.activate(id)

        assertIs<TunnelStatus.Failed>(manager.find(id)!!.status)
        assertTrue(manager.autostartFailures.isEmpty(), "autostart already answered for this tunnel")
    }

    @Test
    fun `dismissing the autostart report clears it without touching the tunnels`() = runTest {
        val manager = managerWith(
            FakeTunnelTransport(),
            resolve = { TunnelResolution.Unavailable(TunnelUnavailable.NoCredential) },
        )
        val id = manager.save(localDraft().copy(autostart = true))
        manager.startAutostart()

        manager.dismissAutostartReport()

        assertTrue(manager.autostartFailures.isEmpty())
        assertIs<TunnelStatus.Failed>(manager.find(id)!!.status) // the row still says what happened
    }

    @Test
    fun `a lock clears the autostart report`() = runTest {
        val manager = managerWith(
            FakeTunnelTransport(),
            resolve = { TunnelResolution.Unavailable(TunnelUnavailable.NoCredential) },
        )
        manager.save(localDraft().copy(autostart = true))
        manager.startAutostart()

        manager.closeAll()

        assertTrue(manager.autostartFailures.isEmpty())
    }

    @Test
    fun `polling records aggregate throughput of every active tunnel`() = runTest {
        val conn = FakeTunnelConnection(localPort = 50060)
        val manager = managerWith(FakeTunnelTransport(conn))
        val a = manager.save(localDraft(label = "a"))
        manager.activate(a)
        val handle = conn.lastForward!!
        handle.bytesUp = 2000
        handle.bytesDown = 3000

        manager.pollTelemetry()

        assertEquals(ThroughputSample(up = 2000, down = 3000), manager.telemetry.history.last())
    }

    @Test
    fun `a failed raise is written to the events journal`() = runTest {
        val manager = managerWith(
            FakeTunnelTransport(),
            resolve = { TunnelResolution.Unavailable(TunnelUnavailable.NoCredential) },
        )
        val id = manager.save(localDraft(label = "Redis"))

        manager.activate(id)

        val event = manager.telemetry.events.single()
        assertEquals("Redis", event.label)
        assertEquals(TunnelEventKind.Failed(TunnelFailureKind.Unavailable), event.kind)
    }

    @Test
    fun `coming up after a failure is written as recovered`() = runTest {
        var available = false
        val manager = managerWith(
            FakeTunnelTransport(FakeTunnelConnection(localPort = 50070)),
            resolve = {
                if (available) TunnelResolution.Ready(target, auth) else TunnelResolution.Unavailable(TunnelUnavailable.NoCredential)
            },
        )
        val id = manager.save(localDraft(label = "Redis"))

        manager.activate(id)
        available = true
        manager.activate(id)

        assertEquals(TunnelEventKind.Recovered, manager.telemetry.events.first().kind)
    }

    @Test
    fun `deleting a tunnel forgets its failure`() = runTest {
        val manager = managerWith(
            FakeTunnelTransport(),
            resolve = { TunnelResolution.Unavailable(TunnelUnavailable.NoCredential) },
        )
        val id = manager.save(localDraft(label = "Redis"))
        manager.activate(id)

        manager.delete(id)
        val again = manager.save(localDraft(label = "Redis"))
        manager.activate(again)

        assertEquals(2, manager.telemetry.events.size)
    }

    @Test
    fun `loads previously saved tunnels on construction`() = runTest {
        val store = FakeTunnelStore()
        store.put(Tunnel("x", "saved", "h1", TunnelDirection.Local, "127.0.0.1", 22, "a", 1))
        val manager = managerWith(FakeTunnelTransport(), store)

        assertEquals(listOf("saved"), manager.tunnels.map { it.tunnel.label })
        assertEquals(TunnelStatus.Inactive, manager.tunnels.single().status)
    }
}

private class FakeTunnelStore : TunnelStore {
    private val entries = mutableListOf<Tunnel>()

    /** Records whose payload no longer decrypts: present in [liveIds], missing from [all]. */
    val unreadable: MutableSet<String> = mutableSetOf()

    override fun all(): List<Tunnel> = entries.filterNot { it.id in unreadable }
    override fun liveIds(): Set<String> = entries.map { it.id }.toSet()
    override fun put(tunnel: Tunnel) {
        val i = entries.indexOfFirst { it.id == tunnel.id }
        if (i >= 0) entries[i] = tunnel else entries += tunnel
    }
    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }
}

private class FakeTunnelTransport(
    val connection: FakeTunnelConnection = FakeTunnelConnection(),
    private val connectError: Throwable? = null,
    /**
     * One gate per `connect` call, held under [NonCancellable] — the real dial is blocking, so a
     * cancelled attempt keeps running to the end and only then finds out it was cancelled. Empty
     * (the default) means every connect returns at once.
     */
    private val connectGates: List<CompletableDeferred<Unit>> = emptyList(),
) : SshTransport {
    var lastTarget: SshTarget? = null
        private set
    var lastAuth: SshAuth? = null
        private set

    private var dials = 0

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection {
        lastTarget = target
        lastAuth = auth
        connectGates.getOrNull(dials++)?.let { withContext(NonCancellable) { it.await() } }
        connectError?.let { throw it }
        return connection
    }
}

private class FakeTunnelConnection(
    private val localPort: Int = 0,
    private val remotePort: Int = 0,
    private val dynamicPort: Int = 0,
    private val localError: Throwable? = null,
    private val raiseGate: CompletableDeferred<Unit>? = null,
) : SshConnection {
    var lastLocalSpec: LocalForwardSpec? = null
        private set
    var lastRemoteSpec: RemoteForwardSpec? = null
        private set
    var lastDynamicSpec: DynamicForwardSpec? = null
        private set
    var lastForward: FakeTunnelForward? = null
        private set
    var disconnected = false
        private set

    /** Makes the liveness probe throw, standing in for anything a tick can fail on. */
    var failLiveness: Boolean = false

    override val isConnected: Boolean get() {
        if (failLiveness) error("liveness probe failed")
        return !disconnected
    }
    override suspend fun exec(command: String): ExecResult = throw UnsupportedOperationException()
    override suspend fun openShell(size: PtySize, term: String): ShellChannel = throw UnsupportedOperationException()
    override suspend fun openSftp(): SftpClient = throw UnsupportedOperationException()

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward {
        lastLocalSpec = spec
        raiseGate?.await()
        localError?.let { throw it }
        return FakeTunnelForward(localPort).also { lastForward = it }
    }

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward {
        lastRemoteSpec = spec
        return FakeTunnelForward(remotePort).also { lastForward = it }
    }

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward {
        lastDynamicSpec = spec
        return FakeTunnelForward(dynamicPort).also { lastForward = it }
    }

    override suspend fun disconnect() {
        disconnected = true
    }
}

private class FakeTunnelForward(
    override val boundPort: Int,
    override var bytesUp: Long = 0,
    override var bytesDown: Long = 0,
) : PortForward {
    var closeCount = 0
        private set
    override var isPaused: Boolean = false
        private set
    override val isActive: Boolean get() = closeCount == 0
    override suspend fun pause() { isPaused = true }
    override suspend fun resume() { isPaused = false }
    override suspend fun close() { closeCount++ }
}
