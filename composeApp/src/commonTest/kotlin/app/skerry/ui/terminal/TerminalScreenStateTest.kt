package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermSnapshotRow
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.wrapsToNextRow
import app.skerry.shared.terminal.TerminalSearchError
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.TerminalStepMark
import app.skerry.shared.terminal.STEP_MARK_OSC
import app.skerry.ui.session.paneSyncTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalScreenStateTest {

    @Test
    fun `output accumulates decoded session output`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("ab".encodeToByteArray())
        session.emit("cd".encodeToByteArray())

        assertEquals("abcd", state.output)
        scope.cancel()
    }

    @Test
    fun `output decodes utf-8 split across chunks`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // "П" (U+041F) in UTF-8 = 0xD0 0x9F, split across two chunks.
        session.emit(byteArrayOf(0xD0.toByte()))
        session.emit(byteArrayOf(0x9F.toByte()))

        assertEquals("П", state.output)
        scope.cancel()
    }

    // --- PTY backpressure ---

    @Test
    fun `pty backlog is bounded - the producer suspends until the emulator catches up`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val total = FEED_BACKLOG_CHUNKS * 4
        var emitted = 0
        var maxInFlight = 0
        // Rebound after construction: the flow body runs at collection time, when `state` exists.
        var applied: () -> Long = { 0L }
        val session = object : TerminalSession {
            override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
            override val output: Flow<ByteArray> = flow {
                repeat(total) {
                    emit(byteArrayOf('x'.code.toByte()))
                    emitted++
                    maxInFlight = maxOf(maxInFlight, emitted - applied().toInt())
                }
            }
            override suspend fun send(data: ByteArray) = Unit
            override suspend fun resize(size: PtySize) = Unit
            override suspend fun close() = Unit
        }
        val state = TerminalScreenState(session, scope)
        applied = { state.outputVersion }
        testScheduler.advanceUntilIdle()

        assertEquals(total, emitted)
        // The drain is asserted on what the emulator parsed, not on the producer's own counter:
        // a Feed dropped after its permit was taken would leave emitted == total regardless.
        assertEquals(total.toLong(), state.outputVersion)
        assertTrue(
            maxInFlight <= FEED_BACKLOG_CHUNKS + 1,
            "unapplied backlog reached $maxInFlight chunks; cap is $FEED_BACKLOG_CHUNKS",
        )
        scope.cancel()
    }

    @Test
    fun `control command lands behind a saturated feed backlog`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val total = FEED_BACKLOG_CHUNKS * 4
        val gate = CompletableDeferred<Unit>()
        val session = FloodingTerminalSession(total, resizeGate = gate)
        val state = TerminalScreenState(session, scope)

        // Park the owner inside the first resize's PTY call; the collector then floods the queue
        // to the permit cap and suspends on acquire. Only now is the pipeline genuinely saturated.
        state.resize(PtySize(cols = 90, rows = 30))
        testScheduler.runCurrent()
        assertEquals(FEED_BACKLOG_CHUNKS, session.emitted)

        // Queued behind a full backlog of unparsed Feeds: must still be applied, exactly once.
        state.resize(PtySize(cols = 100, rows = 40))
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(90 to 30, 100 to 40), session.resizes.map { it.cols to it.rows })
        assertEquals(total.toLong(), state.outputVersion)
        scope.cancel()
    }

    @Test
    fun `cancellation with a saturated backlog does not hang teardown`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val session = FloodingTerminalSession(chunks = FEED_BACKLOG_CHUNKS * 4, resizeGate = gate)
        val state = TerminalScreenState(session, scope)

        // Same parked-owner construction as above: the collector is provably suspended on acquire
        // with a full backlog (emitted == cap) when the scope dies - tab closed mid-`cat`.
        state.resize(PtySize(cols = 90, rows = 30))
        testScheduler.runCurrent()
        assertEquals(FEED_BACKLOG_CHUNKS, session.emitted)

        scope.cancel()
        testScheduler.advanceUntilIdle()

        // Terminates (a wedged collector would hang runTest) and the producer never ran past the
        // cap: cancellation reached the acquire suspension point, not just the flow machinery.
        assertEquals(FEED_BACKLOG_CHUNKS, session.emitted)
        assertEquals(0L, state.outputVersion)
    }

    @Test
    fun `parser fault closes the session and unwedges the collector`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val total = FEED_BACKLOG_CHUNKS * 2
        val session = FloodingTerminalSession(total)
        val state = TerminalScreenState(session, scope)
        var applies = 0
        // The faulting command's own permit is intentionally lost (the throw precedes the Feed
        // branch's try/finally) - inert, the whole semaphore dies with the session right after.
        state.applyInterceptor = { if (++applies == 3) error("injected parser fault") }
        testScheduler.advanceUntilIdle()

        // The owner died on the third command: two chunks parsed, the session was closed (the
        // auto-reconnect trigger), and the collector exited via the closed queue instead of
        // wedging on acquire - the producer stopped at the cap, not at `total`.
        assertEquals(2L, state.outputVersion)
        assertTrue(session.closed)
        assertTrue(session.emitted < total)
        scope.cancel()
    }

    @Test
    fun `emulator resize fault propagates to the recovery handler`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = FloodingTerminalSession(chunks = 4)
        val state = TerminalScreenState(session, scope)
        state.emulatorResizeInterceptor = { error("injected emulator resize fault") }

        state.resize(PtySize(cols = 90, rows = 30))
        testScheduler.advanceUntilIdle()

        // The PTY resize succeeded (its failure is a locally absorbed hiccup), but the emulator
        // half is a parser-class fault: it must reach the owner-level handler and close the
        // session, not leave a silently stale grid.
        assertEquals(listOf(90 to 30), session.resizes.map { it.cols to it.rows })
        assertTrue(session.closed)
        scope.cancel()
    }

    // --- Synchronized panes: the input mirror ---

    @Test
    fun `typed input is mirrored to the other panes`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)
        val mirrored = mutableListOf<Pair<String, MirroredInput>>()
        state.inputMirror = { text, kind -> mirrored += text to kind }

        state.typeInput("ls\n")
        state.paste("echo hi")

        assertEquals(listOf("ls\n" to MirroredInput.Typed, "echo hi" to MirroredInput.Pasted), mirrored)
        scope.cancel()
    }

    @Test
    fun `mirrored input does not mirror again`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val mirrored = mutableListOf<String>()
        state.inputMirror = { text, _ -> mirrored += text }

        // How a pane receives a keystroke from a synchronized sibling: delivered, never bounced back.
        state.typeInput("ls\n", guarded = false, mirror = false)
        state.paste("echo hi", mirror = false)

        assertTrue(mirrored.isEmpty())
        assertEquals(listOf("ls\n", "echo hi"), session.sent.map { it.decodeToString() })
        scope.cancel()
    }

    /**
     * What a snippet left on the line has to be part of what the next keystroke is classified
     * against — at the moment that keystroke is classified, not whenever a queue gets around to it.
     * The dispatcher here never runs the emulator's own coroutine, which is what a pane draining a
     * backlog of output looks like from the keyboard's side.
     */
    @Test
    fun `a snippet's line is tracked before the next input is classified`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInput("rm -rf ") // a snippet leaves a half-written command on the line
        state.typeInput("/srv\r") // the user finishes it by hand

        assertEquals("rm -rf /srv", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `a command held by the production guard is mirrored only once confirmed`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        val mirrored = mutableListOf<String>()
        state.inputMirror = { text, _ -> mirrored += text }

        state.typeInput("rm -rf /srv\r")
        // Held here, so it must be held everywhere: mirroring now would run it on the other panes
        // while this one still asks.
        assertTrue(mirrored.isEmpty())

        state.confirmGuardedCommand()
        assertEquals(listOf("rm -rf /srv\r"), mirrored)
        scope.cancel()
    }

    @Test
    fun `a dismissed command is never mirrored`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        val mirrored = mutableListOf<String>()
        state.inputMirror = { text, _ -> mirrored += text }

        state.typeInput("rm -rf /srv\r")
        state.dismissGuardedCommand()

        assertTrue(mirrored.isEmpty())
        scope.cancel()
    }

    @Test
    fun `a secret is mirrored only into panes that are taking one too`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val originSession = FakeTerminalSession().apply { echoOff = true }
        val atPromptSession = FakeTerminalSession().apply { echoOff = true }
        val origin = TerminalScreenState(originSession, scope)
        val atPrompt = TerminalScreenState(atPromptSession, scope)
        val atShell = TerminalScreenState(FakeTerminalSession(), scope)

        // Origin is at a password prompt: only the pane that is at one as well may take the secret.
        // The one sitting at an ordinary shell would echo it, store it in history and then run it.
        assertEquals(listOf(atPrompt), paneSyncTargets(origin, listOf(atPrompt, atShell)))

        // Ordinary typing goes everywhere, as the toggle promises.
        originSession.echoOff = false
        assertEquals(listOf(atPrompt, atShell), paneSyncTargets(origin, listOf(atPrompt, atShell)))
        scope.cancel()
    }

    @Test
    fun `an MFA prompt reads as a secret, ordinary output does not`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // SSH never reports echo suppression (only telnet does), so the prompt line is all there is
        // to go by. Under synchronized input a miss no longer costs one host's history: the secret
        // is mirrored in cleartext into every other connected pane and then run there.
        session.emit("Verification token: ".encodeToByteArray())
        assertEquals(true, state.awaitingSecret)

        session.emit("\r\nroot@host:~# cat secrets.txt\r\n".encodeToByteArray())
        assertEquals(false, state.awaitingSecret)
        scope.cancel()
    }

    @Test
    fun `send forwards encoded input to session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.send("ls -la\n")

        assertContentEquals("ls -la\n".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `typed input and paste bump inputVersion but programmatic sends do not`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)

        val v0 = state.inputVersion
        state.send("ls\n")
        state.sendBytes(byteArrayOf(0x1b))
        assertEquals(v0, state.inputVersion)

        state.typeInput("l")
        assertEquals(v0 + 1, state.inputVersion)
        state.paste("echo hi")
        assertEquals(v0 + 2, state.inputVersion)
        scope.cancel()
    }

    @Test
    fun `sendUserInput forwards to the session and bumps inputVersion`() = runTest {
        // Keybar keys, snippet runs and AI-confirmed commands are user-initiated: they must snap a
        // scrolled-up viewport back to the live screen, which a programmatic send must not do. They
        // reach the tracked line as well, but by telling the engine what they did to it rather than
        // by being fed through it character by character as typed input is.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val v0 = state.inputVersion
        state.sendUserInput("uptime\r")

        assertContentEquals("uptime\r".encodeToByteArray(), session.sent.single())
        assertEquals(v0 + 1, state.inputVersion)
        scope.cancel()
    }

    // --- lastOutput / lastCommandBlock (for "explain this output") ---

    @Test
    fun `lastCommandBlock returns the last command and its output, not the login banner`() {
        // Regression: with nothing selected, explaining sent the whole screen, so a long MOTD banner
        // drowned out the actual last command (uptime). The block must be just that command + output.
        val screen = listOf(
            "Welcome to Ubuntu 22.04.5 LTS",
            "",
            " * Documentation:  https://help.ubuntu.com",
            "This system has been minimized by removing packages.",
            "Last login: Fri Jul 24 11:51:03 2026 from 178.205.96.77",
            "root@140722:~# uptime",
            " 12:21:14 up 129 days, 18:42,  1 user,  load average: 0.48, 0.14, 0.05",
            "root@140722:~#",
        ).joinToString("\n")

        val block = lastCommandBlock(screen)

        assertEquals(
            "root@140722:~# uptime\n 12:21:14 up 129 days, 18:42,  1 user,  load average: 0.48, 0.14, 0.05",
            block,
        )
        // The banner and unrelated history are excluded.
        assertEquals(false, block!!.contains("Documentation"))
        assertEquals(false, block.contains("Last login"))
    }

    @Test
    fun `lastCommandBlock returns null when the prompt does not repeat`() {
        // Only one prompt on screen: no earlier command line to bound the block, so fall back to the
        // whole screen at the call site rather than mis-slicing.
        assertEquals(null, lastCommandBlock("some free-form output\nwith no repeated prompt\nroot@140722:~#"))
    }

    @Test
    fun `lastCommandBlock ignores a too-short prompt that would match everything`() {
        // A bare "$" prompt would start-with-match unrelated lines; reject it.
        assertEquals(null, lastCommandBlock("total output here\n$ ls\n$"))
    }

    @Test
    fun `lastCommandBlock keeps a command that produced no output`() {
        assertEquals("root@140722:~# cd /var/log", lastCommandBlock("root@140722:~# cd /var/log\nroot@140722:~#"))
    }

    // --- lastCommandBlocks (context attached to an assistant question) ---

    @Test
    fun `lastCommandBlocks returns the most recent blocks, newest last`() {
        val screen = listOf(
            "Last login: Fri Jul 24 11:51:03 2026",
            "root@140722:~# df -h /",
            "/dev/sda1        50G   42G  6.4G  87% /",
            "root@140722:~# uptime",
            " 12:21:14 up 129 days,  load average: 0.48",
            "root@140722:~#",
        ).joinToString("\n")

        val blocks = lastCommandBlocks(screen, 2)

        assertEquals(2, blocks.size)
        assertEquals("root@140722:~# df -h /\n/dev/sda1        50G   42G  6.4G  87% /", blocks[0])
        assertEquals("root@140722:~# uptime\n 12:21:14 up 129 days,  load average: 0.48", blocks[1])
    }

    @Test
    fun `lastCommandBlocks caps at what the screen holds and never includes the banner`() {
        val screen = "Welcome to Ubuntu\nroot@140722:~# uptime\n load average: 0.48\nroot@140722:~#"

        val blocks = lastCommandBlocks(screen, 5)

        assertEquals(listOf("root@140722:~# uptime\n load average: 0.48"), blocks)
    }

    @Test
    fun `lastCommandBlocks returns nothing for a zero count or an unusable prompt`() {
        val screen = "root@140722:~# uptime\n load average: 0.48\nroot@140722:~#"

        assertEquals(emptyList(), lastCommandBlocks(screen, 0))
        assertEquals(emptyList(), lastCommandBlocks("total output here\n$ ls\n$", 2))
    }

    @Test
    fun `lastOutput reads the last command block from the live screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit(
            ("Last login: Fri Jul 24 11:51:03 2026 from 178.205.96.77\r\n" +
                "root@140722:~# uptime\r\n" +
                " 12:21:14 up 129 days, 18:42,  1 user,  load average: 0.48, 0.14, 0.05\r\n" +
                "root@140722:~#").encodeToByteArray(),
        )

        val last = state.lastOutput()
        assertEquals(true, last != null && last.contains("uptime") && last.contains("load average"))
        assertEquals(false, last!!.contains("Last login"))
        scope.cancel()
    }

    @Test
    fun `preloaded history feeds autosuggestion`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("git push origin main"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("git pu")
        session.emit("git pu".encodeToByteArray())

        assertEquals("sh origin main", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `the ghost follows the echoed text and never blinks off while typing`() = runTest {
        // The ghost is drawn at the cursor of the published snapshot, so its text has to be the
        // continuation of what that snapshot shows — not of the locally tracked line, which runs
        // ahead of the echo. Recomputing it on every keystroke made it disappear for the round trip
        // (a visible blink per character); deriving it from the screen keeps the completed command
        // standing still while the typed part grows into it.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("git push origin main"),
        )
        session.emit("$ ".encodeToByteArray())

        state.typeInput("git")
        assertEquals(null, state.suggestionTail) // nothing echoed yet, so there is no place to draw
        session.emit("git".encodeToByteArray())
        assertEquals(" push origin main", state.suggestionTail)

        // Typing ahead of the echo leaves the ghost where it is: the screen still reads "git".
        state.typeInput(" pu")
        assertEquals(" push origin main", state.suggestionTail)

        // The echo arrives and the ghost shortens by exactly the characters that became text — its
        // right edge does not move.
        session.emit(" pu".encodeToByteArray())
        assertEquals("sh origin main", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `an accepted suggestion stays on screen until its echo turns it into text`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("git push origin main", "git push origin main --force-with-lease"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("git pu")
        session.emit("git pu".encodeToByteArray())
        assertEquals("sh origin main", state.suggestionTail)

        assertTrue(state.acceptSuggestion())
        // Still anchored to what the screen shows ("git pu"), now continuing the accepted command.
        assertEquals("sh origin main --force-with-lease", state.suggestionTail)

        session.emit("sh origin main".encodeToByteArray())
        assertEquals(" --force-with-lease", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `Tab accepts a suggestion that is not drawable yet`() = runTest {
        // Nothing is echoed yet, so there is no ghost — but the completion exists. Tab and the mobile
        // chip key off [hasSuggestion], or a Tab pressed on a slow link would fall through to the
        // shell as a raw HT and Shift+Tab would reach the PTY as ESC[Z, clearing the tracked line.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("git push origin main"),
        )
        session.emit("$ ".encodeToByteArray())

        state.typeInput("git pu")
        assertEquals(null, state.suggestionTail)
        assertTrue(state.hasSuggestion)
        assertTrue(state.acceptSuggestion())
        assertEquals("sh origin main", session.sent.last().decodeToString())
        scope.cancel()
    }

    @Test
    fun `backspace does not blink the ghost either`() = runTest {
        // The erase is local first: until the shell echoes it the screen still reads "ll", and the
        // ghost that belongs to "ll" is the correct thing to keep drawing there.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("lsof -i", "ll -h"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("ll")
        session.emit("ll".encodeToByteArray())
        assertEquals(" -h", state.suggestionTail)

        state.typeInput("\b")
        assertEquals(" -h", state.suggestionTail)

        session.emit("\b \b".encodeToByteArray()) // how a shell erases a character
        assertEquals("sof -i", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `the guard holds a line run by accept-line-and-down-history`() = runTest {
        // Ctrl-O runs the current line just like Enter does; the mobile keybar reaches it as ctrl + "/".
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        state.typeInput("rm -rf /srv")

        state.typeInput("${15.toChar()}") // Ctrl-O

        assertNotNull(state.pendingGuarded)
        assertEquals(false, session.sent.any { it.decodeToString() == "${15.toChar()}" })
        // What runs is the line, and the control that runs it is not a character of it: quoting it
        // would draw a command with a `<U+000F>` in it, and report the line twice.
        assertEquals("rm -rf /srv", state.pendingGuardedQuote)
        assertNull(state.pendingGuardedAside)
        scope.cancel()
    }

    /**
     * The join of a completed prefix and an incoming block is a string neither side has: it is
     * classified, because it is the closest thing to what will run, but the dialog may not draw it.
     * What is on the line is the prefix, and that is what the caption is about.
     */
    @Test
    fun `a joined guess is classified but the dialog draws the line itself`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("root@prod:~# rm -rf /srv/prod-db".encodeToByteArray())

        "rm -rf /sr".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        state.sendUserInputGuarded("uptime\r")

        assertEquals("uptime", state.pendingGuardedQuote)
        val aside = assertNotNull(state.pendingGuardedAside)
        assertEquals(true, aside.onLine)
        assertEquals(false, aside.line.contains("uptime"), "the dialog drew a line nothing has: ${aside.line}")
        scope.cancel()
    }

    /**
     * The dangerous part arrives in the block, not on the line: `sudo r` was typed and completed,
     * and `m -rf /var` finishes it. Neither half trips the guard alone — only the two joined, which
     * is what will run.
     */
    @Test
    fun `a block that finishes a completed line is classified as the joined command`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = false)
        session.emit("root@prod:~# sudo r".encodeToByteArray())

        "sudo r".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        state.paste("m -rf /var\n")

        assertEquals("sudo rm -rf /var", state.pendingGuarded?.command)
        scope.cancel()
    }

    /**
     * And a row the host drew that does not finish what was typed is not a command anyone ran: the
     * palette draws what history stores, across every host.
     */
    @Test
    fun `a screen row unrelated to what was typed is not recorded`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })

        "systemctl restart ngi".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("Display all 3000 possibilities? (y or n)".encodeToByteArray())
        state.typeInput("\r")

        assertNull(saved, "a row the host drew was recorded as a command")
        scope.cancel()
    }

    /**
     * The row is the host's to draw, and what is drawn there is not always what it reads as. A line
     * carrying an override renders in an order the shell will not use; recorded, it would come back
     * as a suggestion and sit in the palette across every host, reading as a command it is not.
     */
    @Test
    fun `a screen row that does not draw as itself is not recorded`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })

        "echo hello".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("root@prod:~# echo hello \u202e# rm -rf /srv".encodeToByteArray())
        state.typeInput("\r")

        assertNull(saved, "a line that draws in another order was recorded as a command")
        scope.cancel()
    }

    /**
     * The block finished the line itself, so the screen's row is not what ran — it is missing what
     * this block added. Only a block that runs the line as it stands may take the screen's word for
     * what that line is. (The Android IME funnel delivers a paste and its Enter as one block.)
     */
    @Test
    fun `a block that adds to a completed line does not take the screen's word for it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })

        "rm -rf /srv/bac".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("root@prod:~# rm -rf /srv/backups/".encodeToByteArray())
        state.typeInput("old\r") // one block: what the paste added, then Enter

        assertNull(saved, "the screen's line was recorded as if the block had added nothing")
        scope.cancel()
    }

    /**
     * A character typed after a completion ends the prefix, but not the client's knowledge that
     * something is on the line. The guard still classifies it — a wrapped `rm -rf` finished by hand
     * is exactly the command a confirmation exists for.
     */
    @Test
    fun `a line typed onto after a completion is still classified`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /var/log/nginx/arch".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        state.typeInput("k") // the completion did not finish it; the line now wraps
        session.emit("ive/2024-01".encodeToByteArray()) // the row the line wrapped onto
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "a wrapped rm -rf ran with no question asked")
        scope.cancel()
    }

    /**
     * And what it holds is reported beside the quote: the quote cannot claim a line the client is
     * only guessing at, but leaving it out draws a service restart over an `rm -rf` that runs first.
     */
    @Test
    fun `a line the quote cannot claim is drawn beside it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /srv/back".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t") // completed, so what is held is a beginning of the shell's line
        state.paste("; systemctl restart nginx\n")

        assertEquals("; systemctl restart nginx", state.pendingGuardedQuote)
        assertEquals("rm -rf /srv/back", state.pendingGuardedAside?.line)
        scope.cancel()
    }

    /**
     * Once something is typed onto a completed line the two have parted company: the shell's line is
     * neither what is tracked nor a continuation of it. It is still classified — the danger is in it
     * either way — but there is nothing truthful to draw, so the dialog says what the screen says
     * rather than captioning an invention as "already on the line".
     */
    @Test
    fun `a line that diverged from the shell's is classified but not drawn as it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("root@prod:~# rm -rf /srv/backups-2019/x".encodeToByteArray())

        "rm -rf /srv/back".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        state.typeInput("x") // the shell put its own characters in the middle; this lands at the end
        state.paste("; systemctl restart nginx\n")

        assertNotNull(state.pendingGuarded)
        assertEquals(false, state.pendingGuardedAside?.line == "rm -rf /srv/backx", "a line nobody has was drawn")
        scope.cancel()
    }

    /**
     * A Tab whose completion has not echoed yet leaves the row reading exactly what was typed. That
     * is the prefix, not a command anyone ran — recording it is the thing this fallback exists to
     * stop doing.
     */
    @Test
    fun `a completion that has not echoed records nothing`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })

        "rm -rf /srv/ba".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("root@prod:~# rm -rf /srv/ba".encodeToByteArray()) // the echo of what was typed
        state.typeInput("\r")

        assertNull(saved, "a half-typed prefix was recorded as a command")
        scope.cancel()
    }

    /**
     * The line diverged, it wrapped so the screen holds only its tail, and the input is a bare
     * Enter: there is a reason and nothing truthful to show for it. The join the classifier used is
     * a string neither side has, so the dialog draws no command rather than that one.
     */
    @Test
    fun `a reason with nothing drawable behind it quotes nothing`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /var/log/nginx/arch".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        state.typeInput("k")
        session.emit("ive/2024-01k".encodeToByteArray()) // the row the wrapped line ends on
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded)
        assertEquals("", state.pendingGuardedQuote, "a line neither side has was quoted")
        assertNull(state.pendingGuardedQuoteLength)
        assertNull(state.pendingGuardedAside)
        scope.cancel()
    }

    /**
     * Inside vim the cursor row is a line of a file and the tracked line is what is being typed into
     * it. Neither is a shell line: a paste confirmed there must not be captioned as one, and what
     * the screen holds must not become a command in the host's history.
     */
    @Test
    fun `nothing on the alternate screen is taken for a shell line`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("\u001b[?1049h".encodeToByteArray())

        "rm -rf /srv".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        state.paste("docker ps\nuptime\n")

        assertNull(state.pendingGuardedAside, "a line of a file was captioned as the shell's")
        assertNull(saved)
        scope.cancel()
    }

    /**
     * Text typed after the cursor was moved to the start runs *before* what is already on the line,
     * not after it. The client cannot model that, so it must not draw the line it would get by
     * appending: what the screen holds is the only thing left that is true.
     */
    @Test
    fun `a line whose cursor moved is not quoted as an append`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("root@prod:~# sudo rm -rf /srv; deploy".encodeToByteArray())

        "deploy".forEach { state.typeInput(it.toString()) }
        state.typeInput("\u0001") // Ctrl-A
        state.typeInput("sudo rm -rf /srv; ")
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded)
        assertEquals(false, state.pendingGuardedQuote.startsWith("deploysudo"), "an appended line was quoted")
        assertEquals(false, state.pendingGuardedAside?.line?.startsWith("deploysudo") == true)
        scope.cancel()
    }

    /**
     * Saying no does not clear the line. The question came from the line itself, so forgetting it
     * would leave the next Enter over the same shell line unasked — the guard would have talked
     * itself out of its own finding. Asking again is the price; Ctrl-C and Ctrl-U are the way out.
     */
    @Test
    fun `dismissing a question does not disarm the line it was about`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /srv/data".forEach { state.typeInput(it.toString()) }
        state.typeInput("\u000B") // Ctrl-K: the shell clears the rest of the line
        state.typeInput("\r")
        assertNotNull(state.pendingGuarded)
        assertEquals("", state.pendingGuardedQuote)

        state.dismissGuardedCommand()
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "the line ran unasked after the question was dismissed")
        scope.cancel()
    }

    /**
     * The ghost belonged to the line the secret replaced. With the echo off nothing else will redraw,
     * so it would sit at the cursor of a password prompt offering a completion of a line that is gone
     * — and the typed path already clears it there.
     */
    @Test
    fun `a secret sent by a snippet takes the ghost with the line`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope, initialHistory = listOf("git push origin main"))
        session.emit("$ ".encodeToByteArray())
        state.typeInput("git pu")
        session.emit("git pu".encodeToByteArray())
        assertEquals("sh origin main", state.suggestionTail)

        session.echoOff = true // a prompt is taking a secret
        state.sendUserInput("hunter2")

        assertNull(state.suggestionTail, "a completion of the old line was left over a password prompt")
        scope.cancel()
    }

    /**
     * The completed line wrapped, so the cursor row holds only its tail and says nothing about what
     * the command is. What was typed before the Tab is still a prefix of what the shell has — the
     * shell only appended to it — so it stays a candidate to classify, and `rm -rf` is held.
     */
    @Test
    fun `a wrapped line completed by the shell is still held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /srv/bac".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("kups/2024-week12/nightly/".encodeToByteArray()) // the row the line wrapped onto
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "a completed rm -rf ran with no question asked")
        // The prefix is all there is to quote, and it is not the whole line: the dialog says so
        // without a count, because no count over a prefix would be true.
        assertEquals("rm -rf /srv/bac", state.pendingGuardedQuote)
        assertNull(state.pendingGuardedQuoteLength)
        scope.cancel()
    }

    /**
     * The completed line wrapped, so the cursor row is its tail and not a command anyone ran.
     * Recording that fragment would offer it back as a suggestion and put it in the reverse search;
     * recording nothing is what the engine does with a line it cannot follow.
     */
    @Test
    fun `a wrapped completion is not recorded as a command`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })
        state.resize(PtySize(cols = 20, rows = 6))

        "systemctl restart ngi".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("root@prod:~# systemctl restart nginx.service".encodeToByteArray()) // wraps at 20
        state.typeInput("\r")

        assertNull(saved, "a fragment of a wrapped line was recorded as a command")
        scope.cancel()
    }

    /**
     * And the line the shell finished is what goes into history — the prefix that was typed is not a
     * command anyone ran, and recording nothing at all would empty the history of a session where
     * paths are tab-completed, which is most of them.
     */
    @Test
    fun `a command the shell completed serves the session but is not persisted`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })

        "systemctl restart ngi".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("root@prod:~# systemctl restart nginx.service".encodeToByteArray())
        state.typeInput("\r")

        // The persisted snapshot carries nothing: the completed text is the host's drawing, and a
        // stored copy would surface in the cross-host palette as a command nobody typed.
        assertEquals(emptyList(), saved)
        // The session's own ghost still knows it, as the shell has it — not as the typed prefix.
        "systemctl restart ngin".forEach { state.typeInput(it.toString()) }
        assertTrue(state.hasSuggestion, "the completed command stopped serving the in-session ghost")
        scope.cancel()
    }

    /**
     * A snippet that answers a password prompt is input like any other, and the tracked line is
     * where history comes from. The typed and pasted paths already drop it; the ready-made one must
     * too, or the next Enter writes the secret to the host's stored history.
     */
    @Test
    fun `a secret sent by a snippet never reaches the tracked line`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession().apply { echoOff = true }
        var saved: List<String>? = null
        val state = TerminalScreenState(session, scope, onHistoryChanged = { saved = it })

        state.sendUserInput("hunter2")
        session.echoOff = false // the prompt is answered; the shell echoes again
        state.typeInput("\r")

        assertNull(saved, "a secret reached the host's history")
        scope.cancel()
    }

    /**
     * Inside vim or htop the cursor row is a line of a file. A snippet confirmed there must not be
     * held against it, and the dialog must not draw it beside what is being sent.
     */
    @Test
    fun `a ready-made command is not guessed against the alternate screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("\u001b[?1049h".encodeToByteArray()) // alt-screen
        session.emit("rm -rf /srv/data".encodeToByteArray()) // a line of the file being edited

        state.sendUserInputGuarded("docker ps\r")

        assertNull(state.pendingGuarded, "text the user is only looking at held a command")
        scope.cancel()
    }

    /**
     * Ctrl-O runs the line and pulls the next history entry into it, so what is on the line
     * afterwards is not in the text that ran and cannot be derived from it.
     */
    @Test
    fun `a keybar Ctrl-O leaves the line unknown rather than guessed`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "uptime".forEach { state.typeInput(it.toString()) }
        state.sendUserInput("${15.toChar()}") // a ready-made block carrying Ctrl-O
        state.typeInput("\r") // whatever the shell recalled runs on this Enter

        // Nothing local is offered as what that Enter runs: the line came from the shell's history.
        assertNull(state.pendingGuarded)
        assertNull(state.suggestionTail)
        scope.cancel()
    }

    /**
     * Tab with no local ghost goes to the shell, which completes the line on its own — the tracked
     * line is a prefix of the real one from then on, and the client cannot tell how much of one. The
     * dialog must not quote that prefix: `rm -rf /sr` under a danger reason reads as a command that
     * would delete nothing, while Enter runs `rm -rf /srv/prod-db`.
     */
    @Test
    fun `a line completed by the shell is not quoted as what was typed`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /sr".forEach { state.typeInput(it.toString()) }
        state.typeInput("\t")
        session.emit("root@prod:~# rm -rf /srv/prod-db".encodeToByteArray())
        state.typeInput("\r")

        assertEquals("rm -rf /srv/prod-db", state.pendingGuarded?.command)
        assertEquals("rm -rf /srv/prod-db", state.pendingGuardedQuote)
        scope.cancel()
    }

    @Test
    fun `Tab inside the backspace window offers nothing`() = runTest {
        // The ghost still shows the longer line's completion until the erase echoes, so accepting in
        // that window would insert a command other than the one on screen.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("less /var/log/syslog", "ll -h"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("ll")
        session.emit("ll".encodeToByteArray())
        assertEquals(" -h", state.suggestionTail)

        state.typeInput("\b")
        assertEquals(" -h", state.suggestionTail) // unchanged: the screen still reads "ll"
        assertEquals(false, state.hasSuggestion)

        session.emit("\b \b".encodeToByteArray())
        assertTrue(state.hasSuggestion)
        scope.cancel()
    }

    @Test
    fun `a paste gets its ghost when the echo lands`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("systemctl restart nginx"),
        )
        session.emit("$ ".encodeToByteArray())

        state.paste("systemctl")
        assertEquals(null, state.suggestionTail)

        session.emit("systemctl".encodeToByteArray())
        assertEquals(" restart nginx", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `the ghost shows the command Tab will insert, not the one the screen alone suggests`() = runTest {
        // Two sources of truth: the ghost continues the echoed prefix, Tab completes the tracked line.
        // Picking the candidate from the echoed prefix draws "docker ps" while Tab would insert
        // "docker logs " — the key does something other than what the user is looking at.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        session.emit("$ ".encodeToByteArray())
        state.typeInput("docker")
        session.emit("docker".encodeToByteArray())
        assertEquals(" ps", state.suggestionTail)

        state.typeInput(" l") // typed ahead of the echo
        assertEquals(" logs ", state.suggestionTail)

        assertTrue(state.acceptSuggestion())
        assertEquals("ogs ", session.sent.last().decodeToString())
        scope.cancel()
    }

    @Test
    fun `cycling while the echo lags draws the alternative that Tab will insert`() = runTest {
        // The ghost is drawn from the echoed prefix and the accepted text from the tracked line, so
        // both must name the SAME candidate. Picking it by index in two different candidate lists
        // ("back" matches three commands, "backu" only two) draws one alternative and inserts another.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("backupdb", "backupfiles", "backends"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("back")
        session.emit("back".encodeToByteArray())

        state.typeInput("u") // typed ahead of the echo: screen still reads "back"
        state.cycleSuggestion()
        state.cycleSuggestion() // past the end of the "backu" candidates — wraps to the first

        assertEquals("updb", state.suggestionTail)
        assertTrue(state.acceptSuggestion())
        assertEquals("pdb", session.sent.last().decodeToString())
        scope.cancel()
    }

    @Test
    fun `a ghost after a non-BMP character keeps its place`() = runTest {
        // The echoed prefix is found by comparing UTF-16 code units, and an emoji is a surrogate
        // pair: a probe landing between the halves must not corrupt the tail or throw.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("echo 😀 done"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("echo 😀")
        session.emit("echo 😀".encodeToByteArray())

        assertEquals(" done", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `a line the shell redrew offers nothing to accept`() = runTest {
        // Ctrl-W kills a word on the host but is a control byte the engine ignores, so its line keeps
        // the word. Neither the ghost nor Tab may act on a line that no longer exists on screen.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("git push origin main"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("git pu")
        session.emit("git pu".encodeToByteArray())
        assertTrue(state.hasSuggestion)

        state.typeInput("${23.toChar()}") // Ctrl-W
        session.emit("\r$ git ${27.toChar()}[K".encodeToByteArray()) // the shell redraws the line

        assertEquals(false, state.hasSuggestion)
        assertEquals(null, state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `a wrapped line gets no ghost`() = runTest {
        // The ghost is drawn as one unwrapped run from the cursor, so on a line that already wrapped
        // it would run off the right edge. Nothing is drawn there at all.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("uptime --pretty"),
        )
        state.resize(PtySize(cols = 6, rows = 4))
        state.typeInput("uptime -")
        session.emit("uptime -".encodeToByteArray())

        assertEquals(null, state.suggestionTail)
        // And nothing to accept either: Tab would insert a completion the user never saw.
        assertEquals(false, state.hasSuggestion)
        scope.cancel()
    }

    @Test
    fun `entering alt-screen clears a visible ghost`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("vimdiff a b"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("vim")
        session.emit("vim".encodeToByteArray())
        assertEquals("diff a b", state.suggestionTail)

        session.emit("${27.toChar()}[?1049h".encodeToByteArray())
        assertEquals(true, state.altScreen)
        assertEquals(null, state.suggestionTail)
        assertEquals(false, state.hasSuggestion)
        scope.cancel()
    }

    @Test
    fun `committed command triggers history persist callback`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val snapshots = mutableListOf<List<String>>()
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            onHistoryChanged = { snapshots += it },
        )
        state.typeInput("uptime\n")
        assertEquals(listOf("uptime"), snapshots.last())
        scope.cancel()
    }

    @Test
    fun `cycle suggestion advances the ghost tail`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(
            session, scope,
            initialHistory = listOf("backupdb", "backupfiles"),
        )
        session.emit("$ ".encodeToByteArray())
        state.typeInput("back")
        session.emit("back".encodeToByteArray())

        assertEquals("updb", state.suggestionTail)
        state.cycleSuggestion()
        assertEquals("upfiles", state.suggestionTail)
        scope.cancel()
    }

    @Test
    fun `reverse search selects a matching command and closes on accept`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("docker ps", "git status"),
        )
        state.reverseSearch.open()
        state.reverseSearch.append("git")
        assertEquals("git status", state.reverseSearch.selection)
        state.reverseSearch.accept()
        assertEquals(null, state.reverseSearch.query) // overlay closed after accepting
        scope.cancel()
    }

    @Test
    fun `delete removes selected command from history and persists`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val snapshots = mutableListOf<List<String>>()
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("gti status", "git status"),
            onHistoryChanged = { snapshots += it },
        )
        state.reverseSearch.open()
        state.reverseSearch.append("gti") // pick the typo entry
        assertEquals("gti status", state.reverseSearch.selection)
        state.reverseSearch.deleteSelected()
        assertEquals(emptyList(), state.reverseSearch.results) // "gti" is no longer found
        assertEquals(listOf("git status"), snapshots.last()) // persisted without the typo
        scope.cancel()
    }

    // --- Scrollback search (find in output) ---

    @Test
    fun `search finds matches in the buffer and selects one`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("alpha\r\nbeta\r\nalpha again\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("alpha")

        assertEquals(2, state.search.matches.size)
        assertEquals(true, state.search.currentMatch != null)
        scope.cancel()
    }

    @Test
    fun `search selects the last match at or above the anchor row`() = runTest {
        // Opening search mid-history should land on the newest match the user can see, not on the
        // oldest one at the top of the scrollback.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\nfiller\r\nhit\r\nfiller\r\nhit\r\n".encodeToByteArray())
        state.search.open(anchorRow = 2) // viewport bottom sits on the second "hit"
        state.search.updateQuery("hit")

        assertEquals(1, state.search.index)
        assertEquals(2, state.search.currentMatch?.row)
        scope.cancel()
    }

    @Test
    fun `next and previous cycle through matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\nhit\r\nhit\r\n".encodeToByteArray())
        state.search.open(anchorRow = 0)
        state.search.updateQuery("hit")

        assertEquals(0, state.search.index)
        state.search.next()
        assertEquals(1, state.search.index)
        state.search.prev()
        assertEquals(0, state.search.index)
        state.search.prev() // wraps to the last match
        assertEquals(2, state.search.index)
        state.search.next() // wraps back to the first
        assertEquals(0, state.search.index)
        scope.cancel()
    }

    @Test
    fun `case sensitivity and regex toggles re-run the search`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("Error 404\r\nerror 500\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("error")
        assertEquals(2, state.search.matches.size)

        state.search.applyCase(true)
        assertEquals(1, state.search.matches.size)

        state.search.applyCase(false)
        state.search.updateQuery("\\d{3}")
        assertEquals(0, state.search.matches.size) // still a literal search
        state.search.applyRegex(true)
        assertEquals(2, state.search.matches.size)
        scope.cancel()
    }

    @Test
    fun `an invalid regex is reported without matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("anything\r\n".encodeToByteArray())
        state.search.open()
        state.search.applyRegex(true)
        state.search.updateQuery("a(")

        assertEquals(TerminalSearchError.InvalidPattern, state.search.error)
        assertEquals(emptyList(), state.search.matches)
        assertEquals(null, state.search.currentMatch)
        scope.cancel()
    }

    @Test
    fun `new output refreshes matches while search is open`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var now = 0L
        val state = TerminalScreenState(session, scope, nowMillis = { now })

        session.emit("hit one\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        assertEquals(1, state.search.matches.size)

        now += SEARCH_REFRESH_INTERVAL_MS + 1 // past the refresh throttle window
        session.emit("hit two\r\n".encodeToByteArray())
        assertEquals(2, state.search.matches.size)
        scope.cancel()
    }

    @Test
    fun `the selected match survives new output arriving`() = runTest {
        // Output streaming in while the user is reading a hit must not silently jump the selection
        // to another match (the viewport follows the selection).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var now = 0L
        val state = TerminalScreenState(session, scope, nowMillis = { now })

        session.emit("hit one\r\nhit two\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        state.search.prev() // select the first (older) hit
        val selected = state.search.currentMatch

        now += SEARCH_REFRESH_INTERVAL_MS + 1
        session.emit("hit three\r\n".encodeToByteArray())

        assertEquals(selected, state.search.currentMatch)
        assertEquals(3, state.search.matches.size)
        scope.cancel()
    }

    @Test
    fun `the match list refresh is throttled while output streams`() = runTest {
        // A full pass over a deep scrollback costs tens of milliseconds and runs on the coroutine
        // that feeds the emulator: rebuilding the list on every published snapshot would stall the
        // terminal under streaming output. The visible highlight is computed by the render layer
        // per frame, so a slightly stale list only shows up in the counter.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var now = 0L
        val state = TerminalScreenState(session, scope, nowMillis = { now })

        session.emit("hit one\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        assertEquals(1, state.search.matches.size)

        session.emit("hit two\r\n".encodeToByteArray()) // same instant — throttled away
        assertEquals(1, state.search.matches.size)

        now += SEARCH_REFRESH_INTERVAL_MS + 1
        session.emit("hit three\r\n".encodeToByteArray())
        assertEquals(3, state.search.matches.size) // catches up on the next snapshot after the window
        scope.cancel()
    }

    @Test
    fun `stepping through matches refreshes the list first`() = runTest {
        // Navigation must not walk a stale list: the user asked for the next hit, so the pass is
        // worth running even mid-stream.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope, nowMillis = { 0L }) // clock frozen: always throttled

        session.emit("hit one\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        session.emit("hit two\r\n".encodeToByteArray())
        assertEquals(1, state.search.matches.size)

        state.search.next()

        assertEquals(2, state.search.matches.size)
        assertEquals(1, state.search.index) // moved onto the newly found hit
        scope.cancel()
    }

    @Test
    fun `the two search overlays never stay open together`() = runTest {
        // Both are driven by keys, but only one owns the keyboard: the find bar's field takes focus,
        // which strands the reverse-search overlay's key handling on the (now unfocused) terminal
        // node — a visible panel that no longer reacts to anything.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope, initialHistory = listOf("uptime"))

        session.emit("hit\r\n".encodeToByteArray())
        state.reverseSearch.open()
        state.search.open()
        assertEquals(null, state.reverseSearch.query)

        state.reverseSearch.open()
        assertEquals(null, state.search.query)
        scope.cancel()
    }

    @Test
    fun `an oversized query is capped`() = runTest {
        // A stray paste (a whole log line, a file) is not a search term; an unbounded pattern also
        // hands the regex compiler unbounded work.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)

        state.search.open()
        state.search.updateQuery("x".repeat(MAX_SEARCH_QUERY_LENGTH + 500))

        assertEquals(MAX_SEARCH_QUERY_LENGTH, state.search.query?.length)
        scope.cancel()
    }

    @Test
    fun `the viewport anchor comes from the render layer`() = runTest {
        // The scroll position lives in the composable, so it reports the row at the viewport bottom;
        // an incremental search re-selects around that, not around the live cursor.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\nfiller\r\nhit\r\nfiller\r\nhit\r\n".encodeToByteArray())
        state.search.setAnchorRow(2) // user scrolled up: second "hit" is the last visible row
        state.search.open()
        state.search.updateQuery("hit")

        assertEquals(2, state.search.currentMatch?.row)
        scope.cancel()
    }

    @Test
    fun `closing search drops the query and matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        state.search.close()

        assertEquals(null, state.search.query)
        assertEquals(emptyList(), state.search.matches)
        assertEquals(null, state.search.currentMatch)
        scope.cancel()
    }

    @Test
    fun `output is not searched while the panel is closed`() = runTest {
        // The buffer is walked on every published snapshot, so a closed panel must cost nothing.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.search.open()
        state.search.updateQuery("hit")
        state.search.close()
        session.emit("hit\r\n".encodeToByteArray())

        assertEquals(emptyList(), state.search.matches)
        scope.cancel()
    }

    @Test
    fun `reopening search keeps the previous query`() = runTest {
        // Reopening with the last query is how editors behave; the match list is rebuilt for the
        // buffer as it is now.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        state.search.close()
        state.search.open()

        assertEquals("hit", state.search.query)
        assertEquals(1, state.search.matches.size)
        scope.cancel()
    }

    @Test
    fun `a committed command joins the vocabulary and the executed set`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        assertFalse(state.vocabulary.isCommand("pveversion"), "unknown before it is ever run")
        state.typeInput("pveversion -v")
        state.typeInput("\r")

        assertEquals(setOf("pveversion -v"), state.executedCommands)
        assertTrue(state.vocabulary.isCommand("pveversion"), "the host's own tool is known after one run")
        scope.cancel()
    }

    @Test
    fun `input typed at a password prompt never reaches the executed set`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.echoOff = true
        state.typeInput("hunter2")
        state.typeInput("\r")

        assertEquals(emptySet(), state.executedCommands)
        scope.cancel()
    }

    @Test
    fun `the executed set stays bounded over a long session`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        repeat(MAX_EXECUTED_COMMANDS + 50) { i ->
            state.typeInput("cmd$i")
            state.typeInput("\r")
        }

        assertEquals(MAX_EXECUTED_COMMANDS, state.executedCommands.size)
        assertTrue("cmd0" !in state.executedCommands, "the oldest command is dropped, not kept forever")
        assertTrue("cmd${MAX_EXECUTED_COMMANDS + 49}" in state.executedCommands, "the newest is kept")
        scope.cancel()
    }

    /**
     * Construction must survive output arriving before the constructor returns. The session below
     * emits at collection time, and with an unconfined dispatcher the init coroutine runs straight
     * through publishSnapshot -> refreshSuggestion — which writes state properties. If the init
     * block is ever moved above them, their `by mutableStateOf` delegates do not exist yet and this
     * throws a NullPointerException inside setValue.
     */
    @Test
    fun `a chunk arriving during construction does not break initialization`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = EagerOutputSession("hello\r\n".encodeToByteArray())
        val state = TerminalScreenState(session, scope)

        assertTrue(state.screen.isNotEmpty(), "the eager chunk was applied")
        assertFalse(state.hasSuggestion)
        scope.cancel()
    }

    @Test
    fun `an empty query clears matches without erroring`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\n".encodeToByteArray())
        state.search.open()
        state.search.updateQuery("hit")
        state.search.updateQuery("")

        assertEquals(emptyList(), state.search.matches)
        assertEquals(null, state.search.error)
        assertEquals(-1, state.search.index)
        scope.cancel()
    }

    @Test
    fun `next and previous are no-ops without matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.search.open()
        state.search.updateQuery("nothing here")
        state.search.next()
        state.search.prev()

        assertEquals(-1, state.search.index)
        assertEquals(null, state.search.currentMatch)
        scope.cancel()
    }

    @Test
    fun `resize forwards to session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.resize(PtySize(cols = 100, rows = 30))

        assertEquals(PtySize(cols = 100, rows = 30), session.resizes.single())
        scope.cancel()
    }

    @Test
    fun `resize applies the new grid to the emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // Narrow 5x3 grid: autowrap breaks the line at width 5.
        state.resize(PtySize(cols = 5, rows = 3))
        session.emit("abcdefgh".encodeToByteArray())

        assertEquals("abcde\nfgh", state.output)
        assertEquals(3, state.screen.size) // grid is now exactly 3 rows
        scope.cancel()
    }

    @Test
    fun `exposes the live grid size to the status bar`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // Default 80x24 before the first layout, then the emulator's live size.
        assertEquals(80, state.cols)
        assertEquals(24, state.rows)
        state.resize(PtySize(cols = 132, rows = 43))
        assertEquals(132, state.cols)
        assertEquals(43, state.rows)
        scope.cancel()
    }

    @Test
    fun `repeated resize with the same size forwards once`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.resize(PtySize(cols = 90, rows = 25))
        state.resize(PtySize(cols = 90, rows = 25)) // same size — dedup, don't nudge the PTY

        assertEquals(listOf(PtySize(cols = 90, rows = 25)), session.resizes)
        scope.cancel()
    }

    @Test
    fun `exposes session state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        assertEquals(TerminalState.Open, state.state.value)
        scope.cancel()
    }

    @Test
    fun `tracks application cursor keys mode from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(false, state.applicationCursorKeys)
        session.emit("$esc[?1h".encodeToByteArray()) // DECCKM on (vim/less)
        assertEquals(true, state.applicationCursorKeys)
        session.emit("$esc[?1l".encodeToByteArray()) // DECCKM off
        assertEquals(false, state.applicationCursorKeys)
        scope.cancel()
    }

    @Test
    fun `tracks cursor visibility shape and blink from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        // Defaults: cursor visible, block, blinking.
        assertEquals(true, state.cursorVisible)
        assertEquals(CursorShape.Block, state.cursorShape)
        assertEquals(true, state.cursorBlink)

        session.emit("$esc[?25l".encodeToByteArray())   // hide cursor
        assertEquals(false, state.cursorVisible)

        session.emit("$esc[6 q".encodeToByteArray())     // DECSCUSR: steady bar
        assertEquals(CursorShape.Bar, state.cursorShape)
        assertEquals(false, state.cursorBlink)
        scope.cancel()
    }

    @Test
    fun `selection over screen yields the spanned text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 5))

        assertEquals("hello", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `clearing selection drops the highlight and text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 3))
        state.clearSelection()

        assertEquals(null, state.selection)
        assertEquals(null, state.selectedText())
        scope.cancel()
    }

    @Test
    fun `selecting a word grabs the whole run under the position`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.selectWordAt(TerminalPos(0, 8)) // tap lands on "world"

        assertEquals("world", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `selecting a word from its first char still grabs the whole word`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.selectWordAt(TerminalPos(0, 0)) // tap lands on "h"

        assertEquals("hello", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `moving the end handle extends the selection keeping the start`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 5)) // "hello"
        state.moveSelectionEnd(TerminalPos(0, 11))

        assertEquals("hello world", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `moving the start handle shrinks the selection keeping the end`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 0))
        state.extendSelection(TerminalPos(0, 11)) // "hello world"
        state.moveSelectionStart(TerminalPos(0, 6))

        assertEquals("world", state.selectedText())
        scope.cancel()
    }

    @Test
    fun `moving a handle with no selection is a no-op`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        state.moveSelectionStart(TerminalPos(0, 1))
        state.moveSelectionEnd(TerminalPos(0, 3))

        assertEquals(null, state.selection)
        scope.cancel()
    }

    @Test
    fun `tracks mouse and bracketed-paste modes from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(MouseTracking.Off, state.mouseTracking)
        session.emit("$esc[?1002h$esc[?1006h$esc[?2004h".encodeToByteArray())
        assertEquals(MouseTracking.ButtonEvent, state.mouseTracking)
        assertEquals(true, state.mouseSgr)
        assertEquals(true, state.bracketedPaste)
        scope.cancel()
    }

    @Test
    fun `tracks any-event mouse mode and alt-screen from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(MouseTracking.Off, state.mouseTracking)
        assertEquals(false, state.altScreen)
        session.emit("$esc[?1003h$esc[?1049h".encodeToByteArray()) // AnyEvent + alt-screen
        assertEquals(MouseTracking.AnyEvent, state.mouseTracking)
        assertEquals(true, state.altScreen)
        scope.cancel()
    }

    @Test
    fun `reportMouse sends an sgr report and signals it handled the event`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        session.emit("$esc[?1000h$esc[?1006h".encodeToByteArray()) // Normal + SGR
        val handled = state.reportMouse(MouseButton.Left, MouseEventType.Press, TerminalPos(0, 0))

        assertEquals(true, handled)
        assertContentEquals("$esc[<0;1;1M".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `capturePrimarySelection stores the current selection text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello world".encodeToByteArray())
        state.selectWordAt(TerminalPos(0, 8)) // "world"
        val captured = state.capturePrimarySelection()

        assertEquals("world", captured)
        assertEquals("world", state.primarySelection)
        scope.cancel()
    }

    @Test
    fun `capturePrimarySelection is a no-op without a selection`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        val captured = state.capturePrimarySelection()

        assertEquals(null, captured)
        assertEquals(null, state.primarySelection)
        scope.cancel()
    }

    @Test
    fun `tracks mouse pixel mode 1016 from emulator`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        assertEquals(false, state.mousePixels)
        session.emit("$esc[?1016h".encodeToByteArray())
        assertEquals(true, state.mousePixels)
        scope.cancel()
    }

    @Test
    fun `reportMouse uses pixel coordinates when 1016 is active`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        session.emit("$esc[?1002h$esc[?1016h".encodeToByteArray()) // ButtonEvent + SGR-Pixels
        val handled = state.reportMouse(
            MouseButton.Left, MouseEventType.Press, TerminalPos(2, 3), pixelX = 49, pixelY = 99,
        )

        assertEquals(true, handled)
        // Coordinates are pixels (49+1 / 99+1), not cells.
        assertContentEquals("$esc[<0;50;100M".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `reportMouse is a no-op when mouse tracking is off`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val handled = state.reportMouse(MouseButton.Left, MouseEventType.Press, TerminalPos(0, 0))

        assertEquals(false, handled)
        assertEquals(0, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `paste wraps in bracketed markers when the mode is enabled`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        session.emit("$esc[?2004h".encodeToByteArray()) // bracketed paste on
        state.paste("hi")

        assertContentEquals("$esc[200~hi$esc[201~".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `paste passes text through when bracketed mode is off`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.paste("hi")

        assertContentEquals("hi".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `a recoverable resize failure keeps the command handler alive`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // A recoverable PTY resize failure: the handler must not die — feed still works after it.
        session.resizeError = { IllegalStateException("pty broke") }
        state.resize(PtySize(cols = 10, rows = 4))
        session.resizeError = null
        session.emit("ok".encodeToByteArray())

        assertEquals("ok", state.output)
        scope.cancel()
    }

    @Test
    fun `a failed resize does not poison the same-size dedup`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // The PTY resize fails once (transient hiccup on a live connection)…
        session.resizeError = { IllegalStateException("pty broke") }
        state.resize(PtySize(cols = 50, rows = 12))
        session.resizeError = null
        // …and the next request at the SAME size must reach the session again. The failed
        // attempt never landed, so deduping the retry away would leave the PTY at the old size
        // for good — with auto-fit's settled-snapshot gate waiting on exactly that resize.
        state.resize(PtySize(cols = 50, rows = 12))

        assertEquals(listOf(PtySize(cols = 50, rows = 12)), session.resizes)
        scope.cancel()
    }

    @Test
    fun `a cancellation during resize tears down the command handler`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        // CancellationException must not be swallowed as a "recoverable failure": it must tear
        // down the handler coroutine (structured concurrency), or feed would keep working after cancellation.
        session.resizeError = { CancellationException("scope cancelled") }
        state.resize(PtySize(cols = 10, rows = 4))
        session.emit("ignored".encodeToByteArray())

        assertEquals("", state.output) // handler torn down — feed never applied
        scope.cancel()
    }

    @Test
    fun `a burst of sends reaches the session in FIFO order`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        repeat(50) { state.send(it.toString()) }

        val order = session.sent.map { it.decodeToString() }
        assertEquals(List(50) { it.toString() }, order)
        scope.cancel()
    }

    @Test
    fun `snapshotVersion advances on every feed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val before = state.snapshotVersion
        session.emit("a".encodeToByteArray())
        session.emit("b".encodeToByteArray())

        assertEquals(before + 2, state.snapshotVersion)
        scope.cancel()
    }

    @Test
    fun `osc 52 clipboard write is dropped when the gate is off by default`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar().toString()

        val copies = mutableListOf<String>()
        val collector = scope.launch { state.clipboardCopies.collect { copies += it } }

        session.emit("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray()) // base64 "hello"
        assertEquals(emptyList(), copies) // gated off — no clipboard write reaches the UI
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `osc 52 clipboard write reaches the UI once the gate is enabled`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope, clipboardWriteEnabled = true)
        val esc = 27.toChar().toString()

        val copies = mutableListOf<String>()
        val collector = scope.launch { state.clipboardCopies.collect { copies += it } }

        session.emit("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray())
        assertEquals(listOf("hello"), copies)
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `applyClipboardWriteEnabled toggles the gate on an open session`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope) // starts off
        val esc = 27.toChar().toString()

        val copies = mutableListOf<String>()
        val collector = scope.launch { state.clipboardCopies.collect { copies += it } }

        session.emit("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray())
        assertEquals(emptyList(), copies)

        state.applyClipboardWriteEnabled(true) // live settings change
        session.emit("$esc]52;c;d29ybGQ=$esc\\".encodeToByteArray()) // base64 "world"
        assertEquals(listOf("world"), copies)
        collector.cancel()
        scope.cancel()
    }

    // --- production guard (risky commands on a #prod host) ---

    @Test
    fun `risky command is held before it reaches the pty on a production session`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /var/lib".forEach { state.typeInput(it.toString()) }
        state.typeInput("\r")

        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        // Everything typed so far went through; only the Enter that would run it is held.
        assertEquals(false, session.sent.any { it.contentEquals("\r".encodeToByteArray()) })
        scope.cancel()
    }

    /**
     * What the confirmation quotes has to be what Confirm replays, and for a typed block that is the
     * shell's line — the part already echoed plus the block arriving now. The case that tells them
     * apart is the one Android takes for every clipboard paste: the block continues a line that is
     * already there and carries more lines after it. Quoting the block alone would show neither the
     * command that tripped the guard nor, once that line stood in for it, the lines under it.
     */
    @Test
    fun `the quote for a typed block continues the line already on screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf /sr".forEach { state.typeInput(it.toString()) }
        state.typeInput("v\rchown -R nobody /srv/www\r")

        assertEquals("rm -rf /srv", state.pendingGuarded?.command)
        // Kept as the bytes that will run, carriage returns and all: the line breaks are a drawing
        // question, and the block that draws it is what turns them into breaks.
        assertEquals("rm -rf /srv\rchown -R nobody /srv/www", state.pendingGuardedQuote)
        scope.cancel()
    }

    /** A paste is quoted from the block itself, every line of it, and it is replayed the same way. */
    @Test
    fun `the quote for a pasted block carries the lines under the risky one`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.paste("rm -rf /srv\nchown -R nobody /srv/www\n")

        assertEquals("rm -rf /srv\nchown -R nobody /srv/www", state.pendingGuardedQuote)
        scope.cancel()
    }

    /**
     * A ready-made command lands on whatever the line already holds, so that is what the guard has
     * to classify and quote. Classifying the block alone let a snippet finish a half-typed `rm -rf `
     * with no question asked at all.
     */
    @Test
    fun `a command finishing a half-typed line is held and quoted whole`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "rm -rf ".forEach { state.typeInput(it.toString()) }
        state.sendUserInputGuarded("/srv/data\r")

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        assertEquals("rm -rf /srv/data", state.pendingGuardedQuote)
        // The typed prefix was echoed as it was typed; what the guard held is the block itself.
        assertTrue(session.sent.none { it.decodeToString().contains("/srv/data") })
        scope.cancel()
    }

    /** The same for a paste, which lands at the cursor exactly as a snippet does. */
    @Test
    fun `a paste finishing a half-typed line is quoted with it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "echo ".forEach { state.typeInput(it.toString()) }
        state.paste("rm -rf /srv\n")

        assertEquals("echo rm -rf /srv", state.pendingGuardedQuote)
        scope.cancel()
    }

    /**
     * A line the engine cannot follow any more — Ctrl-W ate a word the client never saw removed — is
     * not quoted at all: the guard falls back to the line it tripped on rather than drawing a
     * sentence the shell will not run.
     */
    @Test
    fun `a line edited by a control the client cannot follow is not quoted from`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("root@prod:~# rm -rf /srv/data".encodeToByteArray())

        // The tracked line keeps the word Ctrl-W removed from the shell's, and the screen — which is
        // what the guard trips on — holds the line as it really is.
        "rm -rf /srv/data extra".forEach { state.typeInput(it.toString()) }
        state.typeInput("\u0017") // Ctrl-W: the shell dropped a word, the tracked line did not
        state.typeInput("\r")

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        assertEquals("rm -rf /srv/data", state.pendingGuardedQuote)
        scope.cancel()
    }

    /**
     * The joined line is offered *beside* the block's own first line, never instead of it: the
     * classifier's patterns are word-anchored, so a tracked `git` joined to `rm -rf /srv` reads as
     * `gitrm -rf /srv` — harmless. The worst candidate wins, so the real command still holds.
     */
    @Test
    fun `a command joined onto a word is still classified on its own`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "git".forEach { state.typeInput(it.toString()) }
        state.sendUserInputGuarded("rm -rf /srv/cache\r")

        assertEquals("rm -rf /srv/cache", state.pendingGuarded?.command)
        scope.cancel()
    }

    /**
     * And once a ready-made command has gone out, the line it left behind is not the shell's any
     * more — nothing told the tracker it ran. Classifying the next command against it would raise a
     * reason out of text that is no longer there, and quote a line nothing will run.
     */
    @Test
    fun `a line left behind by a command that already ran is not joined onto the next`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "sudo ".forEach { state.typeInput(it.toString()) }
        state.sendUserInputGuarded("rm -rf /var/lib/pgsql\r")
        state.confirmGuardedCommand()

        state.sendUserInputGuarded("ls /var/lib\r")

        assertNull(state.pendingGuarded, "a stale `sudo ` held a command that does not run under it")
        scope.cancel()
    }

    /**
     * A command that ran without passing through the tracker leaves the line empty, not unknowable.
     * Treating it as unknowable cost everything after it: the next typed command was classified from
     * the screen row alone, and a snippet finishing it was not classified against it at all.
     */
    @Test
    fun `the line is tracked again after a command that ran elsewhere`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInputGuarded("docker ps\r") // harmless: not held, but it moved the line
        "rm -rf ".forEach { state.typeInput(it.toString()) }
        state.sendUserInputGuarded("/srv/data\r")

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        assertEquals("rm -rf /srv/data", state.pendingGuardedQuote)
        scope.cancel()
    }

    /**
     * And a block that arrives while the tracked line is stale is still quoted whole: what the client
     * cannot trust is the prefix, never the input it was just handed.
     */
    @Test
    fun `a block pasted onto a line the client lost track of is still quoted whole`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        "foo bar".forEach { state.typeInput(it.toString()) }
        state.typeInput("\u0017") // Ctrl-W: the tracked line is now a guess
        state.paste("rm -rf /srv\nchown -R nobody /srv/www\n")

        assertEquals("rm -rf /srv\nchown -R nobody /srv/www", state.pendingGuardedQuote)
        scope.cancel()
    }

    /**
     * The command typed after one that ran elsewhere still reaches history: the tracker knows the
     * line was cleared, so it has no reason to distrust what is typed onto it.
     */
    @Test
    fun `a command typed after a snippet still reaches history`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val history = mutableListOf<List<String>>()
        val state = TerminalScreenState(session, scope, onHistoryChanged = { history += it })

        state.sendUserInputGuarded("docker ps\r")
        session.emit("uptime".encodeToByteArray()) // echo, so the engine records what was typed
        "uptime".forEach { state.typeInput(it.toString()) }
        state.typeInput("\r")

        assertTrue(history.any { "uptime" in it }, "the command after a snippet never reached history")
        scope.cancel()
    }

    /**
     * The assistant's Edit puts a command on the line without running it. Nothing else tells the
     * tracker, so the Enter that follows used to commit the line as it was before — the command the
     * user actually ran was not the one that reached history.
     */
    @Test
    fun `a command put on the line to be edited is tracked as if typed`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val history = mutableListOf<List<String>>()
        val state = TerminalScreenState(session, scope, onHistoryChanged = { history += it })

        state.sendUserInputGuarded("uptime") // Edit: the command lands on the line, nothing runs
        session.emit("uptime".encodeToByteArray())
        state.typeInput("\r")

        assertTrue(history.any { "uptime" in it }, "the edited command never reached history")
        scope.cancel()
    }

    /**
     * The mobile key panel sends its arrows and Esc as raw sequences through the same path a
     * ready-made command takes. They edit the shell's line in ways nothing here can follow, so the
     * line becomes a guess — gluing the bytes on would carry them into the next history entry.
     */
    @Test
    fun `an escape sequence sent to the line does not become part of the next command`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val history = mutableListOf<List<String>>()
        val state = TerminalScreenState(session, scope, onHistoryChanged = { history += it })

        state.sendUserInput("\u001b[A") // arrow up from the key panel: the shell recalled a command
        "uptime".forEach { state.typeInput(it.toString()) }
        state.typeInput("\r")

        // Both halves: the sequence is not glued onto the command, and the command still lands —
        // marking the line unreadable for good would cost every command after it its history entry.
        assertTrue(history.any { "uptime" in it }, "the command after an escape never reached history")
        assertTrue(
            history.flatten().none { it.any { c -> c < ' ' } },
            "an escape sequence was recorded as part of a command: ${history.flatten()}",
        )
        scope.cancel()
    }

    /**
     * A line the client lost track of is not joined onto what arrives next — the join would be a
     * line nothing will run, and the guard would quote it as the thing being confirmed. What the
     * shell really holds is then the screen's business, and the screen is read on every path.
     */
    @Test
    fun `a ready-made command is classified against the line on screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("root@prod:~# rm -rf /srv/data".encodeToByteArray())

        "rm -rf /srv/data".forEach { state.typeInput(it.toString()) }
        state.typeInput("\u0017") // Ctrl-W: the tracked line is a guess from here on
        state.sendUserInputGuarded("docker ps\r")

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        // What Confirm sends is quoted; the screen's line is stated beside it, as its own fact.
        assertEquals("docker ps", state.pendingGuardedQuote)
        assertEquals("rm -rf /srv/data", state.pendingGuardedAside?.line)
        scope.cancel()
    }

    /** The same on the paste path, which has no tracked line to fall back on either. */
    @Test
    fun `a paste is classified against the line on screen`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        session.emit("root@prod:~# rm -rf /srv/data".encodeToByteArray())

        "rm -rf /srv/data".forEach { state.typeInput(it.toString()) }
        state.typeInput("\u0017") // Ctrl-W: the tracked line is a guess from here on
        state.paste(" --one-file-system\n") // harmless on its own

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        scope.cancel()
    }

    /**
     * The classifier reads a bounded number of candidates and a long block fills that on its own, so
     * the joined line — the one candidate that exists because the block finishes what was typed — is
     * classified beside the block rather than inside its budget.
     */
    @Test
    fun `a long block finishing a half-typed line is still held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // With a prompt on screen, as any live session has: the screen's candidates and the join are
        // guesses about the same line, and neither may cost the block a line of its own.
        session.emit("root@prod:~# ".encodeToByteArray())
        "rm -rf ".forEach { state.typeInput(it.toString()) }
        val block = "/srv/data\n" + (1..300).joinToString("\n") { "echo step $it" } + "\n"
        state.sendUserInputGuarded(block)

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `confirming the held command sends it once`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.typeInput("rm -rf /var/lib\r")
        assertEquals(0, session.sent.size)

        state.confirmGuardedCommand()
        assertContentEquals("rm -rf /var/lib\r".encodeToByteArray(), session.sent.single())
        assertEquals(null, state.pendingGuarded)
        scope.cancel()
    }

    @Test
    fun `dismissing the held command sends nothing`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.typeInput("shutdown now\r")
        state.dismissGuardedCommand()

        assertEquals(emptyList(), session.sent.toList())
        assertEquals(null, state.pendingGuarded)
        scope.cancel()
    }

    @Test
    fun `harmless commands and non-production sessions are not held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)
        state.typeInput("ls -la\r")
        assertEquals(null, state.pendingGuarded)

        state.guardPolicy = ProductionGuardPolicy.Off
        state.typeInput("rm -rf /\r")
        assertEquals(null, state.pendingGuarded)
        assertEquals(2, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a command recalled from history is caught off the screen line`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Nothing was typed locally (arrow-up recall) — the command exists only on the screen.
        session.emit("root@prod:~# rm -rf /srv/data".encodeToByteArray())
        state.typeInput("\r")

        assertEquals("rm -rf /srv/data", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `a multi-line input block is judged by every line, not just the first`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // The soft keyboard delivers a whole IME delta in one call — a clipboard insert can carry
        // several lines, and the risky one is not necessarily the first.
        state.typeInput("ls\nrm -rf /var/lib\n")

        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        assertEquals(emptyList(), session.sent.toList())
        scope.cancel()
    }

    @Test
    fun `a command pasted without a newline is still caught on Enter`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Paste, then Enter before the host echoed anything back: the screen line is still empty,
        // so the guard has to remember what was pasted into the line.
        state.paste("rm -rf /var/lib")
        state.typeInput("\r")

        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `a ready-made command is dropped while another one is pending`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInputGuarded("rm -rf /var/lib\n")
        state.sendUserInputGuarded("shutdown now\n") // e.g. a snippet hotkey over the open dialog

        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        state.confirmGuardedCommand()
        assertContentEquals("rm -rf /var/lib\n".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `a snippet command is held and then sent without touching autocomplete`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInputGuarded("systemctl stop nginx\n")
        assertEquals("systemctl stop nginx", state.pendingGuarded?.command)
        assertEquals(emptyList(), session.sent.toList())

        state.confirmGuardedCommand()
        assertContentEquals("systemctl stop nginx\n".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `a harmless snippet command goes straight through`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInputGuarded("uptime\n")

        assertEquals(null, state.pendingGuarded)
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a second risky command while one is pending does not replace it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.typeInput("rm -rf /var/lib\r")
        val first = state.pendingGuarded
        // Impatient second Enter while the dialog is still up: the held command must stay the one
        // the dialog is showing, or the user would confirm a different command than they read.
        state.typeInput("shutdown now\r")

        assertEquals(first?.command, state.pendingGuarded?.command)
        state.confirmGuardedCommand()
        // Only the command the dialog was showing runs; the second one was dropped, not queued.
        assertContentEquals("rm -rf /var/lib\r".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `a pasted command that would run is held too`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Paste carrying a newline runs on arrival — the classic "copied it off a wiki page" case.
        state.paste("systemctl stop postgres\n")
        assertEquals("systemctl stop postgres", state.pendingGuarded?.command)
        assertEquals(emptyList(), session.sent.toList())

        state.confirmGuardedCommand()
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a paste with no newline runs nothing and is not held`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Lands on the shell line for the user to read and edit; Enter is still guarded separately.
        state.paste("rm -rf /var/lib")

        assertEquals(null, state.pendingGuarded)
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a password is never held or shown by the guard`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // At a password prompt the typed text is a secret, not a command: it must not be parked in
        // a dialog for everyone to read, whatever it happens to look like.
        session.emit("sudo password for root: ".encodeToByteArray())
        state.typeInput("rm -rf /\r")

        assertEquals(null, state.pendingGuarded)
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a password is still recognised once the session has scrollback`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // A real session is never on its first screenful: output scrolls history in long before any
        // sudo prompt appears. The prompt row must still be found then, or the secret typed into it
        // is treated as a command — held in a dialog and saved to history.
        repeat(state.rows + 5) { session.emit("filler line\r\n".encodeToByteArray()) }
        session.emit("sudo password for root: ".encodeToByteArray())
        state.typeInput("rm -rf /\r")

        assertEquals(null, state.pendingGuarded, "the password was taken for a command once history existed")
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a risky command recalled with arrow-up is still guarded after scrollback builds up`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Arrow-up recall: nothing is typed, the command is only on the screen line. The guard reads
        // that line, so it has to address it correctly with history in the snapshot.
        repeat(state.rows + 5) { session.emit("filler line\r\n".encodeToByteArray()) }
        session.emit("root@prod:~# rm -rf /var/lib".encodeToByteArray())
        state.typeInput("\r")

        assertNotNull(state.pendingGuarded, "a destructive command went through unconfirmed on a prod host")
        scope.cancel()
    }

    @Test
    fun `full-screen apps are not guarded`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // vim/htop: there is no shell line to classify, and Enter there is not "run a command".
        val esc = 27.toChar().toString()
        session.emit("$esc[?1049h".encodeToByteArray())
        session.emit(":%s/a/b/g sudo rm -rf".encodeToByteArray())
        state.typeInput("\r")

        assertEquals(null, state.pendingGuarded)
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a paste is dropped while a confirmation is open`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.typeInput("rm -rf /var/lib\r")
        // Middle-click paste lands on the terminal surface through a raw pointer handler, which the
        // modal scrim does not consume — it must not slip past the open dialog.
        state.paste("shutdown now\n")

        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        assertEquals(emptyList(), session.sent.toList())
        state.confirmGuardedCommand()
        assertContentEquals("rm -rf /var/lib\r".encodeToByteArray(), session.sent.single())
        scope.cancel()
    }

    @Test
    fun `a harmless paste is dropped while a confirmation is open too`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.typeInput("rm -rf /var/lib\r")
        state.paste("uptime\n")

        // Harmless or not, it would run on the production shell under a dialog asking about
        // something else entirely.
        assertEquals(emptyList(), session.sent.toList())
        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `a pasted password is never held or shown by the guard`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        // Same rule as typing one: a password manager pastes the secret with a trailing newline, and
        // a passphrase can look like anything — parking it in the dialog would print it on screen.
        session.emit("sudo password for root: ".encodeToByteArray())
        state.paste("sudo rm -rf everything\n")

        assertEquals(null, state.pendingGuarded)
        assertEquals(1, session.sent.size)
        scope.cancel()
    }

    @Test
    fun `a harmless typed command is not sent while a confirmation is open`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.typeInput("rm -rf /var/lib\r")
        state.typeInput("uptime\r")

        // Nothing runs on a production shell while the user is answering a question about a
        // different command — the dialog is not a queue.
        assertEquals(emptyList(), session.sent.toList())
        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `a harmless ready-made command is dropped while a confirmation is open`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.guardPolicy = ProductionGuardPolicy(production = true, confirmWarnings = true)

        state.sendUserInputGuarded("rm -rf /var/lib\n")
        // A snippet hotkey fires from the window root, above the modal scrim's focus.
        state.sendUserInputGuarded("uptime\n")

        assertEquals(emptyList(), session.sent.toList())
        assertEquals("rm -rf /var/lib", state.pendingGuarded?.command)
        scope.cancel()
    }

    @Test
    fun `empty selection produces no copyable text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hello".encodeToByteArray())
        state.beginSelection(TerminalPos(0, 2))
        // Focus was never moved — nothing to select.

        assertEquals(null, state.selectedText())
        scope.cancel()
    }

    @Test
    fun `clearing a soft wrap republishes the screen even when no cell changes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        state.resize(PtySize(cols = 4, rows = 4))

        // 中 does not fit in the last column: row 0 is marked wrapped and its column 3 stays blank.
        session.emit("ABC中".encodeToByteArray())
        assertTrue(state.screen[0].wrapsToNextRow())

        // ESC[1;4H ESC[K erases from that blank column — every cell keeps its value, only the wrap
        // goes away. A snapshot compared on cells alone would look unchanged and never be published.
        session.emit("${27.toChar()}[1;4H${27.toChar()}[K".encodeToByteArray())
        assertFalse(state.screen[0].wrapsToNextRow())
        scope.cancel()
    }

    @Test
    fun `a snapshot differing only in the soft-wrap flag is not treated as unchanged`() {
        // Compose skips a state write when the new value is equivalent to the old one, and list
        // equality only sees cells. A row can lose its wrap (EL over an already-blank tail) without a
        // single cell changing — publishing that as "no change" would leave link joining on stale flags.
        val cells = listOf(TermCell('a'), TermCell('b'))
        val wrapped = listOf<List<TermCell>>(TermSnapshotRow(cells, wrapped = true))
        val plain = listOf<List<TermCell>>(TermSnapshotRow(cells, wrapped = false))

        assertTrue(sameScreen(wrapped, listOf(TermSnapshotRow(cells, wrapped = true))))
        assertFalse(sameScreen(wrapped, plain))
        assertFalse(sameScreen(plain, listOf<List<TermCell>>(listOf(TermCell('a')))))
    }

    // --- Runbook step marks ---

    @Test
    fun `a step report is taken once, by the step it belongs to`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar()
        val bel = 7.toChar()
        fun step(token: String, output: String, exitCode: Int) =
            "$esc]$STEP_MARK_OSC;$token;$bel$output\r\n$esc]$STEP_MARK_OSC;$token;$exitCode$bel"

        state.expectStepMark("sk_run_0")
        session.emit(step("sk_run_0", "healthz 200 OK", 0).encodeToByteArray())

        assertNull(state.takeStepMark("sk_run_1"), "another step's report is not this step's")
        assertNull(state.takeStepMark("sk_run_0"), "and taking it dropped it — it is not queued")

        state.expectStepMark("sk_run_1")
        session.emit(step("sk_run_1", "denied", 13).encodeToByteArray())
        val mark = assertNotNull(state.takeStepMark("sk_run_1"))

        assertEquals(TerminalStepMark("sk_run_1", 13, "denied"), mark)
        assertNull(state.takeStepMark("sk_run_1"), "the report is consumed, not kept in memory")
        // Nothing of the protocol reached the screen.
        assertEquals("healthz 200 OK\ndenied", state.output)
        scope.cancel()
    }

    @Test
    fun `a terminal that is not running a step reports nothing and parks nothing`() = runTest {
        // Every session parses the sequence, not just one running a runbook: a host that emits it
        // must not be able to park a copy of the screen in a field nothing ever clears.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar()
        val bel = 7.toChar()

        session.emit("$esc]$STEP_MARK_OSC;sk_run_0;${bel}secret\r\n$esc]$STEP_MARK_OSC;sk_run_0;0$bel".encodeToByteArray())

        assertNull(state.takeStepMark("sk_run_0"))
        assertEquals("secret", state.output, "the output itself is drawn as usual")
        scope.cancel()
    }

    @Test
    fun `ending the step drops the report the run never collected`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)
        val esc = 27.toChar()
        val bel = 7.toChar()
        state.expectStepMark("sk_run_0")
        session.emit("$esc]$STEP_MARK_OSC;sk_run_0;${bel}vault secret\r\n$esc]$STEP_MARK_OSC;sk_run_0;0$bel".encodeToByteArray())

        state.expectStepMark(null) // the run was stopped before it read the report

        assertNull(state.takeStepMark("sk_run_0"), "a captured command's output has no reason to linger")
        scope.cancel()
    }

    @Test
    fun `output version moves on every batch from the host`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        val start = state.outputVersion
        session.emit("a".encodeToByteArray())
        // A window title leaves the screen exactly as it was — the host is talking all the same, and
        // that is the difference between this counter and hashing the visible tail.
        session.emit("${27.toChar()}]0;deploy${7.toChar()}".encodeToByteArray())
        // Same bytes as a moment ago, redrawn in place (a progress line rewriting its own row): the
        // screen is identical, the step is very much alive, and the watchdog must not call it quiet.
        session.emit("\r50%".encodeToByteArray())
        val redrawn = state.output
        session.emit("\r50%".encodeToByteArray())

        assertEquals(redrawn, state.output, "the screen really is unchanged")
        assertEquals(start + 4, state.outputVersion)
        scope.cancel()
    }
}

/**
 * Emits [chunks] one-byte chunks as fast as the collector allows; records resizes. A [resizeGate]
 * parks the emulator owner inside a Resize apply until completed — the only way, under the
 * single-threaded test dispatcher, to hold a genuinely saturated feed backlog while the test acts.
 */
private class FloodingTerminalSession(
    private val chunks: Int,
    private val resizeGate: CompletableDeferred<Unit>? = null,
) : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    var emitted = 0
        private set
    val resizes = mutableListOf<PtySize>()

    override val output: Flow<ByteArray> = flow {
        repeat(chunks) {
            emit(byteArrayOf('x'.code.toByte()))
            emitted++
        }
    }

    override suspend fun send(data: ByteArray) = Unit

    override suspend fun resize(size: PtySize) {
        resizeGate?.await()
        resizes += size
    }

    var closed = false
        private set

    override suspend fun close() {
        closed = true
    }
}

/** Fake session: manual output emission, captures send/resize calls. */
private class FakeTerminalSession : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state

    private val emissions = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = flow {
        for (chunk in emissions) emit(chunk)
    }

    val sent = mutableListOf<ByteArray>()
    val resizes = mutableListOf<PtySize>()

    /** Host stopped echoing — how the transport reports a password prompt. */
    var echoOff = false
    override val echoSuppressed: Boolean get() = echoOff

    /** When set, `resize` throws this before recording the call (simulates a PTY error/cancellation). */
    var resizeError: (() -> Throwable)? = null

    suspend fun emit(chunk: ByteArray) {
        emissions.send(chunk)
    }

    override suspend fun send(data: ByteArray) {
        sent += data
    }

    override suspend fun resize(size: PtySize) {
        resizeError?.let { throw it() }
        resizes += size
    }

    override suspend fun close() {
        _state.value = TerminalState.Closed()
        emissions.close()
    }
}

/**
 * A session whose output is already available the moment it is collected — the shape that makes a
 * PTY chunk land while [TerminalScreenState] is still being constructed.
 */
private class EagerOutputSession(private vararg val chunks: ByteArray) : TerminalSession {
    private val _state = MutableStateFlow<TerminalState>(TerminalState.Open)
    override val state: StateFlow<TerminalState> = _state
    override val output: Flow<ByteArray> = flow { chunks.forEach { emit(it) } }
    override suspend fun send(data: ByteArray) {}
    override suspend fun resize(size: PtySize) {}
    override suspend fun close() {}
}
