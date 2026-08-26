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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.TestTimeSource
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

        /** Runs after the handshake, before the host block — the window a re-share can land in. */
        var afterHandshake: () -> Unit = {}

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
            afterHandshake()
            block(channel)
        }

        override suspend fun joinShare(
            session: SyncSession,
            teamId: String,
            shareId: String,
            block: suspend (ShareChannel) -> Unit,
        ) = block(channel)
    }

    /**
     * [stallOnClose] is a relay that stops reading: the goodbye never completes, so the share's own
     * coroutine is never cancelled and its socket stays parked in the read loop after the host
     * believes the session ended.
     */
    private class FakeChannel(private val stallOnClose: Boolean = false) : ShareChannel {
        val sent = Channel<ByteArray>(Channel.UNLIMITED)
        val events = Channel<ShareEvent>(Channel.UNLIMITED)
        var closed = false

        override suspend fun send(frame: ByteArray) { sent.send(frame) }
        override suspend fun receive(): ShareEvent? = events.receiveCatching().getOrNull()
        override suspend fun close() {
            if (stallOnClose) awaitCancellation()
            closed = true
            events.close()
            sent.close()
        }
    }

    private fun TestScope.controller(
        client: SessionShareClient?,
        key: DataKey? = teamKey,
        controlGate: ControlRequestGate = ControlRequestGate(),
    ): Pair<SessionShareController, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return SessionShareController(
            liveLink = { client?.let { ShareLink(syncSession, it) } },
            teamKey = { key },
            crypto = crypto,
            newShareId = { "share-1" },
            scope = scope,
            controlGate = controlGate,
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

    /**
     * Issue #343: nothing limited how often a viewer could ask. The host denies, the Grant/Deny row
     * goes away, and the next frame puts it straight back — from a member sending them in a loop, or
     * from the relay replaying one captured ciphertext, which re-authenticates under the team key
     * exactly as the original did. A decision the host has already made must stay made for a while.
     */
    @Test
    fun `a denied request is not raised again until the refusal window passes`() = cryptoTest {
        val client = FakeShareClient()
        val time = TestTimeSource()
        val (controller, scope) = controller(client, controlGate = ControlRequestGate(time))
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        suspend fun ask(seq: Long) {
            client.channel.events.send(
                ShareEvent.Data(
                    codec.seal(teamKey, ShareFrame.ControlRequest(seq), ShareDirection.GUEST_TO_HOST),
                    from = "mate@x.io",
                ),
            )
            advanceUntilIdle()
        }

        ask(1)
        assertTrue(assertIs<ShareUiState.Live>(controller.state).controlRequestPending)
        controller.answerControlRequest(grant = false)
        advanceUntilIdle()

        ask(2)
        assertEquals(
            false,
            assertIs<ShareUiState.Live>(controller.state).controlRequestPending,
            "the denied prompt came straight back",
        )

        time += CONTROL_REASK_WINDOW
        ask(3)
        assertTrue(
            assertIs<ShareUiState.Live>(controller.state).controlRequestPending,
            "the window has passed; asking again is allowed",
        )
        scope.cancel()
    }

    /**
     * While the row is up the host is deciding. A second frame — a colleague's, or the same viewer
     * hammering the button — must not rewrite the name the host is looking at.
     */
    @Test
    fun `a request that arrives while one is pending does not replace it`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        listOf("mate@x.io", "mallory@x.io").forEachIndexed { i, account ->
            client.channel.events.send(
                ShareEvent.Data(
                    codec.seal(teamKey, ShareFrame.ControlRequest(i + 1L), ShareDirection.GUEST_TO_HOST),
                    from = account,
                ),
            )
            advanceUntilIdle()
        }

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertTrue(live.controlRequestPending)
        assertEquals("mate@x.io", live.controlRequestBy, "the pending question was overwritten under the host")
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

    /**
     * A session locked read-only has no answer the host could give, so the question is not put over
     * the shell at all — and the gate's one slot is not spent holding a request nothing can resolve
     * for the rest of the share (#343).
     */
    @Test
    fun `a read-only session never raises the request row`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "root@prod", source(MutableSharedFlow()), readOnlyOnly = true)
        advanceUntilIdle()

        client.channel.events.send(
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.ControlRequest(5), ShareDirection.GUEST_TO_HOST),
                from = "mate@x.io",
            ),
        )
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertEquals(false, live.controlRequestPending, "a locked session cannot answer this question")
        assertEquals(null, live.controlRequestBy)
        scope.cancel()
    }

    /**
     * The toggle is the same decision as the Deny button, reached another way: the row goes, and the
     * asker is held off exactly as a denial holds them. Without it the host turns input off and the
     * next frame puts the same request straight back.
     */
    @Test
    fun `turning input off answers the request on screen`() = cryptoTest {
        val client = FakeShareClient()
        val time = TestTimeSource()
        val (controller, scope) = controller(client, controlGate = ControlRequestGate(time))
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        suspend fun ask(seq: Long) {
            client.channel.events.send(
                ShareEvent.Data(
                    codec.seal(teamKey, ShareFrame.ControlRequest(seq), ShareDirection.GUEST_TO_HOST),
                    from = "mate@x.io",
                ),
            )
            advanceUntilIdle()
        }

        ask(1)
        assertTrue(assertIs<ShareUiState.Live>(controller.state).controlRequestPending)

        controller.setInputAllowed(false)
        advanceUntilIdle()
        assertEquals(
            false,
            assertIs<ShareUiState.Live>(controller.state).controlRequestPending,
            "the row stayed over the shell after the host had answered it",
        )

        time += CONTROL_PROMPT_FLOOR
        ask(2)
        assertEquals(
            false,
            assertIs<ShareUiState.Live>(controller.state).controlRequestPending,
            "the toggle's answer did not hold the asker off",
        )
        scope.cancel()
    }

    /**
     * A share the host ended is not always a socket that is gone: a relay that stops reading leaves
     * the goodbye frame suspended, so nothing cancels the old read loop. Its viewers must reach
     * nothing at all — not the shell of the share that replaced it, not its panel, not its viewer
     * list — however long the relay keeps them parked there.
     */
    @Test
    fun `a share the host ended cannot reach the one that replaced it`() = cryptoTest {
        val stalled = FakeShareClient(FakeChannel(stallOnClose = true))
        val fresh = FakeShareClient()
        var current: SessionShareClient = stalled
        val typedIntoTheOldShell = mutableListOf<ByteArray>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = SessionShareController(
            liveLink = { ShareLink(syncSession, current) },
            teamKey = { teamKey },
            crypto = crypto,
            newShareId = { "share-1" },
            scope = scope,
        )
        val codec = SessionShareCodec(crypto, "share-1")

        controller.share(
            "t1", "Platform", "pane-1", "left-behind",
            source(MutableSharedFlow(), typedIntoTheOldShell), readOnlyOnly = false,
        )
        advanceUntilIdle()

        // The host stops, the goodbye hangs, and they start sharing a different pane.
        current = fresh
        controller.share("t2", "Other", "pane-2", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()
        controller.setInputAllowed(true) // the new share's viewers may type; the old one's may not
        advanceUntilIdle()

        listOf(
            ShareEvent.Viewers(3, listOf("alice@team-a")),
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.ControlRequest(1), ShareDirection.GUEST_TO_HOST),
                from = "alice@team-a",
            ),
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.Input("rm -rf /\n".encodeToByteArray(), sender = 7, seq = 1), ShareDirection.GUEST_TO_HOST),
                from = "alice@team-a",
            ),
        ).forEach { stalled.channel.events.send(it) }
        advanceUntilIdle()

        assertTrue(typedIntoTheOldShell.isEmpty(), "a viewer typed into a shell the host stopped sharing")
        val live = assertIs<ShareUiState.Live>(controller.state)
        assertEquals("t2", live.teamId)
        assertEquals(0, live.viewers, "the old share's viewers were counted as this one's")
        assertEquals(false, live.controlRequestPending, "the old share's question was raised over this one")
        scope.cancel()
    }

    /**
     * The Grant/Deny row lives in the share popup, which the host may not have open. A question from
     * a viewer who has since stopped watching must not still be there when they do open it: granting
     * it lets whoever is watching *now* type, under the name of a colleague who left.
     */
    @Test
    fun `a request from a viewer who stopped watching is taken off the screen`() = cryptoTest {
        val client = FakeShareClient()
        val clock = TestTimeSource()
        val (controller, scope) = controller(client, controlGate = ControlRequestGate(clock))
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        client.channel.events.send(ShareEvent.Viewers(1, listOf("mate@x.io")))
        client.channel.events.send(
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.ControlRequest(5), ShareDirection.GUEST_TO_HOST),
                from = "mate@x.io",
            ),
        )
        advanceUntilIdle()
        assertTrue(assertIs<ShareUiState.Live>(controller.state).controlRequestPending)

        client.channel.events.send(ShareEvent.Viewers(0, emptyList()))
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertEquals(false, live.controlRequestPending, "the asker left; the row stayed over the shell")
        assertEquals(null, live.controlRequestBy)

        // Taking the row off the screen is only half of it: the gate's one slot has to come back
        // too, or the next colleague's question is dropped in silence until the first ages out.
        clock += CONTROL_PROMPT_FLOOR
        client.channel.events.send(ShareEvent.Viewers(1, listOf("alice@x.io")))
        client.channel.events.send(
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.ControlRequest(6), ShareDirection.GUEST_TO_HOST),
                from = "alice@x.io",
            ),
        )
        advanceUntilIdle()
        val next = assertIs<ShareUiState.Live>(controller.state)
        assertEquals("alice@x.io", next.controlRequestBy, "the slot stayed held by a viewer who left")
        scope.cancel()
    }

    /**
     * A relay that stops reading never lets the goodbye through. Without a bound on it the share's
     * coroutine, its socket and its subscription to the pane's output are parked for as long as the
     * app runs — one set per share the user stops, at the relay's discretion.
     */
    @Test
    fun `a relay that never takes the goodbye still lets the share go`() = cryptoTest {
        val client = FakeShareClient(FakeChannel(stallOnClose = true))
        val output = MutableSharedFlow<ByteArray>()
        val (controller, scope) = controller(client)
        controller.share("t1", "Platform", "pane-1", "host", source(output), readOnlyOnly = false)
        advanceUntilIdle()
        assertEquals(1, output.subscriptionCount.value, "the share never started streaming the pane")

        controller.stop()
        advanceTimeBy(GOODBYE_TIMEOUT_MS + 1)
        advanceUntilIdle()

        assertEquals(0, output.subscriptionCount.value, "a wedged relay parked the share on the pane forever")
        scope.cancel()
    }

    /**
     * `stop()` only *requests* cancellation, so a share the host ended can still be inside the
     * non-suspending stretch after the relay handshake. Publishing there puts the old team's name
     * and share id over the one the user actually started.
     */
    @Test
    fun `a share stopped before its socket opened publishes nothing`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        client.afterHandshake = { controller.stop() }
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()

        assertIs<ShareUiState.Off>(controller.state, "a share the host had already stopped drew itself live")
        assertNull(controller.sharedPaneId, "a stopped share marked a pane as the one being shared")
        scope.cancel()
    }

    /**
     * Nothing to ask once input is allowed: the viewer may already type. Without this the host
     * grants, which clears the refusals, and a looping request re-raises the row over a session it
     * has already been let into — one prompt per frame, for as long as the host keeps answering.
     */
    @Test
    fun `a request while input is already allowed is dropped`() = cryptoTest {
        val client = FakeShareClient()
        val (controller, scope) = controller(client)
        val codec = SessionShareCodec(crypto, "share-1")
        controller.share("t1", "Platform", "pane-1", "host", source(MutableSharedFlow()), readOnlyOnly = false)
        advanceUntilIdle()
        controller.setInputAllowed(true)
        advanceUntilIdle()

        client.channel.events.send(
            ShareEvent.Data(
                codec.seal(teamKey, ShareFrame.ControlRequest(5), ShareDirection.GUEST_TO_HOST),
                from = "mallory@x.io",
            ),
        )
        advanceUntilIdle()

        val live = assertIs<ShareUiState.Live>(controller.state)
        assertTrue(live.inputAllowed)
        assertEquals(false, live.controlRequestPending, "asked for what the viewer already has")
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
