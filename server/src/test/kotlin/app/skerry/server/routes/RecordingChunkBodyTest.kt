package app.skerry.server.routes

import io.ktor.server.plugins.BadRequestException
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class RecordingChunkBodyTest {
    @Test
    fun `body is bounded independently of advertised length`() = runTest {
        val expected = ByteArray(40) { it.toByte() }
        assertContentEquals(expected, readRecordingChunk(ByteReadChannel(expected), 40))
        assertFailsWith<BadRequestException> { readRecordingChunk(ByteReadChannel(ByteArray(39)), 40) }
        assertFailsWith<BadRequestException> { readRecordingChunk(ByteReadChannel(ByteArray(41)), 40) }
        assertFailsWith<BadRequestException> { readRecordingChunk(ByteReadChannel(ByteArray(4 * 1024 * 1024)), 40) }
    }
}
