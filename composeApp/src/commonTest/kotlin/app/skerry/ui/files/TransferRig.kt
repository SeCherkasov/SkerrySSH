package app.skerry.ui.files

import app.skerry.shared.files.SftpFileBrowser
import app.skerry.ui.sftp.DownloadTarget
import app.skerry.ui.sftp.FakeSftpClient
import app.skerry.ui.sftp.UploadSource
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
    /** The scope the coordinator runs its transfers on — cancelled to play the session dying. */
    val scope: CoroutineScope,
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
    val transferScope = scope()
    val coordinator = TransferCoordinator(remote, localCtl, localBrowser, remoteCtl, remoteBrowser, transferScope, now)
    return Rig(localCtl, remoteCtl, local, remote, coordinator, transferScope)
}

internal fun FilePaneController.entry(name: String) =
    (state as FilePaneState.Loaded).entries.first { it.name == name }

/**
 * Test target for "Save to..." downloads. Both outcomes are counted rather than flagged: the
 * contract is that exactly one of them happens exactly once, and a document that is written *and*
 * then removed is as much a defect as one that is never written at all.
 */
internal class FakeDownloadTarget(
    override val displayName: String,
    override val stagingPath: String,
    private val finalizeError: String? = null,
) : DownloadTarget {
    var finalizes = 0
        private set
    var discards = 0
        private set
    val finalized: Boolean get() = finalizes > 0
    val discarded: Boolean get() = discards > 0
    override suspend fun finalize() {
        // error(), not a bare throw: detekt reads a `throw` inside a method named finalize as the
        // Java finalizer hazard, and this one is the DownloadTarget contract's finalize.
        finalizeError?.let { error(it) }
        finalizes++
    }
    override suspend fun discard() { discards++ }
}

/**
 * Test source for picked uploads. [cleanups] counts the calls rather than flagging them: the
 * contract is "exactly once", and a second release of a handle the platform already freed is as
 * much a defect as never releasing it.
 */
internal class FakeUploadSource(
    override val name: String,
    override val stagingPath: String,
) : UploadSource {
    var cleanups = 0
        private set
    override suspend fun cleanup() { cleanups++ }
}
