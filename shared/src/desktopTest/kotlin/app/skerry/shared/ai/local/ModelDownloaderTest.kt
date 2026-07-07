package app.skerry.shared.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelDownloaderTest {

    private val payload = "GGUF-model-bytes-0123456789".encodeToByteArray()

    private fun model(bytes: ByteArray = payload, sha256: String = bytes.toByteString().sha256().hex()) = LocalModel(
        id = "test-model-q4",
        displayName = "Test Model 1B",
        fileName = "m.gguf",
        url = "https://models.example.com/m.gguf",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256,
        license = "Apache-2.0",
    )

    private val fs = FakeFileSystem()
    private val store = LocalModelStore(fs, "/data/models".toPath())

    private var lastRangeHeader: String? = null

    /** Mock model server: full 200 response, or (with Range and [supportRange]) a 206 tail. */
    private fun http(bytes: ByteArray = payload, supportRange: Boolean = true, status: HttpStatusCode? = null): HttpClient =
        HttpClient(MockEngine { req ->
            lastRangeHeader = req.headers[HttpHeaders.Range]
            if (status != null) return@MockEngine respondError(status)
            val range = req.headers[HttpHeaders.Range]
            if (range != null && supportRange) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                respond(
                    content = bytes.copyOfRange(from, bytes.size),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(HttpHeaders.ContentRange, "bytes $from-${bytes.size - 1}/${bytes.size}"),
                )
            } else {
                respond(content = bytes, status = HttpStatusCode.OK)
            }
        })

    @Test
    fun `downloads verifies and installs the model emitting progress`() = runTest {
        val downloader = ModelDownloader(http(), fs, store)
        val m = model()

        val events = downloader.download(m).toList()

        assertTrue(events.filterIsInstance<ModelDownloadEvent.Progress>().isNotEmpty(), "expected progress events")
        assertTrue(events.contains(ModelDownloadEvent.Verifying), "expected a verifying phase")
        assertIs<ModelDownloadEvent.Completed>(events.last())
        assertTrue(store.isInstalled(m))
        assertFalse(fs.exists(store.partPath(m)), "part file must be gone after install")
        assertEquals(payload.toByteString(), fs.read(store.path(m)) { readByteString() })
    }

    @Test
    fun `final progress event reports full size`() = runTest {
        val downloader = ModelDownloader(http(), fs, store)
        val m = model()

        val progress = downloader.download(m).toList().filterIsInstance<ModelDownloadEvent.Progress>()

        assertEquals(m.sizeBytes, progress.last().downloadedBytes)
        assertEquals(m.sizeBytes, progress.last().totalBytes)
    }

    @Test
    fun `resumes a partial download with a range request`() = runTest {
        val m = model()
        fs.createDirectories("/data/models".toPath())
        fs.write(store.partPath(m)) { write(payload, 0, 10) } // the first 10 bytes are already downloaded

        val downloader = ModelDownloader(http(), fs, store)
        downloader.download(m).toList()

        assertEquals("bytes=10-", lastRangeHeader)
        assertTrue(store.isInstalled(m))
        assertEquals(payload.toByteString(), fs.read(store.path(m)) { readByteString() })
    }

    @Test
    fun `restarts from scratch when the server ignores range`() = runTest {
        val m = model()
        fs.createDirectories("/data/models".toPath())
        fs.write(store.partPath(m)) { write(payload, 0, 10) }

        val downloader = ModelDownloader(http(supportRange = false), fs, store)
        downloader.download(m).toList()

        assertTrue(store.isInstalled(m))
        assertEquals(payload.toByteString(), fs.read(store.path(m)) { readByteString() })
    }

    @Test
    fun `checksum mismatch fails with INTEGRITY and discards the file`() = runTest {
        val m = model(sha256 = "deadbeef".repeat(8)) // deliberately wrong digest
        val downloader = ModelDownloader(http(), fs, store)

        val ex = assertFailsWith<ModelDownloadException> { downloader.download(m).toList() }

        assertEquals(ModelDownloadException.Kind.INTEGRITY, ex.kind)
        assertFalse(store.isInstalled(m))
        assertFalse(fs.exists(store.partPath(m)), "corrupt part must not be kept for resume")
    }

    @Test
    fun `http error maps to NETWORK and keeps the part file for resume`() = runTest {
        val m = model()
        fs.createDirectories("/data/models".toPath())
        fs.write(store.partPath(m)) { write(payload, 0, 10) }

        val downloader = ModelDownloader(http(status = HttpStatusCode.ServiceUnavailable), fs, store)
        val ex = assertFailsWith<ModelDownloadException> { downloader.download(m).toList() }

        assertEquals(ModelDownloadException.Kind.NETWORK, ex.kind)
        assertTrue(fs.exists(store.partPath(m)), "part file must survive a network failure")
    }

    @Test
    fun `server sending more bytes than the catalog size fails with INTEGRITY`() = runTest {
        val oversized = payload + "extra".encodeToByteArray()
        val m = model() // sizeBytes = payload.size, the server sends more
        val downloader = ModelDownloader(http(bytes = oversized, supportRange = false), fs, store)

        val ex = assertFailsWith<ModelDownloadException> { downloader.download(m).toList() }

        assertEquals(ModelDownloadException.Kind.INTEGRITY, ex.kind)
        assertFalse(store.isInstalled(m))
    }
}
