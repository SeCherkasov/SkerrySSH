package app.skerry.shared.vault

import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class AtomicWriteTest {

    /**
     * Records the order of the two events that matter: the tmp file's contents reaching the disk
     * (a [FileHandle.flush], which is `fsync` on the JVM) and the rename over the target.
     */
    private class OrderingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        val events = mutableListOf<String>()

        override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
            SyncRecordingHandle(super.openReadWrite(file, mustCreate, mustExist)) { events += "sync ${file.name}" }

        override fun atomicMove(source: Path, target: Path) {
            events += "move ${source.name}"
            super.atomicMove(source, target)
        }
    }

    private class SyncRecordingHandle(
        private val delegate: FileHandle,
        private val onSync: () -> Unit,
    ) : FileHandle(readWrite = true) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            delegate.read(fileOffset, array, arrayOffset, byteCount)

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            delegate.write(fileOffset, array, arrayOffset, byteCount)

        override fun protectedFlush() {
            delegate.flush()
            onSync()
        }

        override fun protectedResize(size: Long) = delegate.resize(size)

        override fun protectedSize(): Long = delegate.size()

        override fun protectedClose() = delegate.close()
    }

    @Test
    fun `the new contents reach the disk before they replace the old file`() {
        // A rename is durable before the data it points at: after a power cut the target could be the
        // renamed, still empty tmp file — the whole vault gone, the old copy already unlinked.
        val fs = OrderingFileSystem(FakeFileSystem())
        val target = "/vault.json".toPath()

        atomicWriteUtf8(fs, target, "new")

        assertEquals("sync vault.json.tmp", fs.events.firstOrNull(), fs.events.toString())
        assertEquals("move vault.json.tmp", fs.events.last(), fs.events.toString())
        assertEquals("new", fs.read(target) { readUtf8() })
    }

    @Test
    fun `a shorter rewrite leaves no tail of a longer tmp file behind`() {
        // The tmp file is opened read-write, which does not truncate: a stale tmp left by a crash
        // must not leak its tail into the next write.
        val fs = FakeFileSystem()
        val target = "/vault.json".toPath()
        fs.write("/vault.json.tmp".toPath()) { write(Buffer().writeUtf8("a much longer stale body"), 24) }

        atomicWriteUtf8(fs, target, "new")

        assertEquals("new", fs.read(target) { readUtf8() })
    }
}
