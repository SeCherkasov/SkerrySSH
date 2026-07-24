package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileBrowserFailure
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val HOME = "/home/skerry"

/** Fake source rooted at HOME, seeded with a mix of directories and files. */
private fun seededBrowser(): SftpFileBrowser {
    val fake = FakeSftpClient(startDir = HOME).apply {
        seedDir("$HOME/zeta")
        seedDir("$HOME/alpha")
        seedFile("$HOME/readme.txt", size = 11)
        seedFile("$HOME/build.log", size = 200)
    }
    return SftpFileBrowser(fake, label = "prod-web-01")
}

/** Same seed plus a nested file under alpha, for testing navigation into a directory. */
private fun seededBrowserWithNested(): SftpFileBrowser {
    val fake = FakeSftpClient(startDir = HOME).apply {
        seedDir("$HOME/zeta")
        seedDir("$HOME/alpha")
        seedFile("$HOME/alpha/inside.txt")
        seedFile("$HOME/readme.txt", size = 11)
        seedFile("$HOME/build.log", size = 200)
    }
    return SftpFileBrowser(fake, label = "prod-web-01")
}

/**
 * Generic pane tests run over the [SftpFileBrowser] adapter atop an in-memory [FakeSftpClient],
 * which also covers the adapter's integration. [UnconfinedTestDispatcher] runs the controller's
 * launch immediately, so state is ready after [advanceUntilIdle].
 */
class FilePaneControllerTest {

