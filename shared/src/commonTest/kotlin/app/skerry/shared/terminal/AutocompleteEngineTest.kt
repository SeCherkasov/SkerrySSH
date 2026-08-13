package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandHistoryTest {

    @Test
    fun `records newest first and dedupes`() {
        val h = CommandHistory()
        h.record("ls")
        h.record("cd /tmp")
        h.record("ls") // repeat moves to the top, no duplicate
        assertEquals(listOf("ls", "cd /tmp"), h.commands)
    }

    @Test
    fun `blank commands are ignored`() {
        val h = CommandHistory()
        h.record("   ")
        h.record("")
        assertTrue(h.commands.isEmpty())
    }

    @Test
    fun `capacity trims oldest`() {
        val h = CommandHistory(capacity = 2)
        h.record("a"); h.record("b"); h.record("c")
        assertEquals(listOf("c", "b"), h.commands)
    }

    @Test
    fun `suggestion returns newest matching longer entry`() {
        val h = CommandHistory()
        h.record("git status")
        h.record("git push origin main")
        assertEquals("git push origin main", h.suggestion("git p"))
        assertEquals("git status", h.suggestion("git s"))
        assertNull(h.suggestion("git status")) // equals the prefix: no suggestion
        assertNull(h.suggestion("")) // empty prefix
    }

    @Test
    fun `matches returns all prefix matches newest first`() {
        val h = CommandHistory()
        h.record("git status")
        h.record("git stash")
        assertEquals(listOf("git stash", "git status"), h.matches("git s"))
        assertTrue(h.matches("").isEmpty())
    }

    @Test
    fun `forget removes a command from history`() {
        val h = CommandHistory()
        h.record("gti status")
        h.record("git status")
        assertTrue(h.forget("gti status"))
        assertEquals(listOf("git status"), h.commands)
        assertFalse(h.forget("nope")) // not present: false
    }

    @Test
    fun `search finds substring matches newest first`() {
        val h = CommandHistory()
        h.record("git status")
        h.record("docker ps")
        h.record("git push")
        assertEquals(listOf("git push", "git status"), h.search("git"))
        assertEquals(listOf("docker ps"), h.search("ps")) // substring, not prefix
        assertTrue(h.search("").isEmpty())
    }
}

class AutocompleteEngineTest {

    /**
     * A completion only stays a prefix until something is typed onto it: the shell inserted its own
     * characters where the cursor was, and appending here makes a line neither side has. The guard
     * would classify that invention and, being the shortest candidate, quote it as the command.
     */
    @Test
    fun `a character typed after a completion is not a prefix of anything`() {
        val engine = AutocompleteEngine()
        engine.onUserInput("rm -rf /srv/pro\t".encodeToByteArray())
        assertTrue(engine.linePartial)

        engine.onUserInput("cache".encodeToByteArray())

        assertTrue(engine.lineSuspect, "the line is still a guess")
        assertFalse(engine.linePartial, "an invented line was offered as a prefix")
    }

    /** And a control byte that rewrites the line on the shell's side ends the prefix too. */
    @Test
    fun `a line edited on the shell side after a completion is not a prefix`() {
        val engine = AutocompleteEngine()
        engine.onUserInput("rm -rf /srv/dat\t".encodeToByteArray())

        engine.onUserInput(byteArrayOf(16)) // Ctrl-P: the shell recalls another line entirely

        assertTrue(engine.lineSuspect)
        assertFalse(engine.linePartial)
    }

    /**
     * Reverse search replaces the shell's line wholesale, like the history keys do, and what is
     * typed after it goes into the search box rather than onto the line. Appending it here would
     * quote `prod` as the thing being sent while `rm -rf /srv/prod-db` is what runs.
     */
    @Test
    fun `a reverse search makes the line a guess`() {
        val engine = AutocompleteEngine()
        engine.onUserInput("rm -rf /srv".encodeToByteArray())

        engine.onUserInput(byteArrayOf(18)) // Ctrl-R
        engine.onUserInput("prod".encodeToByteArray())

        assertTrue(engine.lineSuspect)
        assertNull(engine.suggestionTail())
    }

    /**
     * The engine models what is on the line, not where the cursor is in it. A control that moves the
     * insertion point leaves the content right and the position wrong, and text typed afterwards
     * lands somewhere this cannot predict — so the line stops being something to quote or complete.
     */
    @Test
    fun `a cursor moved inside the line makes it a guess`() {
        val engine = AutocompleteEngine()
        engine.commandHistory.preload(listOf("deploy --dry-run"))
        engine.onUserInput("deploy".encodeToByteArray())
        assertEquals(" --dry-run", engine.suggestionTail())

        engine.onUserInput(byteArrayOf(1)) // Ctrl-A: to the start of the line

        assertTrue(engine.lineSuspect)
        assertFalse(engine.linePartial)
        assertNull(engine.suggestionTail())
    }

