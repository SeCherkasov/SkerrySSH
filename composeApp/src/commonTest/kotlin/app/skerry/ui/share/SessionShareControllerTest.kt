package app.skerry.ui.share

import app.skerry.shared.share.SessionShareClient
import app.skerry.shared.share.SessionShareCodec
import app.skerry.shared.share.ShareChannel
import app.skerry.shared.share.ShareDirection
import app.skerry.shared.share.ShareEvent
import app.skerry.shared.share.ShareFrame
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.share.SharedSessionInfo
import app.skerry.shared.share.shareMetaAad
import app.skerry.shared.sync.SyncException
import app.skerry.shared.sync.SyncSession
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.IonspinVaultCrypto
import app.skerry.shared.vault.VaultCrypto
import app.skerry.shared.vault.initializeVaultCrypto
import app.skerry.ui.sync.ShareLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionShareControllerTest {

    private val crypto: VaultCrypto = IonspinVaultCrypto()
    private val teamKey: DataKey by lazy { crypto.newDataKey() }
    private val syncSession = SyncSession("me@x.io", "access", "refresh")

    private fun cryptoTest(block: suspend TestScope.() -> Unit): TestResult = runTest {
        initializeVaultCrypto()
        block()
    }

    /** Records what was hosted and hands the host end a channel the test drives. */
    private class FakeShareClient(
        val channel: FakeChannel = FakeChannel(),
        val failWith: SyncException? = null,
    ) : SessionShareClient {
        var hostedShareId: String? = null
        var hostedMeta: ByteArray? = null

        override suspend fun listShares(session: SyncSession, teamId: String): List<SharedSessionInfo> = emptyList()

        override suspend fun hostShare(
            session: SyncSession,
            teamId: String,
            shareId: String,
            meta: ByteArray,
            block: suspend (ShareChannel) -> Unit,
        ) {
            failWith?.let { throw it }
            hostedShareId = shareId
            hostedMeta = meta
            block(channel)
        }

        override suspend fun joinShare(
            session: SyncSession,
            teamId: String,
            shareId: String,
            block: suspend (ShareChannel) -> Unit,
        ) = block(channel)
    }

    private class FakeChannel : ShareChannel {
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        val events = Channel<ShareEvent>(Channel.UNLIMITED)
        var closed = false

        override suspend fun send(frame: ByteArray) { sent.send(frame) }
        override suspend fun receive(): ShareEvent? = events.receiveCatching().getOrNull()
        override suspend fun close() {
            closed = true
            events.close()
            sent.close()
        }
    }

    private fun TestScope.controller(client: SessionShareClient?, key: DataKey? = teamKey): Pair<SessionShareController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return SessionShareController(
            liveLink = { client?.let { ShareLink(syncSession, it) } },
            teamKey = { key },
            crypto = crypto,
            newShareId = { "share-1" },
            scope = scope,
        ) to scope
    }

    private fun source(
        output: MutableSharedFlow<ByteArray>,
        typed: MutableList<ByteArray> = mutableListOf(),
        sessionState: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open),
    ) = ShareSource(
        output = output,
        toShell = { typed += it },
        geometry = { ShareFrame.Resize(90, 30) },
        sessionState = sessionState,
    )

    @Test
    fun `the shell ending stops the broadcast`() = cryptoTest {
        val client = FakeShareClient()
        val shell = MutableStateFlow<TerminalState>(TerminalState.Open)
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "root@prod-web", source(MutableSharedFlow(), sessionState = shell), readOnlyOnly = false)
        advanceUntilIdle()
        assertIs<ShareUiState.Live>(controller.state)

        shell.value = TerminalState.Closed(cleanExit = true)
        advanceUntilIdle()

        // Otherwise the team keeps watching the last frame of a session that no longer exists, and
        // the relay socket stays open until the app is closed.
        assertEquals(ShareUiState.Off, controller.state)
        assertNull(controller.sharedPaneId)
        scope.cancel()
    }

    @Test
    fun `sharing goes live and seals the session label with the team key`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)

        controller.share("t1", "Platform", "pane-1", "root@prod-web", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertEquals("share-1", live.shareId)
        assertEquals("Platform", live.teamName)
        assertEquals("pane-1", controller.sharedPaneId)
        assertContentEquals(
            "root@prod-web".encodeToByteArray(),
            crypto.open(teamKey, client.hostedMeta!!, shareMetaAad("share-1")),
        )
        scope.cancel()
    }

    @Test
    fun `a production session can be watched but never typed into`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "root@prod", source(MutableSharedFlow()), readOnlyOnly = true)
        advanceUntilIdle()

        controller.setInputAllowed(true)
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertTrue(live.inputLocked)
        assertEquals(false, live.inputAllowed, "the guard's confirmation must not be bypassable by a viewer")
        scope.cancel()
    }

    @Test
    fun `viewer keystrokes reach the shell only after the host allows input`() = cryptoTest {
        val client = FakeShareClient()
        val typed = mutableListOf<ByteArray>()
        val (controller, scope) = controller(client)
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow(), typed), readOnlyOnly = false)
        advanceUntilIdle()

        client.channel.events.send(
            ShareEvent.Data(codec.seal(teamKey, ShareFrame.Input("no\n".encodeToByteArray(), 1, 1), ShareDirection.GUEST_TO_HOST)),
        )
        advanceUntilIdle()
        assertTrue(typed.isEmpty(), "a share starts read-only")

        controller.setInputAllowed(true)
        client.channel.events.send(
            ShareEvent.Data(codec.seal(teamKey, ShareFrame.Input("yes\n".encodeToByteArray(), 1, 2), ShareDirection.GUEST_TO_HOST)),
        )
        advanceUntilIdle()

        assertEquals(1, typed.size)
        assertContentEquals("yes\n".encodeToByteArray(), typed.single())
        scope.cancel()
    }

    @Test
    fun `terminal output is streamed while the share is live`() = cryptoTest {
        val client = FakeShareClient()
        val output = MutableSharedFlow<ByteArray>()
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "host", source(output), readOnlyOnly = false)
        advanceUntilIdle()

        output.emit("printed".encodeToByteArray())
        advanceUntilIdle()

        val frame = SessionShareCodec(crypto, "share-1")
            .open(teamKey, client.channel.sent.receive(), ShareDirection.HOST_TO_GUEST)
        assertIs<ShareFrame.Output>(frame)
        assertContentEquals("printed".encodeToByteArray(), frame.bytes)
        scope.cancel()
    }

    @Test
    fun `a viewer's request for control is answered by the host, not granted on its own`() = cryptoTest {
        val client = FakeShareClient()
        val typed = mutableListOf<ByteArray>()
        val (controller, scope) = controller(client)
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow(), typed), readOnlyOnly = false)
        advanceUntilIdle()

        // The name on the prompt is the relay's, read off the JWT of the socket the frame arrived
        // on — never anything the frame itself claims (#312).
        client.channel.events.send(ShareEvent.Viewers(1, listOf("mate@x.io")))
        client.channel.events.send(
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.ControlRequest(5), ShareDirection.GUEST_TO_HOST),
                from = "mate@x.io",
            ),
        )
        advanceUntilIdle()
        assertEquals("mate@x.io", assertIs<ShareUiState.Live>(controller.state).controlRequestBy)
        assertTrue(assertIs<ShareUiState.Live>(controller.state).controlRequestPending)
        assertEquals(false, assertIs<ShareUiState.Live>(controller.state).inputAllowed, "asking must not grant")

        controller.answerControlRequest(grant = true)
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertTrue(live.inputAllowed)
        assertEquals(null, live.controlRequestBy)
        assertEquals(false, live.controlRequestPending)
        scope.cancel()
    }

    /**
     * A relay older than the naming protocol sends no account with the frame. The prompt still has
     * to appear — asking is the viewer's only route to being allowed to type, and their own button
     * says "Requested" either way — so the panel shows a request that names nobody.
     */
    @Test
    fun `a request the relay does not name is still shown, without a name`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        client.channel.events.send(
            ShareEvent.Data(codec.seal(teamKey, ShareFrame.ControlRequest(5), ShareDirection.GUEST_TO_HOST), from = null),
        )
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertTrue(live.controlRequestPending, "an unnamed request never reached the panel")
        assertEquals(null, live.controlRequestBy)
        scope.cancel()
    }

    @Test
    fun `a production session refuses a control request outright`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "root@prod", source(MutableSharedFlow()), readOnlyOnly = true)
        advanceUntilIdle()

        controller.answerControlRequest(grant = true)
        advanceUntilIdle()

        assertEquals(false, assertIs<ShareUiState.Live>(controller.state).inputAllowed)
        scope.cancel()
    }

    @Test
    fun `stopping tells the viewers and returns the button to idle`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        controller.stop()
        advanceUntilIdle()

        assertEquals(ShareUiState.Off, controller.state)
        assertEquals(null, controller.sharedPaneId)
        assertTrue(client.channel.closed, "the relay socket stayed open after stopping")
        scope.cancel()
    }

    @Test
    fun `a refused relay reports a typed failure instead of a silent no-op`() = cryptoTest {
        val client = FakeShareClient(failWith = SyncException(SyncException.Kind.FORBIDDEN, "not a member"))
        val (controller, scope) = controller(client)

        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        assertEquals(ShareUiState.Failed(ShareFailure.Rejected), controller.state)
        assertEquals(null, controller.sharedPaneId)
        scope.cancel()
    }

    @Test
    fun `sharing without a team key never opens a socket`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client, key = null)

        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        assertEquals(ShareUiState.Failed(ShareFailure.NoTeamKey), controller.state)
        assertEquals(null, client.hostedShareId, "a share was announced with no key to seal it with")
        scope.cancel()
    }

    @Test
    fun `with no sync account there is nothing to share through`() = cryptoTest {
        val (controller, scope) = controller(client = null)

        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        assertEquals(ShareUiState.Failed(ShareFailure.NotConnected), controller.state)
        scope.cancel()
    }

    @Test
    fun `who is watching is shown, not just how many`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        client.channel.events.send(ShareEvent.Viewers(3, listOf("a@x.io", "b@x.io", "c@x.io")))
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertEquals(3, live.viewers)
        assertEquals(listOf("a@x.io", "b@x.io", "c@x.io"), live.viewerAccounts, "the avatars need the accounts")
        scope.cancel()
    }
}