    private fun TestScope.controllerOn(browser: SftpFileBrowser): FilePaneController =
        FilePaneController(browser, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    private fun FilePaneController.loaded() = state as FilePaneState.Loaded
    private fun FilePaneController.entry(name: String) = loaded().entries.first { it.name == name }

    private fun TestScope.started(browser: SftpFileBrowser = seededBrowser()): FilePaneController =
        controllerOn(browser).also { it.start(); advanceUntilIdle() }

    @Test
    fun `label is exposed from the browser`() = runTest {
        assertEquals("prod-web-01", controllerOn(seededBrowser()).label)
    }

    @Test
    fun `start loads the start directory with dirs first then files by name`() = runTest {
        val c = started()

        assertEquals(HOME, c.path)
        assertEquals(listOf("alpha", "zeta", "build.log", "readme.txt"), c.loaded().entries.map { it.name })
        assertEquals(FileItemType.Directory, c.loaded().entries.first().type)
    }

    @Test
    fun `open navigates into a directory and lists it`() = runTest {
        val c = started(seededBrowserWithNested())

        c.open(c.entry("alpha"))
        advanceUntilIdle()

        assertEquals("$HOME/alpha", c.path)
        assertEquals(listOf("inside.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `open keeps the old path and entries until the new listing is ready`() = runTest {
        // Navigation must be atomic: load the listing, then update path+entries in one snapshot.
        // The gate holds the alpha listing to catch the intermediate state.
        val base = seededBrowserWithNested()
        val gate = CompletableDeferred<Unit>()
        val gated = object : FileBrowser {
            override val label: String get() = base.label
            override suspend fun realpath(path: String): String = base.realpath(path)
            override suspend fun list(path: String): List<FileItem> {
                if (path == "$HOME/alpha") gate.await()
                return base.list(path)
            }
            override suspend fun mkdir(path: String) = base.mkdir(path)
            override suspend fun delete(item: FileItem) = base.delete(item)
            override suspend fun rename(from: String, to: String) = base.rename(from, to)
        }
        val c = FilePaneController(gated, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.start(); advanceUntilIdle()
        val alpha = c.entry("alpha")

        c.open(alpha)
        advanceUntilIdle() // blocked on the gate: alpha listing hasn't returned yet

        assertEquals(HOME, c.path)
        assertEquals(listOf("alpha", "zeta", "build.log", "readme.txt"), c.loaded().entries.map { it.name })

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("$HOME/alpha", c.path)
        assertEquals(listOf("inside.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `open on a file does not change the path`() = runTest {
        val c = started()
        c.open(c.entry("readme.txt"))
        advanceUntilIdle()
        assertEquals(HOME, c.path)
    }

    @Test
    fun `goUp moves to the parent directory`() = runTest {
        val c = started()
        c.goUp()
        advanceUntilIdle()
        assertEquals("/home", c.path)
        assertTrue(c.loaded().entries.any { it.name == "skerry" })
    }

    @Test
    fun `goUp does not ask the server to canonicalize a dotdot path`() = runTest {
        // Server that rejects REALPATH on "..": realpath throws for any path containing "..".
        // goUp must compute the parent lexically instead of relying on realpath.
        val base = seededBrowserWithNested()
        val noDotDot = object : FileBrowser {
            override val label: String get() = base.label
            override suspend fun realpath(path: String): String {
                if (path.contains("..")) throw FileBrowserException(FileBrowserFailure.Sftp)
                return base.realpath(path)
            }
            override suspend fun list(path: String): List<FileItem> = base.list(path)
            override suspend fun mkdir(path: String) = base.mkdir(path)
            override suspend fun delete(item: FileItem) = base.delete(item)
            override suspend fun rename(from: String, to: String) = base.rename(from, to)
        }
        val c = FilePaneController(noDotDot, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.start(); advanceUntilIdle()

        c.goUp()
        advanceUntilIdle()

        assertEquals("/home", c.path)
        assertIs<FilePaneState.Loaded>(c.state)
        assertTrue(c.loaded().entries.any { it.name == "skerry" })
    }

    @Test
    fun `mkdir creates a directory and shows it`() = runTest {
        val c = started()
        c.mkdir("newdir")
        advanceUntilIdle()
        assertEquals(FileItemType.Directory, c.entry("newdir").type)
    }

    @Test
    fun `mkdir lands the cursor on the new directory`() = runTest {
        val c = started()
        c.mkdir("newdir")
        advanceUntilIdle()
        assertFalse(c.cursorOnParent)
        assertEquals("newdir", c.cursoredItem()?.name)
    }

    @Test
    fun `delete removes a file`() = runTest {
        val c = started()
        c.delete(c.entry("readme.txt"))
        advanceUntilIdle()
        assertTrue(c.loaded().entries.none { it.name == "readme.txt" })
    }

    @Test
    fun `rename changes an entry name`() = runTest {
        val c = started()
        c.rename(c.entry("readme.txt"), "manual.txt")
        advanceUntilIdle()
        val names = c.loaded().entries.map { it.name }
        assertTrue("manual.txt" in names && "readme.txt" !in names)
    }

    @Test
    fun `a failing operation surfaces as Error without crashing`() = runTest {
        val c = started()
        c.mkdir("alpha") // already exists, must fail
        advanceUntilIdle()
        assertIs<FilePaneState.Error>(c.state)
    }

    // Multi-selection

    @Test
    fun `selectOnly selects a single item`() = runTest {
        val c = started()
        c.selectOnly(c.entry("alpha"))
        assertEquals(setOf("$HOME/alpha"), c.selection)
    }

    @Test
    fun `toggle adds and removes from the selection`() = runTest {
        val c = started()
        c.toggle(c.entry("alpha"))
        c.toggle(c.entry("zeta"))
        assertEquals(setOf("$HOME/alpha", "$HOME/zeta"), c.selection)

        c.toggle(c.entry("alpha"))
        assertEquals(setOf("$HOME/zeta"), c.selection)
    }

    @Test
    fun `selectTo selects the range from the anchor`() = runTest {
        val c = started()
        // order: alpha, zeta, build.log, readme.txt
        c.selectOnly(c.entry("alpha"))
        c.selectTo(c.entry("build.log"))
        assertEquals(setOf("$HOME/alpha", "$HOME/zeta", "$HOME/build.log"), c.selection)
    }

    @Test
    fun `selectTo with reversed anchor selects the range upward`() = runTest {
        val c = started()
        // order: alpha(0), zeta(1), build.log(2), readme.txt(3)
        c.selectOnly(c.entry("build.log"))
        c.selectTo(c.entry("alpha"))
        assertEquals(setOf("$HOME/alpha", "$HOME/zeta", "$HOME/build.log"), c.selection)
    }

    @Test
    fun `selectTo without an anchor selects only the target`() = runTest {
        val c = started()
        c.selectTo(c.entry("zeta"))
        assertEquals(setOf("$HOME/zeta"), c.selection)
    }

    @Test
    fun `selectedItems returns the selected entries in display order`() = runTest {
        val c = started()
        c.toggle(c.entry("build.log"))
        c.toggle(c.entry("alpha"))
        // display order: alpha, zeta, build.log, readme.txt; selectedItems follows it
        assertEquals(listOf("alpha", "build.log"), c.selectedItems().map { it.name })
    }

    @Test
    fun `navigation clears the selection`() = runTest {
        val c = started()
        c.selectOnly(c.entry("alpha"))
        c.goUp()
        advanceUntilIdle()
        assertTrue(c.selection.isEmpty())
    }

    @Test
    fun `deleting a selected item drops it from the selection`() = runTest {
        val c = started()
        c.selectOnly(c.entry("readme.txt"))
        c.delete(c.entry("readme.txt"))
        advanceUntilIdle()
        assertTrue("$HOME/readme.txt" !in c.selection)
    }

    // Cursor and keyboard navigation (mc-style)

    @Test
    fun `start places the cursor on the first entry`() = runTest {
        val c = started()
        assertEquals("$HOME/alpha", c.cursor)
    }

    @Test
    fun `moveCursor steps down and up the listing`() = runTest {
        val c = started()
        // order: alpha(0), zeta(1), build.log(2), readme.txt(3)
        c.moveCursor(1)
        assertEquals("$HOME/zeta", c.cursor)
        c.moveCursor(2)
        assertEquals("$HOME/readme.txt", c.cursor)
        c.moveCursor(-1)
        assertEquals("$HOME/build.log", c.cursor)
    }

    @Test
    fun `moveCursor clamps at the bottom and stops on the parent row at the top`() = runTest {
        val c = started()
        c.moveCursor(-5) // up past the first entry lands on ".."
        assertTrue(c.cursorOnParent)
        assertEquals(null, c.cursor)
        c.moveCursor(99)
        assertFalse(c.cursorOnParent)
        assertEquals("$HOME/readme.txt", c.cursor)
    }

    @Test
    fun `cursorToFirst lands on the parent row and cursorToLast on the last entry`() = runTest {
        val c = started()
        c.cursorToLast()
        assertEquals("$HOME/readme.txt", c.cursor)
        c.cursorToFirst() // top of the list is the ".." row
        assertTrue(c.cursorOnParent)
    }

    @Test
    fun `moveCursor up from the first entry lands on the parent row`() = runTest {
        val c = started() // cursor on alpha
        c.moveCursor(-1)
        assertTrue(c.cursorOnParent)
    }

    @Test
    fun `moveCursor down from the parent row returns to the first entry`() = runTest {
        val c = started()
        c.setCursorOnParent()
        c.moveCursor(1)
        assertFalse(c.cursorOnParent)
        assertEquals("$HOME/alpha", c.cursor)
    }

    @Test
    fun `enterCursored on the parent row goes up`() = runTest {
        val c = started()
        c.setCursorOnParent()
        c.enterCursored()
        advanceUntilIdle()
        assertEquals("/home", c.path)
    }

    @Test
    fun `markCursored on the parent row does nothing`() = runTest {
        val c = started()
        c.setCursorOnParent()
        c.markCursored()
        assertTrue(c.selection.isEmpty())
    }

    @Test
    fun `setCursor points at a specific row`() = runTest {
        val c = started()
        c.setCursor(c.entry("build.log"))
        assertEquals("$HOME/build.log", c.cursor)
        assertEquals("build.log", c.cursoredItem()?.name)
    }

    @Test
    fun `navigation resets the cursor to the first row of the new directory`() = runTest {
        val c = started(seededBrowserWithNested())
        c.moveCursor(2)
        c.open(c.entry("alpha"))
        advanceUntilIdle()
        assertEquals("$HOME/alpha/inside.txt", c.cursor)
    }

    @Test
    fun `enterCursored on a directory navigates into it`() = runTest {
        val c = started(seededBrowserWithNested())
        c.setCursor(c.entry("alpha"))
        c.enterCursored()
        advanceUntilIdle()
        assertEquals("$HOME/alpha", c.path)
    }

    @Test
    fun `enterCursored on a file keeps the path`() = runTest {
        val c = started()
        c.setCursor(c.entry("readme.txt"))
        c.enterCursored()
        advanceUntilIdle()
        assertEquals(HOME, c.path)
    }

    @Test
    fun `markCursored toggles the selection of the cursored row`() = runTest {
        val c = started()
        c.setCursor(c.entry("zeta"))
        c.markCursored()
        assertEquals(setOf("$HOME/zeta"), c.selection)
        c.markCursored()
        assertTrue(c.selection.isEmpty())
    }

    @Test
    fun `markCursoredAndAdvance marks the row and moves the cursor down`() = runTest {
        val c = started()
        // cursor on alpha
        c.markCursoredAndAdvance()
        assertEquals(setOf("$HOME/alpha"), c.selection)
        assertEquals("$HOME/zeta", c.cursor)
    }

    @Test
    fun `refresh keeps the cursor when the entry still exists`() = runTest {
        val c = started()
        c.setCursor(c.entry("build.log"))
        c.refresh()
        advanceUntilIdle()
        assertEquals("$HOME/build.log", c.cursor)
    }

    @Test
    fun `deleting the cursored entry leaves the cursor on a valid row`() = runTest {
        val c = started()
        c.setCursor(c.entry("readme.txt"))
        c.delete(c.entry("readme.txt"))
        advanceUntilIdle()
        val present = c.loaded().entries.map { it.path }
        assertTrue(c.cursor in present)
    }

    @Test
    fun `cursor is null in an empty directory`() = runTest {
        val fake = FakeSftpClient(startDir = HOME).apply { seedDir("$HOME/empty") }
        val c = started(SftpFileBrowser(fake, label = "prod-web-01"))
        c.open(c.entry("empty"))
        advanceUntilIdle()
        assertEquals(null, c.cursor)
        c.moveCursor(1) // must not crash on an empty listing
        assertEquals(null, c.cursor)
    }

    @Test
    fun `operands returns the selected entries in display order`() = runTest {
        val c = started()
        c.selectOnly(c.entry("readme.txt"))
        c.toggle(c.entry("alpha"))
        assertEquals(listOf("alpha", "readme.txt"), c.operands().map { it.name })
    }

    @Test
    fun `operands falls back to the cursored entry when nothing is selected`() = runTest {
        val c = started()
        c.setCursor(c.entry("build.log"))
        assertEquals(listOf("build.log"), c.operands().map { it.name })
    }

    @Test
    fun `operands is empty on the parent row with no selection`() = runTest {
        val c = started()
        c.setCursorOnParent()
        assertTrue(c.operands().isEmpty())
    }

    @Test
    fun `deleteSelected removes every selected entry and clears the selection`() = runTest {
        val c = started()
        c.selectOnly(c.entry("readme.txt"))
        c.toggle(c.entry("build.log"))
        c.deleteSelected()
        advanceUntilIdle()
        val names = c.loaded().entries.map { it.name }
        assertTrue("readme.txt" !in names && "build.log" !in names, "expected both deleted, have: $names")
        assertTrue(c.selection.isEmpty())
    }

    @Test
    fun `deleteSelected falls back to the cursored entry when nothing is selected`() = runTest {
        val c = started()
        c.setCursor(c.entry("alpha"))
        c.deleteSelected()
        advanceUntilIdle()
        assertTrue("alpha" !in c.loaded().entries.map { it.name })
    }

    @Test
    fun `deleteSelected on the parent row with no selection is a no-op`() = runTest {
        val c = started()
        c.setCursorOnParent()
        c.deleteSelected()
        advanceUntilIdle()
        assertEquals(listOf("alpha", "zeta", "build.log", "readme.txt"), c.loaded().entries.map { it.name })
    }

    // Rubber-band (mc right-click drag select): dragging paints the range with one sign.

    @Test
    fun `rubberBandTo with select paints the range from anchor to current`() = runTest {
        val c = started()
        // order: alpha(0), zeta(1), build.log(2), readme.txt(3)
        c.rubberBandTo(c.entry("alpha"), c.entry("build.log"), select = true)
        assertEquals(setOf("$HOME/alpha", "$HOME/zeta", "$HOME/build.log"), c.selection)
    }

    @Test
    fun `rubberBandTo paints the range regardless of drag direction`() = runTest {
        val c = started()
        c.rubberBandTo(c.entry("readme.txt"), c.entry("zeta"), select = true)
        assertEquals(setOf("$HOME/zeta", "$HOME/build.log", "$HOME/readme.txt"), c.selection)
    }

    @Test
    fun `rubberBandTo with select accumulates onto an existing selection`() = runTest {
        // Unlike selectTo (which replaces the range), rubber-band paints on top of the existing selection.
        val c = started()
        c.selectOnly(c.entry("readme.txt"))
        c.rubberBandTo(c.entry("alpha"), c.entry("zeta"), select = true)
        assertEquals(setOf("$HOME/alpha", "$HOME/zeta", "$HOME/readme.txt"), c.selection)
    }

    @Test
    fun `rubberBandTo without select erases the range and keeps the rest`() = runTest {
        val c = started()
        c.rubberBandTo(c.entry("alpha"), c.entry("readme.txt"), select = true) // everything selected
        c.rubberBandTo(c.entry("zeta"), c.entry("build.log"), select = false)
        assertEquals(setOf("$HOME/alpha", "$HOME/readme.txt"), c.selection)
    }

    @Test
    fun `rubberBandTo on a single row toggles that one row by the chosen sign`() = runTest {
        val c = started()
        c.rubberBandTo(c.entry("zeta"), c.entry("zeta"), select = true)
        assertEquals(setOf("$HOME/zeta"), c.selection)
    }

    // Hiding hidden files/directories (dotfiles), as in mc.

    @Test
    fun `setShowHidden false hides dotfiles and true brings them back`() = runTest {
        val fake = FakeSftpClient(startDir = HOME).apply {
            seedDir("$HOME/.config")
            seedDir("$HOME/visible")
            seedFile("$HOME/.bashrc")
            seedFile("$HOME/notes.txt")
        }
        val c = started(SftpFileBrowser(fake, label = "h"))
        c.setShowHidden(false)
        assertEquals(listOf("visible", "notes.txt"), c.loaded().entries.map { it.name })
        c.setShowHidden(true)
        assertEquals(listOf(".config", "visible", ".bashrc", "notes.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `hiding moves the cursor off a now-hidden entry`() = runTest {
        val fake = FakeSftpClient(startDir = HOME).apply {
            seedDir("$HOME/visible")
            seedFile("$HOME/.secret")
        }
        val c = started(SftpFileBrowser(fake, label = "h"))
        c.setCursor(c.entry(".secret"))
        c.setShowHidden(false)
        assertEquals("$HOME/visible", c.cursor)
    }

    @Test
    fun `hiding drops hidden entries from the selection`() = runTest {
        val fake = FakeSftpClient(startDir = HOME).apply {
            seedFile("$HOME/.secret")
            seedFile("$HOME/keep.txt")
        }
        val c = started(SftpFileBrowser(fake, label = "h"))
        c.selectOnly(c.entry(".secret"))
        c.toggle(c.entry("keep.txt"))
        c.setShowHidden(false)
        assertEquals(setOf("$HOME/keep.txt"), c.selection)
    }

    // Name filter over the cached listing (no source query), like the mc/Total Commander quick filter.

    @Test
    fun `setNameFilter narrows the listing and clearing brings it back`() = runTest {
        val c = started()

        c.setNameFilter("read")
        assertEquals(listOf("readme.txt"), c.loaded().entries.map { it.name })

        c.setNameFilter("")
        assertEquals(listOf("alpha", "zeta", "build.log", "readme.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `name filter accepts a glob mask`() = runTest {
        val c = started()

        c.setNameFilter("*.log")

        assertEquals(listOf("build.log"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `name filter combines with the hidden toggle`() = runTest {
        val fake = FakeSftpClient(startDir = HOME).apply {
            seedFile("$HOME/.notes.txt")
            seedFile("$HOME/notes.txt")
            seedFile("$HOME/build.log")
        }
        val c = started(SftpFileBrowser(fake, label = "h"))

        c.setShowHidden(false)
        c.setNameFilter("notes")

        assertEquals(listOf("notes.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `filtering moves the cursor off a filtered-out entry and prunes selection`() = runTest {
        val c = started()
        c.setCursor(c.entry("zeta"))
        c.selectOnly(c.entry("zeta"))

        c.setNameFilter("read")

        assertEquals("$HOME/readme.txt", c.cursor)
        assertEquals(emptySet(), c.selection)
    }

    @Test
    fun `navigation clears the name filter`() = runTest {
        val c = started(seededBrowserWithNested())
        val alpha = c.entry("alpha")
        c.setNameFilter("*.log")

        c.open(alpha)
        advanceUntilIdle()

        assertEquals("", c.nameFilter)
        assertEquals(listOf("inside.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `refresh keeps the name filter applied`() = runTest {
        val c = started()
        c.setNameFilter("*.log")

        c.refresh()
        advanceUntilIdle()

        assertEquals("*.log", c.nameFilter)
        assertEquals(listOf("build.log"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `rubberBandTo is a no-op when an endpoint is not in the listing`() = runTest {
        val c = started()
        c.selectOnly(c.entry("alpha"))
        val ghost = FileItem(name = "ghost", path = "$HOME/ghost", type = FileItemType.File, size = 0, modifiedEpochSeconds = 0)
        c.rubberBandTo(c.entry("zeta"), ghost, select = true)
        assertEquals(setOf("$HOME/alpha"), c.selection)
    }

    @Test
    fun `entering an empty directory puts the cursor on the parent row`() = runTest {
        val c = started() // alpha is seeded as an empty directory
        c.open(c.entry("alpha"))
        advanceUntilIdle()

        assertEquals("$HOME/alpha", c.path)
        assertTrue(c.loaded().entries.isEmpty())
        assertTrue(c.cursorOnParent)
        assertEquals(null, c.cursor)
    }

    @Test
    fun `deleting the last entry moves the cursor to the parent row`() = runTest {
        val c = started(seededBrowserWithNested())
        c.open(c.entry("alpha")) // contains a single inside.txt
        advanceUntilIdle()

        c.selectOnly(c.entry("inside.txt"))
        c.deleteSelected()
        advanceUntilIdle()

        assertTrue(c.loaded().entries.isEmpty())
        assertTrue(c.cursorOnParent)
        assertEquals(null, c.cursor)
    }

    // Path-bar jump (goToPath): type a path to jump straight to it.

    @Test
    fun `goToPath jumps to an absolute directory and lists it`() = runTest {
        val c = started(seededBrowserWithNested())
        c.goToPath("$HOME/alpha")
        advanceUntilIdle()
        assertEquals("$HOME/alpha", c.path)
        assertEquals(listOf("inside.txt"), c.loaded().entries.map { it.name })
    }

    @Test
    fun `goToPath resolves a relative path against the current directory`() = runTest {
        val c = started(seededBrowserWithNested())
        c.goToPath("alpha")
        advanceUntilIdle()
        assertEquals("$HOME/alpha", c.path)
    }

    @Test
    fun `goToPath normalizes dotdot segments`() = runTest {
        val c = started(seededBrowserWithNested())
        c.open(c.entry("alpha")); advanceUntilIdle()
        c.goToPath("..")
        advanceUntilIdle()
        assertEquals(HOME, c.path)
    }

    @Test
    fun `goToPath treats a Windows drive-letter path as absolute`() = runTest {
        // The desktop local pane on Windows works with drive-letter paths; resolving them against
        // the current directory would produce garbage like "/home/skerry/C:\Temp".
        val base = seededBrowserWithNested()
        var requested: String? = null
        val capturing = object : FileBrowser {
            override val label: String get() = base.label
            override suspend fun realpath(path: String): String {
                requested = path
                return if (path == "C:\\Temp") "$HOME/alpha" else base.realpath(path)
            }
            override suspend fun list(path: String): List<FileItem> = base.list(path)
            override suspend fun mkdir(path: String) = base.mkdir(path)
            override suspend fun delete(item: FileItem) = base.delete(item)
            override suspend fun rename(from: String, to: String) = base.rename(from, to)
        }
        val c = FilePaneController(capturing, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.start(); advanceUntilIdle()

        c.goToPath("C:\\Temp")
        advanceUntilIdle()

        assertEquals("C:\\Temp", requested)
        assertEquals("$HOME/alpha", c.path)
    }

    @Test
    fun `goToPath does not ask the server to canonicalize a dotdot path`() = runTest {
        // Same server constraint as goUp: some SFTP servers reject REALPATH on paths containing
        // "..", so dot segments must be collapsed lexically before the round-trip.
        val base = seededBrowserWithNested()
        val noDotDot = object : FileBrowser {
            override val label: String get() = base.label
            override suspend fun realpath(path: String): String {
                if (path.contains("..")) throw FileBrowserException(FileBrowserFailure.Sftp)
                return base.realpath(path)
            }
            override suspend fun list(path: String): List<FileItem> = base.list(path)
            override suspend fun mkdir(path: String) = base.mkdir(path)
            override suspend fun delete(item: FileItem) = base.delete(item)
            override suspend fun rename(from: String, to: String) = base.rename(from, to)
        }
        val c = FilePaneController(noDotDot, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        c.start(); advanceUntilIdle()
        c.open(c.entry("alpha")); advanceUntilIdle()

        c.goToPath("..")
        advanceUntilIdle()
        assertEquals(HOME, c.path)
        assertIs<FilePaneState.Loaded>(c.state)

        c.goToPath("alpha/../zeta")
        advanceUntilIdle()
        assertEquals("$HOME/zeta", c.path)
        assertIs<FilePaneState.Loaded>(c.state)
    }

    @Test
    fun `goToPath to a missing directory surfaces Error and keeps the current directory`() = runTest {
        val c = started()
        c.goToPath("$HOME/nope")
        advanceUntilIdle()
        assertIs<FilePaneState.Error>(c.state)
        assertEquals(HOME, c.path)
    }

    @Test
    fun `goToPath ignores blank input`() = runTest {
        val c = started()
        c.goToPath("   ")
        advanceUntilIdle()
        assertEquals(HOME, c.path)
        assertIs<FilePaneState.Loaded>(c.state)
    }

    @Test
    fun `goToPath clears the selection`() = runTest {
        val c = started(seededBrowserWithNested())
        c.selectOnly(c.entry("readme.txt"))
        c.goToPath("$HOME/alpha")
        advanceUntilIdle()
        assertTrue(c.selection.isEmpty())
    }
}
