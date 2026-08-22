package app.skerry.ui.files

import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle

internal const val LHOME = "/local/home"
internal const val RHOME = "/remote/app"

/*
 * The rig the transfer tests stand on: a "local" [FakeSftpClient] (an FS stand-in that only sees
 * transfers through its own tree) and a "remote" one carrying the transfer channel. An upload really
 * creates the file in the remote fake, and the re-listed pane shows it. Split out of
 * `TransferCoordinatorTest` so a second suite can use it.
 */

internal fun TestScope.scope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

internal fun localFake() = FakeSftpClient(startDir = LHOME).apply {
    seedFile("$LHOME/a.txt", size = 10)
    seedFile("$LHOME/b.txt", size = 20)
    seedDir("$LHOME/sub")
    seedFile("$LHOME/sub/inner.txt", size = 7)
}

internal fun remoteFake() = FakeSftpClient(startDir = RHOME).apply {
    seedFile("$RHOME/r.txt", size = 30)
}

internal class Rig(
    val local: FilePaneController,
    val remote: FilePaneController,
    val localFake: FakeSftpClient,
    val remoteFake: FakeSftpClient,
    val coordinator: TransferCoordinator,
)

internal fun TestScope.rig(
    local: FakeSftpClient = localFake(),
    remote: FakeSftpClient = remoteFake(),
    now: () -> Long = { 0L },
): Rig {
    val localBrowser = SftpFileBrowser(local, "This Mac")
    val remoteBrowser = SftpFileBrowser(remote, "prod-web-01")
    val localCtl = FilePaneController(localBrowser, scope())
    val remoteCtl = FilePaneController(remoteBrowser, scope())
    localCtl.start(); remoteCtl.start(); advanceUntilIdle()
    val coordinator = TransferCoordinator(remote, localCtl, localBrowser, remoteCtl, remoteBrowser, scope(), now)
    return Rig(localCtl, remoteCtl, local, remote, coordinator)
}

internal fun FilePaneController.entry(name: String) =
    (state as FilePaneState.Loaded).entries.first { it.name == name }
