package app.skerry.ui.sftp

import app.skerry.shared.sftp.SftpEntryType
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

/** Фейк со стартовым каталогом HOME, засеянным каталогами и файлами вперемешку. */
private fun seededFake(): FakeSftpClient = FakeSftpClient(startDir = HOME).apply {
    seedDir("$HOME/zeta")
    seedDir("$HOME/alpha")
    seedFile("$HOME/readme.txt", size = 11)
    seedFile("$HOME/build.log", size = 200)
}

/** Handle цели скачивания для тестов: пишет staging, помнит вызовы finalize/discard. */
private class FakeDownloadTarget(
    override val displayName: String,
    override val stagingPath: String,
) : DownloadTarget {
    var finalized = false
        private set
    var discarded = false
        private set

    override suspend fun finalize() {
        finalized = true
    }

    override suspend fun discard() {
        discarded = true
    }
}

/** Handle источника загрузки для тестов: помнит вызов cleanup. */
private class FakeUploadSource(
    override val name: String,
    override val stagingPath: String,
) : UploadSource {
    var cleanedUp = false
        private set

    override suspend fun cleanup() {
        cleanedUp = true
    }
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
    fun `download records the call finalizes the target and returns transfer to Idle`() = runTest {
        val fake = seededFake()
        val controller = controllerOn(fake)
        controller.start()
        advanceUntilIdle()

        val file = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "readme.txt" }
        val target = FakeDownloadTarget("readme.txt", "/local/readme.txt")
        controller.download(file, target)
        advanceUntilIdle()

        assertEquals(SftpTransferState.Idle, controller.transfer)
        assertEquals("$HOME/readme.txt" to "/local/readme.txt", fake.lastDownload)
        assertTrue(target.finalized)
        assertFalse(target.discarded)
    }

    @Test
    fun `download exposes progress while transferring`() = runTest {
        val fake = seededFake()
        val gate = CompletableDeferred<Unit>()
        fake.transferGate = gate
        val controller = controllerOn(fake)
        controller.start()
        advanceUntilIdle()

        val file = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "readme.txt" }
        controller.download(file, FakeDownloadTarget("readme.txt", "/local/readme.txt"))
        advanceUntilIdle() // дойдёт до приостановки на шлюзе — после первого колбэка прогресса

        val active = assertIs<SftpTransferState.Active>(controller.transfer)
        assertEquals(TransferDirection.Download, active.direction)
        assertEquals(11L, active.total)
        assertEquals(5L, active.transferred) // 11/2

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SftpTransferState.Idle, controller.transfer)
    }

    @Test
    fun `download of a directory discards the target and surfaces Failed without changing the listing`() = runTest {
        val controller = controllerOn(seededFake())
        controller.start()
        advanceUntilIdle()

        val dir = (controller.state as SftpPaneState.Loaded).entries.first { it.name == "alpha" }
        val target = FakeDownloadTarget("alpha", "/local/alpha")
        controller.download(dir, target)
        advanceUntilIdle()

        assertIs<SftpTransferState.Failed>(controller.transfer)
        assertTrue(target.discarded)
        assertFalse(target.finalized)
        // Листинг не должен превратиться в Error из-за неудачной передачи.
        assertIs<SftpPaneState.Loaded>(controller.state)
    }

    @Test
    fun `upload creates the remote file cleans up the source shows it in the listing and ends Idle`() = runTest {
        val fake = seededFake().apply { uploadSize = 8 }
        val controller = controllerOn(fake)
        controller.start()
        advanceUntilIdle()

        val source = FakeUploadSource("new.bin", "/local/new.bin")
        controller.upload(source)
        advanceUntilIdle()

        assertEquals(SftpTransferState.Idle, controller.transfer)
        assertEquals("/local/new.bin" to "$HOME/new.bin", fake.lastUpload)
        assertTrue(source.cleanedUp)
        assertTrue((controller.state as SftpPaneState.Loaded).entries.any { it.name == "new.bin" })
    }

    @Test
    fun `upload cleans up the source and surfaces Failed when the transfer fails`() = runTest {
        val fake = seededFake().apply { uploadError = "диск переполнен" }
        val controller = controllerOn(fake)
        controller.start()
        advanceUntilIdle()

        val source = FakeUploadSource("new.bin", "/local/new.bin")
        controller.upload(source)
        advanceUntilIdle()

        assertIs<SftpTransferState.Failed>(controller.transfer)
        assertTrue(source.cleanedUp)
        // Листинг не должен превратиться в Error из-за неудачной передачи.
        assertIs<SftpPaneState.Loaded>(controller.state)
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
