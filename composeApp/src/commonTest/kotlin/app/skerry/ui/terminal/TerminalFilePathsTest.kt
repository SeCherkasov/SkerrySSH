package app.skerry.ui.terminal

import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalFilePathsTest {

    private fun row(text: String): List<TermCell> = text.map { TermCell(it) }

    // --- Detection in plain text ---

    @Test
    fun `detects an absolute path in surrounding text`() {
        val text = "tail -f /var/log/syslog now"
        val hit = detectFilePaths(text).single()
        assertEquals("/var/log/syslog", hit.uri)
        assertEquals("/var/log/syslog", text.substring(hit.start, hit.endExclusive))
    }

    @Test
    fun `detects several paths on one line`() {
        assertEquals(
            listOf("/etc/hosts", "/tmp/out.txt"),
            detectFilePaths("cp /etc/hosts /tmp/out.txt").map { it.uri },
        )
    }

    @Test
    fun `detects a home-relative path`() {
        assertEquals("~/.ssh/config", detectFilePaths("edit ~/.ssh/config please").single().uri)
        // A bare "~" is a real directory reference (home) and worth opening.
        assertEquals("~", detectFilePaths("cd ~").single().uri)
    }

    @Test
    fun `ignores relative paths without an anchor`() {
        // No cwd tracking (no OSC 7), so a relative path cannot be resolved reliably — v1 skips it
        // rather than opening the wrong directory.
        assertTrue(detectFilePaths("open src/main/kotlin/App.kt").isEmpty())
        assertTrue(detectFilePaths("run ./build.sh and ../other").isEmpty())
    }

    @Test
    fun `ignores a lone slash and slash-only tokens`() {
        assertTrue(detectFilePaths("a / b").isEmpty())
        assertTrue(detectFilePaths("// comment").isEmpty())
        assertTrue(detectFilePaths("/**").isEmpty())
    }

    @Test
    fun `does not detect the path part of a url`() {
        assertTrue(detectFilePaths("see https://skerry.app/docs/index.html").isEmpty())
        assertTrue(detectFilePaths("ftp://files.example.com/pub/x").isEmpty())
    }

    @Test
    fun `does not detect a path glued to a preceding word`() {
        // Only a token boundary starts a path: "a=/b" is a shell assignment, "x/y/z" is relative.
        assertTrue(detectFilePaths("HOST=/dev/null").isEmpty())
        assertTrue(detectFilePaths("root:x:0:0::/root:/bin/bash").isEmpty())
    }

    @Test
    fun `starts a path after an opening quote or bracket`() {
        assertEquals("/etc/hosts", detectFilePaths("\"/etc/hosts\"").single().uri)
        assertEquals("/etc/hosts", detectFilePaths("('/etc/hosts')").single().uri)
    }

    @Test
    fun `trims trailing sentence punctuation`() {
        assertEquals("/etc/hosts", detectFilePaths("look at /etc/hosts.").single().uri)
        assertEquals("/etc/hosts", detectFilePaths("(see /etc/hosts)").single().uri)
        assertEquals("/etc/hosts", detectFilePaths("'/etc/hosts',").single().uri)
    }

    @Test
    fun `trims a compiler or grep line-number suffix`() {
        assertEquals("/src/main.kt", detectFilePaths("/src/main.kt:42:7: error").single().uri)
        assertEquals("/var/log/syslog", detectFilePaths("/var/log/syslog:15:oom killer").single().uri)
    }

    @Test
    fun `keeps a colon that belongs to the file name`() {
        // ':' is legal in a POSIX name and common in timestamped ones — only a real line marker is cut.
        assertEquals(
            "/backups/2026-07-26T03:00:00.tar.gz",
            detectFilePaths("ls -l /backups/2026-07-26T03:00:00.tar.gz").single().uri,
        )
        // A name with a colon AND a trailing line marker: only the marker goes.
        assertEquals("/a:2026.log", detectFilePaths("/a:2026.log:15: warning").single().uri)
    }

    @Test
    fun `keeps a trailing slash of a directory path`() {
        assertEquals("/var/log/", detectFilePaths("ls /var/log/").single().uri)
    }

    @Test
    fun `rejects control characters inside a path`() {
        val esc = 27.toChar()
        assertTrue(detectFilePaths("/etc/${esc}hosts").isEmpty())
    }

    @Test
    fun `rejects a path carrying invisible characters`() {
        // A server could hide a bidi override or zero-width mark so the glyphs read as one directory
        // while the string handed to SFTP is another.
        assertTrue(detectFilePaths("/etc/\u202Ehosts").isEmpty())
        assertTrue(detectFilePaths("/etc/\u200Bhosts").isEmpty())
        assertNull(filePathFromSelection("/var/\u2066log"))
    }

    @Test
    fun `rejects an implausibly long path`() {
        assertTrue(detectFilePaths("/" + "a".repeat(5000)).isEmpty())
    }

    // --- Real output lines: what the detector must and must not offer ---

    @Test
    fun `ls -l offers only the entry name column when it is absolute`() {
        // A plain listing has no absolute paths at all — nothing should light up.
        assertTrue(detectFilePaths("-rw-r--r--  1 root root  1.2K Jul 26 09:12 nginx.conf").isEmpty())
        // `ls -l /etc/nginx/` prints the directory as a header line: that one is a real path.
        assertEquals("/etc/nginx/", detectFilePaths("/etc/nginx/:").single().uri)
    }

    @Test
    fun `systemd and log lines offer the unit path but not the timestamp`() {
        val line = "Jul 26 09:12:01 host nginx[123]: config /etc/nginx/nginx.conf test failed"
        assertEquals(listOf("/etc/nginx/nginx.conf"), detectFilePaths(line).map { it.uri })
    }

    @Test
    fun `a json body does not turn into paths`() {
        // Quoted values start at a token boundary, so they are offered; a URL inside is not.
        val line = """{"log": "https://a.test/x", "ratio": 3/4, "at": "2026/07/26"}"""
        assertTrue(detectFilePaths(line).isEmpty())
    }

    @Test
    fun `a quoted absolute path inside json is offered without the quote`() {
        assertEquals("/var/lib/app.db", detectFilePaths("""{"db": "/var/lib/app.db"}""").single().uri)
    }

    // --- Grid mapping and hit testing ---

    @Test
    fun `maps a path onto its grid columns`() {
        val cells = row("x /etc/hosts y")
        val span = rowFilePathSpans(cells).single()
        assertEquals(2, span.start)
        assertEquals(2 + "/etc/hosts".length, span.endExclusive)
        assertEquals("/etc/hosts", span.uri)
    }

    @Test
    fun `filePathSpanAt returns the path only under its columns`() {
        val cells = row("cat /etc/hosts")
        assertNull(filePathSpanAt(cells, 0))
        assertEquals("/etc/hosts", filePathSpanAt(cells, 4)?.uri)
        assertEquals("/etc/hosts", filePathSpanAt(cells, cells.lastIndex)?.uri)
    }

    @Test
    fun `wide cells shift columns so mapping still lands on the path`() {
        val cells = buildList {
            add(TermCell("世", width = CellWidth.Wide))
            add(TermCell("", width = CellWidth.Continuation))
            add(TermCell(' '))
            "/etc/hosts".forEach { add(TermCell(it)) }
        }
        val span = rowFilePathSpans(cells).single()
        assertEquals(3, span.start)
        assertEquals("/etc/hosts", filePathSpanAt(cells, 3)?.uri)
    }

    @Test
    fun `a path with a concealed segment offers nothing`() {
        // Visible "/var/log" + concealed "/../secret": SFTP would be handed a path the user never
        // saw, so a span covering any hidden cell is dropped whole - clicks on the visible prefix
        // included.
        val hidden = TermStyle(hidden = true)
        val cells = "see ".map { TermCell(it) } +
            "/var/log".map { TermCell(it) } +
            "/../secret".map { TermCell(it, hidden) }
        assertTrue(rowFilePathSpans(cells).isEmpty())
        assertNull(filePathSpanAt(cells, 5))
    }

    @Test
    fun `a concealed cell offers no path to open`() {
        // SGR 8: the pointed cell is invisible, so it must not be a click target.
        val cells = "see /etc/hosts".map { TermCell(it, TermStyle(hidden = true)) }
        assertNull(filePathSpanAt(cells, 6))
    }

    @Test
    fun `rows without a slash yield no spans`() {
        assertTrue(rowFilePathSpans(row("total 42  drwxr-xr-x root root")).isEmpty())
    }

    @Test
    fun `a cell already carrying an osc8 hyperlink is not offered as a path`() {
        // OSC 8 wins at the click layer; reporting a path there too would underline the same cells twice.
        val cells = "/etc/hosts".map { TermCell(it.toString(), hyperlink = "https://a.test") }
        assertTrue(rowFilePathSpans(cells).isEmpty())
    }

    // --- Selection (touch path) ---

    @Test
    fun `selection that is exactly one path is offered`() {
        assertEquals("/var/log/syslog", filePathFromSelection("/var/log/syslog"))
        assertEquals("/var/log/syslog", filePathFromSelection("  /var/log/syslog \n"))
        assertEquals("~/.ssh/config", filePathFromSelection("~/.ssh/config"))
    }

    @Test
    fun `selection with extra words or no path is not offered`() {
        assertNull(filePathFromSelection("tail -f /var/log/syslog"))
        assertNull(filePathFromSelection("just text"))
        assertNull(filePathFromSelection(null))
        assertNull(filePathFromSelection(""))
    }

    // --- file:// hyperlinks (OSC 8, `ls --hyperlink`) ---

    @Test
    fun `a file URI names its path whatever the host`() {
        assertEquals("/etc/hosts", fileUriPath("file://web-01/etc/hosts"))
        assertEquals("/etc/hosts", fileUriPath("file:///etc/hosts"))
        assertEquals("/etc/hosts", fileUriPath("FILE://web-01/etc/hosts"))
        assertEquals("/etc/hosts", fileUriPath("file://user@web-01/etc/hosts"))
    }

    @Test
    fun `a file URI is percent-decoded as UTF-8`() {
        assertEquals("/home/u/my notes/отчёт.txt", fileUriPath("file://h/home/u/my%20notes/%D0%BE%D1%82%D1%87%D1%91%D1%82.txt"))
        assertEquals("/tmp/a%b", fileUriPath("file://h/tmp/a%25b"))
        // A literal character outside the BMP next to an escape stays one character.
        assertEquals("/tmp/😀 x", fileUriPath("file://h/tmp/😀%20x"))
        assertEquals("/", fileUriPath("file://h/"))
    }

    @Test
    fun `a path carrying format or C1 characters is not offered`() {
        // Arabic letter mark, word joiner, soft hyphen, CSI (C1): invisible, and able to reorder or pad
        // what the chip shows.
        for (hidden in listOf("%D8%9C", "%E2%81%A0", "%C2%AD", "%C2%9B")) {
            assertNull(fileUriPath("file://h/srv/a${hidden}b"), hidden)
        }
        assertNull(normalizeFilePath("/srv/a\u2060b"))
        // Format characters outside the BMP (Unicode tags) arrive as surrogate pairs.
        assertNull(fileUriPath("file://h/etc/pass%F3%A0%80%A0wd"))
        assertNull(normalizeFilePath("/etc/pass\uDB40\uDC20wd"))
        // Variation selectors, Mongolian free variation selectors and the grapheme joiner are marks, not
        // format characters, yet render as nothing on their own.
        for (hidden in listOf("%EF%B8%8F", "%E1%A0%8B", "%CD%8F")) {
            assertNull(fileUriPath("file://h/etc/pass${hidden}wd"), hidden)
        }
    }

    @Test
    fun `a malformed or hostile file URI names nothing`() {
        assertNull(fileUriPath("https://example.com/etc/hosts"))
        assertNull(fileUriPath("file://host"))
        assertNull(fileUriPath("file://h/tmp/%zz"))
        assertNull(fileUriPath("file://h/tmp/%4"))
        assertNull(fileUriPath("file://h/tmp/%FF%FE"))
        assertNull(fileUriPath("file://h/tmp/a%0Ab"))
        assertNull(fileUriPath("file://h/tmp/a%00b"))
        assertNull(fileUriPath("file://h/tmp/%E2%80%AEtxt.sh"))
        assertNull(fileUriPath("file://h/tmp/a?b"))
        assertNull(fileUriPath("file://h/tmp/a#b"))
        assertNull(fileUriPath("file://h/" + "a".repeat(5000)))
    }

    private fun linkRow(prefix: String, uri: String, text: String, suffix: String = ""): List<TermCell> =
        row(prefix) + text.map { TermCell(it.toString(), hyperlink = uri) } + row(suffix)

    @Test
    fun `a file hyperlink is a path over its whole run`() {
        val row = linkRow("-rw ", "file://h/etc/hosts", "hosts", " x")
        val hit = filePathSpanAt(row, 6)!!
        assertEquals("/etc/hosts", hit.uri)
        assertEquals(4, hit.start)
        assertEquals(9, hit.endExclusive)
        assertNull(filePathSpanAt(row, 3))
    }

    @Test
    fun `a web or concealed hyperlink is not a path`() {
        assertNull(filePathSpanAt(linkRow("", "https://example.com/etc/hosts", "hosts"), 1))
        val hidden = List(5) { TermCell("h", style = TermStyle(hidden = true), hyperlink = "file://h/etc/hosts") }
        assertNull(filePathSpanAt(hidden, 1))
    }

    @Test
    fun `a selection inside one file hyperlink names its path`() {
        val screen = listOf(linkRow("-rw ", "file://h/etc/hosts", "hosts", " x"))
        assertEquals("/etc/hosts", fileLinkPathOfSelection(screen, TerminalSelection(TerminalPos(0, 4), TerminalPos(0, 9))))
        assertEquals("/etc/hosts", fileLinkPathOfSelection(screen, TerminalSelection(TerminalPos(0, 5), TerminalPos(0, 7))))
        // Starting before the link, reaching past its end, spanning rows, or empty: nothing.
        assertNull(fileLinkPathOfSelection(screen, TerminalSelection(TerminalPos(0, 3), TerminalPos(0, 9))))
        assertNull(fileLinkPathOfSelection(screen, TerminalSelection(TerminalPos(0, 5), TerminalPos(0, 11))))
        assertNull(fileLinkPathOfSelection(screen + screen, TerminalSelection(TerminalPos(0, 5), TerminalPos(1, 6))))
        assertNull(fileLinkPathOfSelection(screen, TerminalSelection(TerminalPos(0, 5), TerminalPos(0, 5))))
    }

    @Test
    fun `a selection over a concealed part of a file hyperlink names nothing`() {
        val uri = "file://h/etc/hosts"
        val row = List(5) { i -> TermCell("h", style = TermStyle(hidden = i == 3), hyperlink = uri) }
        assertEquals("/etc/hosts", fileLinkPathOfSelection(listOf(row), TerminalSelection(TerminalPos(0, 0), TerminalPos(0, 3))))
        assertNull(fileLinkPathOfSelection(listOf(row), TerminalSelection(TerminalPos(0, 0), TerminalPos(0, 5))))
    }
}