    /**
     * A Tab the UI did not consume went to the shell, and the shell answers it by rewriting the line.
     * Nothing local can follow that, so the line stops being trusted rather than staying a prefix
     * that the guard would quote as the whole command.
     */
    @Test
    fun `a tab sent to the shell makes the line a guess`() {
        val engine = AutocompleteEngine()
        engine.commandHistory.preload(listOf("rm -rf /srv/prod-db"))
        engine.onUserInput("rm -rf /sr".encodeToByteArray())
        assertEquals("v/prod-db", engine.suggestionTail())

        engine.onUserInput("\t".encodeToByteArray())

        assertTrue(engine.lineSuspect)
        // And specifically a prefix of what the shell has, not a line of unknown contents: the
        // production guard classifies the one and drops the other.
        assertTrue(engine.linePartial)
        assertNull(engine.suggestionTail())
    }

    private fun engine(vararg history: String) =
        AutocompleteEngine(CommandHistory().apply { history.reversed().forEach { record(it) } })

    @Test
    fun `tracks typed line from user bytes`() {
        val e = AutocompleteEngine()
        e.onUserInput("ls -l".encodeToByteArray())
        assertEquals("ls -l", e.currentLine)
    }

    @Test
    fun `backspace removes last char`() {
        val e = AutocompleteEngine()
        e.onUserInput("lss".encodeToByteArray())
        e.onUserInput(byteArrayOf(127)) // DEL
        assertEquals("ls", e.currentLine)
    }

    @Test
    fun `enter commits the line to history and resets`() {
        val e = AutocompleteEngine()
        val committed = e.onUserInput("uptime\r".encodeToByteArray())
        assertEquals("uptime", committed)
        assertEquals("", e.currentLine)
        // retyping the prefix suggests the committed command
        e.onUserInput("up".encodeToByteArray())
        assertEquals("uptime", e.suggestion())
    }

    @Test
    fun `suggestion tail is the completion after the typed prefix`() {
        val e = engine("systemctl restart nginx")
        e.onUserInput("systemctl re".encodeToByteArray())
        assertEquals("start nginx", e.suggestionTail())
    }

    @Test
    fun `accept returns tail bytes and extends the line`() {
        val e = engine("docker compose up -d")
        e.onUserInput("docker com".encodeToByteArray())
        val bytes = e.acceptSuggestion()
        assertEquals("pose up -d", bytes?.decodeToString())
        assertEquals("docker compose up -d", e.currentLine)
    }

    @Test
    fun `builtins complete when history is empty`() {
        val e = AutocompleteEngine()
        e.onUserInput("gti".encodeToByteArray()) // no matches
        assertNull(e.suggestion())
        e.onUserInput(byteArrayOf(127, 127, 127)) // erase
        e.onUserInput("git st".encodeToByteArray())
        assertEquals("git status", e.suggestion())
    }

    @Test
    fun `arrow-key escape sequence clears the line without corrupting it`() {
        val e = AutocompleteEngine()
        e.onUserInput("ls".encodeToByteArray())
        e.onUserInput(byteArrayOf(27, '['.code.toByte(), 'A'.code.toByte())) // ESC [ A (up arrow)
        assertEquals("", e.currentLine)
    }

    @Test
    fun `reset clears the tracked line without recording to history`() {
        val e = AutocompleteEngine()
        e.onUserInput("secretpass".encodeToByteArray())
        e.reset() // e.g. entering no-echo mode (password input): do not commit
        assertEquals("", e.currentLine)
        // After reset, nothing should have entered history: retyping the same prefix gives no
        // suggestion (its source is only history/builtins, and "secretpass" is in neither).
        e.onUserInput("secret".encodeToByteArray())
        assertNull(e.suggestion())
    }

    @Test
    fun `no suggestion after a trailing space`() {
        val e = engine("git status")
        e.onUserInput("git ".encodeToByteArray())
        assertNull(e.suggestionTail())
    }

    @Test
    fun `cycle advances through candidates and wraps`() {
        val e = engine("backupdb", "backupfiles")
        e.onUserInput("back".encodeToByteArray())
        assertEquals("backupdb", e.suggestion())
        e.cycleSuggestion()
        assertEquals("backupfiles", e.suggestion())
        e.cycleSuggestion() // wrap
        assertEquals("backupdb", e.suggestion())
    }

    @Test
    fun `cycle position resets when the line changes`() {
        val e = engine("backupdb", "backupfiles")
        e.onUserInput("back".encodeToByteArray())
        e.cycleSuggestion()
        assertEquals("backupfiles", e.suggestion())
        e.onUserInput("u".encodeToByteArray()) // "backu": the line changed
        assertEquals("backupdb", e.suggestion()) // cycle position reset to the first candidate
    }

    @Test
    fun `completes known subcommand when history is empty`() {
        val e = AutocompleteEngine()
        e.onUserInput("git pus".encodeToByteArray())
        assertEquals("git push", e.suggestion())
        e.onUserInput(byteArrayOf(127, 127, 127, 127, 127, 127, 127)) // erase
        e.onUserInput("docker ru".encodeToByteArray())
        assertEquals("docker run", e.suggestion())
    }

