package app.skerry.ui.files

import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.FakeSftpClient
import app.skerry.ui.sftp.TransferDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LHOME = "/local/home"
private const val RHOME = "/remote/app"

/**
 * Тесты координатора передачи. Локальная панель — над «локальным» [FakeSftpClient] (имитация ФС,
 * передачу видит только через своё дерево), удалённая и сам канал передачи — над «удалённым»
 * [FakeSftpClient]. Так upload реально создаёт файл в удалённом фейке (его `upload` сеет файл), и
 * перечитанная удалённая панель его показывает.
 */
class TransferCoordinatorTest {

    private fun TestScope.scope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun localFake() = FakeSftpClient(startDir = LHOME).apply {
        seedFile("$LHOME/a.txt", size = 10)
        seedFile("$LHOME/b.txt", size = 20)
        seedDir("$LHOME/sub")
    }

    private fun remoteFake() = FakeSftpClient(startDir = RHOME).apply {
        seedFile("$RHOME/r.txt", size = 30)
    }

    private class Rig(
        val local: FilePaneController,
        val remote: FilePaneController,
        val remoteFake: FakeSftpClient,
        val coordinator: TransferCoordinator,
    )

    private fun TestScope.rig(
        local: FakeSftpClient = localFake(),
        remote: FakeSftpClient = remoteFake(),
    ): Rig {
        val localCtl = FilePaneController(SftpFileBrowser(local, "This Mac"), scope())
        val remoteCtl = FilePaneController(SftpFileBrowser(remote, "prod-web-01"), scope())
        localCtl.start(); remoteCtl.start(); advanceUntilIdle()
        val coordinator = TransferCoordinator(remote, localCtl, remoteCtl, scope())
        return Rig(localCtl, remoteCtl, remote, coordinator)
    }

    private fun FilePaneController.entry(name: String) =
        (state as FilePaneState.Loaded).entries.first { it.name == name }

    @Test
    fun `uploadSelection sends selected local files into the remote directory and refreshes it`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val remoteNames = (r.remote.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("a.txt" in remoteNames && "b.txt" in remoteNames)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.local.selection.isEmpty())
    }

    @Test
    fun `uploadSelection skips directories in the selection`() = runTest {
        val r = rig()
        r.local.toggle(r.local.entry("sub")) // каталог — должен быть пропущен

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        val remoteNames = (r.remote.state as FilePaneState.Loaded).entries.map { it.name }
        assertTrue("sub" !in remoteNames)
    }

    @Test
    fun `downloadSelection downloads selected remote files into the local directory`() = runTest {
        val r = rig()
        r.remote.toggle(r.remote.entry("r.txt"))

        r.coordinator.downloadSelection()
        advanceUntilIdle()

        assertEquals("$RHOME/r.txt" to "$LHOME/r.txt", r.remoteFake.lastDownload)
        assertEquals(TransferState.Idle, r.coordinator.transfer)
        assertTrue(r.remote.selection.isEmpty())
    }

    @Test
    fun `transfer exposes active progress with file counts while running`() = runTest {
        val remote = remoteFake().apply { uploadSize = 10 }
        val gate = CompletableDeferred<Unit>()
        remote.transferGate = gate
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))
        r.local.toggle(r.local.entry("b.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle() // дойдёт до шлюза на первом файле

        val active = assertIs<TransferState.Active>(r.coordinator.transfer)
        assertEquals(TransferDirection.Upload, active.direction)
        assertEquals(1, active.fileIndex)
        assertEquals(2, active.fileCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `empty selection is a no-op`() = runTest {
        val r = rig()
        r.coordinator.uploadSelection()
        advanceUntilIdle()
        assertEquals(TransferState.Idle, r.coordinator.transfer)
    }

    @Test
    fun `a failed transfer surfaces as Failed`() = runTest {
        val remote = remoteFake().apply { uploadError = "диск переполнен" }
        val r = rig(remote = remote)
        r.local.toggle(r.local.entry("a.txt"))

        r.coordinator.uploadSelection()
        advanceUntilIdle()

        assertIs<TransferState.Failed>(r.coordinator.transfer)
    }
}
