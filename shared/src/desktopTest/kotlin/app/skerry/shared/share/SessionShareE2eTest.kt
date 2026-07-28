package app.skerry.shared.share

import app.skerry.server.config.ServerConfig
import app.skerry.server.module
import app.skerry.shared.sync.DeviceInfo
import app.skerry.shared.sync.KtorSyncClient
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.team.TeamRole
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Session sharing end to end: a real embedded server relaying between a real host and a real
 * viewer, with the frames sealed under a team key the server never sees.
 *
 * What only this level can prove: that a sealed frame actually fits the relay's WebSocket frame cap,
 * that the catch-up buffer reaches a viewer who joins after output was already produced, and that
 * the server relays without ever being able to read what it carries.
 */
class SessionShareE2eTest {

    private val crypto = IonspinVaultCrypto()
    private val teamId = "team-e2e"
    private val shareId = "share-e2e"
    private val codec by lazy { SessionShareCodec(crypto, shareId) }

    @Test
    fun `a viewer watches the host's terminal and types back into it`() = runBlocking {
        initializeVaultCrypto()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-share-e2e-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = embeddedServer(Netty, port = port) { module(config) }.start(wait = false)
        val sync = KtorSyncClient("http://localhost:$port")
        val shares = KtorSessionShareClient("http://localhost:$port", KtorSyncClient.defaultHttpClient())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val teamKey = crypto.newDataKey()
            val (hostSession, guestSession) = setUpTeam(sync)

            // --- host: shares a terminal whose output is a plain flow ---
            val output = MutableSharedFlow<ByteArray>()
            val typedIntoShell = Channel<ByteArray>(Channel.UNLIMITED)
            val hostReady = CompletableDeferred<SessionShareHost>()
            val viewerCounts = Channel<List<String>>(Channel.UNLIMITED)
            val hostJob = scope.launch {
                shares.hostShare(hostSession, teamId, shareId, meta = sealedMeta(teamKey)) { channel ->
                    val host = SessionShareHost(
                        codec = codec,
                        teamKey = teamKey,
                        channel = channel,
                        output = output,
                        toShell = { typedIntoShell.send(it) },
                        geometry = { ShareFrame.Resize(120, 40) },
                        allowInput = { true },
                        onViewers = { viewerCounts.trySend(it) },
                    )
                    hostReady.complete(host)
                    host.run()
                }
            }
            val host = withTimeout(TIMEOUT) { hostReady.await() }

            // The directory the team sees, with the label only members can open.
            val listed = withTimeout(TIMEOUT) {
                var entries = shares.listShares(guestSession, teamId)
                while (entries.isEmpty()) entries = shares.listShares(guestSession, teamId)
                entries
            }
            assertEquals(shareId, listed.single().shareId)
            assertContentEquals(
                "root@prod-web".encodeToByteArray(),
                crypto.open(teamKey, listed.single().meta, shareMetaAad(shareId)),
            )

            // Output produced before anyone joined: the relay's catch-up buffer must carry it over.
            output.subscriptionCount.first { it > 0 }
            output.emit("before-join".encodeToByteArray())
            // A write larger than one relay frame, to prove chunking against the real frame cap.
            val bulk = ByteArray(SHARE_MAX_CHUNK_BYTES * 2 + 5) { 0x62 }
            output.emit(bulk)

            // --- viewer: joins and reads the same terminal ---
            val seen = Channel<ByteArray>(Channel.UNLIMITED)
            val viewerReady = CompletableDeferred<SharedSessionViewer>()
            val guestJob = scope.launch {
                shares.joinShare(guestSession, teamId, shareId) { channel ->
                    val viewer = SharedSessionViewer(codec, teamKey, channel, this)
                    viewerReady.complete(viewer)
                    viewer.output.collect { seen.send(it) }
                }
            }
            val viewer = withTimeout(TIMEOUT) { viewerReady.await() }

            val caughtUp = withTimeout(TIMEOUT) { collect(seen, "before-join".length + bulk.size) }
            assertContentEquals("before-join".encodeToByteArray() + bulk, caughtUp)

            // The host announces its grid to the viewer that just joined.
            assertEquals(ShareFrame.Resize(120, 40), withTimeout(TIMEOUT) { viewer.geometry.first { it != null } })
            assertEquals(listOf("mate@x.io"), withTimeout(TIMEOUT) { viewerCounts.receive() })

            // Live output after the join.
            output.emit("live".encodeToByteArray())
            assertContentEquals("live".encodeToByteArray(), withTimeout(TIMEOUT) { collect(seen, 4) })

            // The viewer types; the host applies it because this share allows input.
            viewer.send("uptime\n".encodeToByteArray())
            assertContentEquals("uptime\n".encodeToByteArray(), withTimeout(TIMEOUT) { typedIntoShell.receive() })

            // Stopping the share ends the viewer's session rather than freezing its screen.
            host.stop()
            assertEquals(
                app.skerry.shared.terminal.TerminalState.Closed(cleanExit = true),
                withTimeout(TIMEOUT) { viewer.state.first { it is app.skerry.shared.terminal.TerminalState.Closed } },
            )
            withTimeout(TIMEOUT) { hostJob.join() }
            guestJob.cancel()

            // The share is gone from the directory once its host let go of the socket.
            withTimeout(TIMEOUT) { while (shares.listShares(guestSession, teamId).isNotEmpty()) kotlinx.coroutines.yield() }
        } finally {
            scope.cancel()
            sync.close()
            server.stop(0, 0)
        }
    }

    @Test
    fun `a member of another team cannot join the share`() = runBlocking {
        initializeVaultCrypto()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = Files.createTempFile("skerry-share-e2e-acl-", ".db")
        val config = ServerConfig.fromEnv(
            mapOf(
                "SKERRY_DB_URL" to "jdbc:sqlite:${dbFile.toAbsolutePath()}",
                "SKERRY_JWT_SECRET" to "e2e-test-secret-not-default",
                "SKERRY_PORT" to "$port",
            ),
        )
        val server = embeddedServer(Netty, port = port) { module(config) }.start(wait = false)
        val sync = KtorSyncClient("http://localhost:$port")
        val shares = KtorSessionShareClient("http://localhost:$port", KtorSyncClient.defaultHttpClient())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val teamKey = crypto.newDataKey()
            val (hostSession, _) = setUpTeam(sync)
            val outsider = sync.register("out@x.io", "out-key".encodeToByteArray(), byteArrayOf(0), DeviceInfo("d-out", "Out"))

            val opened = CompletableDeferred<Unit>()
            scope.launch {
                shares.hostShare(hostSession, teamId, shareId, meta = sealedMeta(teamKey)) { channel ->
                    opened.complete(Unit)
                    SessionShareHost(
                        codec, teamKey, channel, MutableSharedFlow(), {},
                        { ShareFrame.Resize(80, 24) }, { false }, {},
                    ).run()
                }
            }
            withTimeout(TIMEOUT) { opened.await() }

            // Not a member: the directory is a 404, and the socket is closed before any frame.
            val listing = runCatching { shares.listShares(outsider, teamId) }
            assertTrue(listing.isFailure, "an outsider read the team's share directory")

            val received = CompletableDeferred<ShareEvent?>()
            withTimeout(TIMEOUT) {
                runCatching {
                    shares.joinShare(outsider, teamId, shareId) { channel -> received.complete(channel.receive()) }
                }
            }
            assertEquals(null, received.getCompleted(), "an outsider received a frame of the shared session")
        } finally {
            scope.cancel()
            sync.close()
            server.stop(0, 0)
        }
    }

    /** Owner + one accepted member; returns their sessions. */
    private suspend fun setUpTeam(sync: KtorSyncClient): Pair<SyncSession, SyncSession> {
        val host = sync.register("owner@x.io", "owner-key".encodeToByteArray(), byteArrayOf(0), DeviceInfo("d-owner", "Laptop"))
        val guest = sync.register("mate@x.io", "mate-key".encodeToByteArray(), byteArrayOf(0), DeviceInfo("d-mate", "Phone"))
        sync.createTeam(host, teamId)
        sync.invite(host, teamId, "mate@x.io", TeamRole.EDITOR, byteArrayOf(1, 2, 3))
        sync.accept(guest, teamId)
        return host to guest
    }

    /** The session label, sealed like every other frame so the server never learns the host name. */
    private fun sealedMeta(teamKey: DataKey): ByteArray =
        crypto.seal(teamKey, "root@prod-web".encodeToByteArray(), shareMetaAad(shareId))

    /** Reads until [bytes] bytes of terminal output have arrived (chunk boundaries are arbitrary). */
    private suspend fun collect(channel: Channel<ByteArray>, bytes: Int): ByteArray {
        var acc = ByteArray(0)
        while (acc.size < bytes) acc += channel.receive()
        return acc
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