    @Test
    fun `four-byte utf-8 characters keep both surrogates in the tracked line`() {
        val e = AutocompleteEngine()
        // A non-BMP emoji encodes as 4 UTF-8 bytes -> a UTF-16 surrogate pair: the tracked line must
        // get both surrogates, not just the high one (otherwise currentLine is corrupt and its length drifts).
        e.onUserInput("echo 😀 ok".encodeToByteArray())
        assertEquals("echo 😀 ok", e.currentLine)
    }

    @Test
    fun `completes a path token seen earlier in the session`() {
        val e = AutocompleteEngine()
        e.onUserInput("cat /etc/nginx/nginx.conf\r".encodeToByteArray())
        e.onUserInput("vim /etc/ng".encodeToByteArray())
        assertEquals("vim /etc/nginx/nginx.conf", e.suggestion())
        assertEquals("inx/nginx.conf", e.suggestionTail())
    }

    @Test
    fun `a screen-only control byte leaves the line trustworthy`() {
        // Ctrl-L redraws the prompt; the line itself is untouched, so the command it ends up running
        // still belongs in history.
        val e = AutocompleteEngine()
        e.onUserInput("systemctl restart nginx".encodeToByteArray())
        e.onUserInput(byteArrayOf(12)) // Ctrl-L
        assertEquals(false, e.lineSuspect)

        e.onUserInput("\r".encodeToByteArray())
        assertEquals(listOf("systemctl restart nginx"), e.commandHistory.commands)
    }

    @Test
    fun `history recall on the host makes the line suspect`() {
        // Ctrl-P replaces the whole line with a recalled command; recording what this engine still
        // holds would file a command that never ran and offer it back next session.
        val e = AutocompleteEngine()
        e.onUserInput("git s".encodeToByteArray())
        e.onUserInput(byteArrayOf(16)) // Ctrl-P
        assertEquals(true, e.lineSuspect)

        e.onUserInput("\r".encodeToByteArray())
        assertTrue(e.commandHistory.commands.isEmpty())
    }

    @Test
    fun `a yank into an empty line makes it suspect too`() {
        // Ctrl-Y pastes the kill ring: the host's line grows while this one stays empty.
        val e = AutocompleteEngine()
        e.onUserInput(byteArrayOf(25)) // Ctrl-Y on a fresh prompt
        e.onUserInput(" --now\r".encodeToByteArray())

        assertTrue(e.commandHistory.commands.isEmpty())
    }

    @Test
    fun `a suspect line is not completed either`() {
        val e = AutocompleteEngine(CommandHistory().apply { record("git push origin main") })
        e.onUserInput("git pu".encodeToByteArray())
        e.onUserInput(byteArrayOf(23)) // Ctrl-W

        assertEquals(null, e.suggestion())
        assertEquals(null, e.acceptSuggestion())
    }

    @Test
    fun `a line a control byte may have edited is neither suggested nor recorded`() {
        // Ctrl-W kills a word on the host; this engine does not track that, so from there its line
        // and the host's can disagree — offering a completion for it, or filing it as a command that
        // ran, would both be wrong.
        val e = AutocompleteEngine(CommandHistory().apply { record("git push origin main") })
        e.onUserInput("git pu".encodeToByteArray())
        assertEquals(false, e.lineSuspect)

        e.onUserInput(byteArrayOf(23)) // Ctrl-W
        assertEquals(true, e.lineSuspect)

        e.onUserInput("\r".encodeToByteArray())
        assertEquals(false, e.lineSuspect) // a fresh line is trustworthy again
        assertEquals(listOf("git push origin main"), e.commandHistory.commands)
    }

    /**
     * A ready-made command never reaches [AutocompleteEngine.onUserInput], so the terminal tells the
     * engine what it did to the line instead: the tail after the control that ran it, or nothing
     * known at all when that control was Ctrl-O, which pulls the next history entry into the line.
     */
    @Test
    fun a_line_that_ran_elsewhere_is_replaced_by_what_it_left() {
        val engine = AutocompleteEngine()
        engine.onUserInput("git pu".encodeToByteArray())

        engine.lineRanElsewhere("cd /tmp")
        assertEquals("cd /tmp", engine.currentLine)
        assertFalse(engine.lineSuspect)

        engine.lineRanElsewhere("")
        assertEquals("", engine.currentLine)
        assertFalse(engine.lineSuspect)
    }

    @Test
    fun a_line_whose_new_contents_cannot_be_known_is_suspect() {
        val engine = AutocompleteEngine()
        engine.onUserInput("uptime".encodeToByteArray())

        engine.lineRanElsewhere(null)

        assertEquals("", engine.currentLine)
        assertTrue(engine.lineSuspect)
        assertNull(engine.suggestion())
    }
}
