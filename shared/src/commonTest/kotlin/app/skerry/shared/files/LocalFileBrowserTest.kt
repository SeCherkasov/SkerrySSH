package app.skerry.shared.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the local browser through an in-memory [FakeFileSystem] (commonTest, no real FS). Covers
 * navigation (realpath), listing with types/sizes, mutations (mkdir/rename/delete), and wrapping
 * okio errors as [FileBrowserException].
 */
class LocalFileBrowserTest {

    private val fs = FakeFileSystem()

    private fun browser(home: String = "/home/me") =
        LocalFileBrowser(fs, home = home, label = "This Mac", ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun `label is exposed`() {
        assertEquals("This Mac", browser().label)
    }

    @Test
    fun `realpath of dot returns the home directory`() = runTest {
        assertEquals("/home/me", browser().realpath("."))
    }

    @Test
    fun `realpath normalizes parent references`() = runTest {
        assertEquals("/home/me", browser().realpath("/home/me/sub/.."))
    }

    @Test
    fun `list returns entries with type and size`() = runTest {
        fs.createDirectories("/dir".toPath())
        fs.write("/dir/a.txt".toPath()) { writeUtf8("hello") }
        fs.createDirectory("/dir/sub".toPath())

        val items = browser().list("/dir").associateBy { it.name }

        assertEquals(2, items.size)
        val file = items.getValue("a.txt")
        assertEquals(FileItemType.File, file.type)
        assertEquals(5, file.size)
        assertEquals("/dir/a.txt", file.path)
        assertEquals(FileItemType.Directory, items.getValue("sub").type)
    }

    @Test
    fun `list of a missing directory throws FileBrowserException`() = runTest {
        // The okio text is diagnostic detail only; the user-facing reason is the typed failure.
        val e = assertFailsWith<FileBrowserException> { browser().list("/nope") }
        assertEquals(FileBrowserFailure.LocalIo, e.failure)
    }

    @Test
    fun `mkdir creates a directory`() = runTest {
        fs.createDirectories("/dir".toPath())

        browser().mkdir("/dir/new")

        assertTrue(fs.metadataOrNull("/dir/new".toPath())?.isDirectory == true)
    }

    @Test
    fun `mkdir on an existing path throws FileBrowserException`() = runTest {
        fs.createDirectories("/dir/dup".toPath())

        assertFailsWith<FileBrowserException> { browser().mkdir("/dir/dup") }
    }

    @Test
    fun `rename moves a file`() = runTest {
        fs.createDirectories("/dir".toPath())
        fs.write("/dir/old.txt".toPath()) { writeUtf8("x") }

        browser().rename("/dir/old.txt", "/dir/new.txt")

        assertNull(fs.metadataOrNull("/dir/old.txt".toPath()))
        assertTrue(fs.exists("/dir/new.txt".toPath()))
    }

    @Test
    fun `delete removes a file`() = runTest {
        fs.createDirectories("/dir".toPath())
        fs.write("/dir/f.txt".toPath()) { writeUtf8("x") }
        val item = FileItem("f.txt", "/dir/f.txt", FileItemType.File, 1, 0)

        browser().delete(item)

        assertFalse(fs.exists("/dir/f.txt".toPath()))
    }

    @Test
    fun `delete removes an empty directory`() = runTest {
        fs.createDirectories("/dir/empty".toPath())
        val item = FileItem("empty", "/dir/empty", FileItemType.Directory, 0, 0)

        browser().delete(item)

        assertFalse(fs.exists("/dir/empty".toPath()))
    }

    @Test
    fun `delete of a non-empty directory removes it recursively`() = runTest {
        fs.createDirectories("/dir/full/inner".toPath())
        fs.write("/dir/full/inside.txt".toPath()) { writeUtf8("x") }
        fs.write("/dir/full/inner/deep.txt".toPath()) { writeUtf8("y") }
        val item = FileItem("full", "/dir/full", FileItemType.Directory, 0, 0)

        browser().delete(item)

        assertFalse(fs.exists("/dir/full".toPath()))
    }

    @Test
    fun `delete of a missing path throws FileBrowserException`() = runTest {
        val item = FileItem("nope", "/dir/nope", FileItemType.Directory, 0, 0)

        assertFailsWith<FileBrowserException> { browser().delete(item) }
    }
}
