package app.skerry.ui.files

import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.FileBrowserException
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
    fun `open keeps the old path and entries until the new listing is ready`() = runTest {
        // Регресс: path менялся синхронно, а entries приезжали асинхронно — старый список «висел»
        // под новым путём (и строка «..», зависящая от path, мигала), давая видимый подскок при
        // каждом переходе на desktop и mobile. Навигация обязана быть атомарной: грузим листинг,
        // затем одним снимком меняем path+entries. «Ворота» держат листинг alpha, чтобы поймать
        // промежуточное состояние.
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
        advanceUntilIdle() // дошли до ожидания на воротах: листинг alpha ещё не вернулся

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
        c.mkdir("alpha") // уже существует — обязан упасть
        advanceUntilIdle()
        assertIs<FilePaneState.Error>(c.state)
    }

    // Мультивыделение

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

    // Курсор и клавиатурная навигация (mc-режим)

    @Test
    fun `start places the cursor on the first entry`() = runTest {
        val c = started()
        assertEquals("$HOME/alpha", c.cursor)
    }

    @Test
    fun `moveCursor steps down and up the listing`() = runTest {
        val c = started()
        // порядок: alpha(0), zeta(1), build.log(2), readme.txt(3)
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
        c.moveCursor(-5) // вверх за первый файл — попадаем на «..»
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
        c.cursorToFirst() // самый верх — строка «..»
        assertTrue(c.cursorOnParent)
    }

    @Test
    fun `moveCursor up from the first entry lands on the parent row`() = runTest {
        val c = started() // курсор на alpha
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
        // курсор на alpha
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
        c.moveCursor(1) // не должно падать на пустом листинге
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
        assertTrue("readme.txt" !in names && "build.log" !in names, "ожидали удаления обоих, есть: $names")
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
}
