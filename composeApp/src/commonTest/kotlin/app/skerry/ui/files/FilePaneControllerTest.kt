package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
import app.skerry.shared.files.FileItem
import app.skerry.shared.files.FileItemType
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val HOME = "/home/skerry"

/** Фейк-источник со стартовым каталогом HOME, засеянным каталогами и файлами вперемешку. */
private fun seededBrowser(): SftpFileBrowser {
    val fake = FakeSftpClient(startDir = HOME).apply {
        seedDir("$HOME/zeta")
        seedDir("$HOME/alpha")
        seedFile("$HOME/readme.txt", size = 11)
        seedFile("$HOME/build.log", size = 200)
    }
    return SftpFileBrowser(fake, label = "prod-web-01")
}

/** Тот же посев плюс вложенный файл в alpha — для теста навигации внутрь каталога. */
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
 * Тесты обобщённой панели идут поверх адаптера [SftpFileBrowser] над in-memory [FakeSftpClient] —
 * заодно покрывают интеграцию адаптера. [UnconfinedTestDispatcher] выполняет launch контроллера
 * немедленно, так что после [advanceUntilIdle] состояние готово.
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
        // Сервер, который не умеет REALPATH с ".." (как в баге «Не удалось разрешить путь /root/..»):
        // realpath любого пути с ".." бросает. goUp обязан вычислить родителя лексически и не упасть.
        val base = seededBrowserWithNested()
        val noDotDot = object : FileBrowser {
            override val label: String get() = base.label
            override suspend fun realpath(path: String): String {
                if (path.contains("..")) throw FileBrowserException("REALPATH с .. не поддержан")
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
        c.mkdir("alpha") // уже существует — обязан упасть
        advanceUntilIdle()
        assertIs<FilePaneState.Error>(c.state)
    }

    // ---- мультивыделение ----

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
        // порядок: alpha, zeta, build.log, readme.txt
        c.selectOnly(c.entry("alpha"))
        c.selectTo(c.entry("build.log"))
        assertEquals(setOf("$HOME/alpha", "$HOME/zeta", "$HOME/build.log"), c.selection)
    }

    @Test
    fun `selectTo with reversed anchor selects the range upward`() = runTest {
        val c = started()
        // порядок: alpha(0), zeta(1), build.log(2), readme.txt(3)
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
        // порядок отображения: alpha, zeta, build.log, readme.txt — selectedItems следует ему
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
}
