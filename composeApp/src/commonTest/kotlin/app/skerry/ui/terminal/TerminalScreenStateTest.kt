package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuardPolicy
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.CursorShape
import app.skerry.shared.terminal.MouseButton
import app.skerry.shared.terminal.MouseEventType
import app.skerry.shared.terminal.MouseTracking
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSearchError
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.session.paneSyncTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        // Keybar keys, snippet runs, and AI-confirmed commands are user-initiated: they must snap
        // a scrolled-up viewport back to the live screen (unlike programmatic send), but must not
        // feed autocomplete (unlike typeInput).
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
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("git push origin main"),
        )
        state.typeInput("git pu")
        assertEquals("sh origin main", state.suggestionTail)
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
        val state = TerminalScreenState(
            FakeTerminalSession(), scope,
            initialHistory = listOf("backupdb", "backupfiles"),
        )
        state.typeInput("back")
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
        state.openReverseSearch()
        state.reverseSearchAppend("git")
        assertEquals("git status", state.reverseSearchSelection)
        state.reverseSearchAccept()
        assertEquals(null, state.reverseSearchQuery) // overlay closed after accepting
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
        state.openReverseSearch()
        state.reverseSearchAppend("gti") // pick the typo entry
        assertEquals("gti status", state.reverseSearchSelection)
        state.reverseSearchDeleteSelected()
        assertEquals(emptyList(), state.reverseSearchResults) // "gti" is no longer found
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
        state.openSearch()
        state.updateSearchQuery("alpha")

        assertEquals(2, state.searchMatches.size)
        assertEquals(true, state.currentMatch != null)
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
        state.openSearch(anchorRow = 2) // viewport bottom sits on the second "hit"
        state.updateSearchQuery("hit")

        assertEquals(1, state.searchIndex)
        assertEquals(2, state.currentMatch?.row)
        scope.cancel()
    }

    @Test
    fun `next and previous cycle through matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\nhit\r\nhit\r\n".encodeToByteArray())
        state.openSearch(anchorRow = 0)
        state.updateSearchQuery("hit")

        assertEquals(0, state.searchIndex)
        state.searchNext()
        assertEquals(1, state.searchIndex)
        state.searchPrev()
        assertEquals(0, state.searchIndex)
        state.searchPrev() // wraps to the last match
        assertEquals(2, state.searchIndex)
        state.searchNext() // wraps back to the first
        assertEquals(0, state.searchIndex)
        scope.cancel()
    }

    @Test
    fun `case sensitivity and regex toggles re-run the search`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("Error 404\r\nerror 500\r\n".encodeToByteArray())
        state.openSearch()
        state.updateSearchQuery("error")
        assertEquals(2, state.searchMatches.size)

        state.applySearchCase(true)
        assertEquals(1, state.searchMatches.size)

        state.applySearchCase(false)
        state.updateSearchQuery("\\d{3}")
        assertEquals(0, state.searchMatches.size) // still a literal search
        state.applySearchRegex(true)
        assertEquals(2, state.searchMatches.size)
        scope.cancel()
    }

    @Test
    fun `an invalid regex is reported without matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("anything\r\n".encodeToByteArray())
        state.openSearch()
        state.applySearchRegex(true)
        state.updateSearchQuery("a(")

        assertEquals(TerminalSearchError.InvalidPattern, state.searchError)
        assertEquals(emptyList(), state.searchMatches)
        assertEquals(null, state.currentMatch)
        scope.cancel()
    }

    @Test
    fun `new output refreshes matches while search is open`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        var now = 0L
        val state = TerminalScreenState(session, scope, nowMillis = { now })

        session.emit("hit one\r\n".encodeToByteArray())
        state.openSearch()
        state.updateSearchQuery("hit")
        assertEquals(1, state.searchMatches.size)

        now += SEARCH_REFRESH_INTERVAL_MS + 1 // past the refresh throttle window
        session.emit("hit two\r\n".encodeToByteArray())
        assertEquals(2, state.searchMatches.size)
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
        state.openSearch()
        state.updateSearchQuery("hit")
        state.searchPrev() // select the first (older) hit
        val selected = state.currentMatch

        now += SEARCH_REFRESH_INTERVAL_MS + 1
        session.emit("hit three\r\n".encodeToByteArray())

        assertEquals(selected, state.currentMatch)
        assertEquals(3, state.searchMatches.size)
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
        state.openSearch()
        state.updateSearchQuery("hit")
        assertEquals(1, state.searchMatches.size)

        session.emit("hit two\r\n".encodeToByteArray()) // same instant — throttled away
        assertEquals(1, state.searchMatches.size)

        now += SEARCH_REFRESH_INTERVAL_MS + 1
        session.emit("hit three\r\n".encodeToByteArray())
        assertEquals(3, state.searchMatches.size) // catches up on the next snapshot after the window
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
        state.openSearch()
        state.updateSearchQuery("hit")
        session.emit("hit two\r\n".encodeToByteArray())
        assertEquals(1, state.searchMatches.size)

        state.searchNext()

        assertEquals(2, state.searchMatches.size)
        assertEquals(1, state.searchIndex) // moved onto the newly found hit
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
        state.openReverseSearch()
        state.openSearch()
        assertEquals(null, state.reverseSearchQuery)

        state.openReverseSearch()
        assertEquals(null, state.searchQuery)
        scope.cancel()
    }

    @Test
    fun `an oversized query is capped`() = runTest {
        // A stray paste (a whole log line, a file) is not a search term; an unbounded pattern also
        // hands the regex compiler unbounded work.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val state = TerminalScreenState(FakeTerminalSession(), scope)

        state.openSearch()
        state.updateSearchQuery("x".repeat(MAX_SEARCH_QUERY_LENGTH + 500))

        assertEquals(MAX_SEARCH_QUERY_LENGTH, state.searchQuery?.length)
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
        state.setSearchAnchorRow(2) // user scrolled up: second "hit" is the last visible row
        state.openSearch()
        state.updateSearchQuery("hit")

        assertEquals(2, state.currentMatch?.row)
        scope.cancel()
    }

    @Test
    fun `closing search drops the query and matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\n".encodeToByteArray())
        state.openSearch()
        state.updateSearchQuery("hit")
        state.closeSearch()

        assertEquals(null, state.searchQuery)
        assertEquals(emptyList(), state.searchMatches)
        assertEquals(null, state.currentMatch)
        scope.cancel()
    }

    @Test
    fun `output is not searched while the panel is closed`() = runTest {
        // The buffer is walked on every published snapshot, so a closed panel must cost nothing.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.openSearch()
        state.updateSearchQuery("hit")
        state.closeSearch()
        session.emit("hit\r\n".encodeToByteArray())

        assertEquals(emptyList(), state.searchMatches)
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
        state.openSearch()
        state.updateSearchQuery("hit")
        state.closeSearch()
        state.openSearch()

        assertEquals("hit", state.searchQuery)
        assertEquals(1, state.searchMatches.size)
        scope.cancel()
    }

    @Test
    fun `an empty query clears matches without erroring`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        session.emit("hit\r\n".encodeToByteArray())
        state.openSearch()
        state.updateSearchQuery("hit")
        state.updateSearchQuery("")

        assertEquals(emptyList(), state.searchMatches)
        assertEquals(null, state.searchError)
        assertEquals(-1, state.searchIndex)
        scope.cancel()
    }

    @Test
    fun `next and previous are no-ops without matches`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val session = FakeTerminalSession()
        val state = TerminalScreenState(session, scope)

        state.openSearch()
        state.updateSearchQuery("nothing here")
        state.searchNext()
        state.searchPrev()

        assertEquals(-1, state.searchIndex)
        assertEquals(null, state.currentMatch)
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
