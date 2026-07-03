package app.skerry.shared.ai.local

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalModelStoreTest {

    private val model = LocalModel(
        id = "test-model-q4",
        displayName = "Test Model 1B",
        fileName = "test-model-1b-q4.gguf",
        url = "https://example.com/test-model-1b-q4.gguf",
        sizeBytes = 10,
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        license = "Apache-2.0",
    )

    private val fs = FakeFileSystem()
    private val dir = "/data/models".toPath()
    private val store = LocalModelStore(fs, dir)

    @Test
    fun `paths resolve inside the models directory`() {
        assertEquals("/data/models/test-model-1b-q4.gguf".toPath(), store.path(model))
        assertEquals("/data/models/test-model-1b-q4.gguf.part".toPath(), store.partPath(model))
    }

    @Test
    fun `model is not installed when file is missing`() {
        assertFalse(store.isInstalled(model))
    }

    @Test
    fun `model is not installed when file size differs from catalog`() {
        fs.createDirectories(dir)
        fs.write(store.path(model)) { writeUtf8("short") } // 5 байт вместо 10
        assertFalse(store.isInstalled(model))
    }

    @Test
    fun `model is installed when file exists with exact catalog size`() {
        fs.createDirectories(dir)
        fs.write(store.path(model)) { writeUtf8("0123456789") }
        assertTrue(store.isInstalled(model))
    }

    @Test
    fun `downloadedBytes reports partial file size and zero when absent`() {
        assertEquals(0L, store.downloadedBytes(model))
        fs.createDirectories(dir)
        fs.write(store.partPath(model)) { writeUtf8("0123") }
        assertEquals(4L, store.downloadedBytes(model))
    }

    @Test
    fun `delete removes both installed file and partial download`() {
        fs.createDirectories(dir)
        fs.write(store.path(model)) { writeUtf8("0123456789") }
        fs.write(store.partPath(model)) { writeUtf8("01") }

        store.delete(model)

        assertFalse(fs.exists(store.path(model)))
        assertFalse(fs.exists(store.partPath(model)))
        assertFalse(store.isInstalled(model))
    }

    @Test
    fun `delete is a no-op when nothing was downloaded`() {
        store.delete(model) // не должно бросить
        assertFalse(store.isInstalled(model))
    }
}
