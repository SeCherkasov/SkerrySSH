package app.skerry.ui.sftp

import app.skerry.shared.sftp.SftpEntryType
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

/** Фейк со стартовым каталогом HOME, засеянным каталогами и файлами вперемешку. */
private fun seededFake(): FakeSftpClient = FakeSftpClient(startDir = HOME).apply {
    seedDir("$HOME/zeta")
    seedDir("$HOME/alpha")
    seedFile("$HOME/readme.txt", size = 11)
    seedFile("$HOME/build.log", size = 200)
}

class SftpControllerTest {

    // Как в ConnectionControllerTest: UnconfinedTestDispatcher выполняет launch контроллера
    // немедленно (до первой приостановки), так что после advanceUntilIdle состояние готово.
    private fun TestScope.controllerOn(fake: FakeSftpClient): SftpController =
        SftpController(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `start loads the start directory with dirs first then files by name`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        assertEquals(HOME, controller.path)
        val loaded = assertIs<SftpPaneState.Loaded>(controller.state)
        assertEquals(listOf("alpha", "zeta", "build.log", "readme.txt"), loaded.entries.map { it.name })
        assertEquals(SftpEntryType.Directory, loaded.entries.first().type)
    }

    @Test
    fun `open navigates into a directory and lists it`() = runTest {
        val fake = seededFake().apply { seedFile("$HOME/alpha/inside.txt") }
        val controller = controllerOn(fake)
        controller.start()
        advanceUntilIdle()

        val alpha = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "alpha" }
        controller.open(alpha)
        advanceUntilIdle()

        assertEquals("$HOME/alpha", controller.path)
        assertEquals(listOf("inside.txt"), (controller.state as SftpPaneState.Loaded).entries.map { it.name })
    }

    @Test
    fun `open on a file does not change the path`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        val file = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "readme.txt" }
        controller.open(file)
        advanceUntilIdle()

        assertEquals(HOME, controller.path)
    }

    @Test
    fun `goUp moves to the parent directory`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        controller.goUp()
        advanceUntilIdle()

        assertEquals("/home", controller.path)
        assertTrue((controller.state as SftpPaneState.Loaded).entries.any { it.name == "skerry" })
    }

    @Test
    fun `refresh picks up an externally added file`() = runTest {
        val fake = seededFake()
        val controller = controllerOn(fake)
        controller.start()
        advanceUntilIdle()

        fake.seedFile("$HOME/late.txt")
        controller.refresh()
        advanceUntilIdle()

        assertTrue((controller.state as SftpPaneState.Loaded).entries.any { it.name == "late.txt" })
    }

    @Test
    fun `mkdir creates a directory and shows it in the listing`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        controller.mkdir("newdir")
        advanceUntilIdle()

        val entries = (controller.state as SftpPaneState.Loaded).entries
        val created = entries.first { it.name == "newdir" }
        assertEquals(SftpEntryType.Directory, created.type)
    }

    @Test
    fun `delete removes a file`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        val file = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "readme.txt" }
        controller.delete(file)
        advanceUntilIdle()

        assertTrue((controller.state as SftpPaneState.Loaded).entries.none { it.name == "readme.txt" })
    }

    @Test
    fun `delete removes an empty directory`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        val dir = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "alpha" }
        controller.delete(dir)
        advanceUntilIdle()

        assertTrue((controller.state as SftpPaneState.Loaded).entries.none { it.name == "alpha" })
    }

    @Test
    fun `rename changes an entry name`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        val file = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "readme.txt" }
        controller.rename(file, "manual.txt")
        advanceUntilIdle()

        val names = (controller.state as SftpPaneState.Loaded).entries.map { it.name }
        assertTrue("manual.txt" in names && "readme.txt" !in names)
    }

    @Test
    fun `a failing operation surfaces as Error without crashing`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        // alpha уже существует — повторный mkdir обязан упасть и перейти в Error.
        controller.mkdir("alpha")
        advanceUntilIdle()

        assertIs<SftpPaneState.Error>(controller.state)
    }
}
