package app.skerry.ui.ai

import app.skerry.shared.ai.local.LocalModel
import app.skerry.shared.ai.local.LocalModelCatalog
import app.skerry.shared.ai.local.ModelDownloadEvent
import app.skerry.shared.ai.local.ModelDownloadException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalModelControllerTest {

    private val model = LocalModelCatalog.default

    private fun okFlow(m: LocalModel): Flow<ModelDownloadEvent> = flow {
        emit(ModelDownloadEvent.Progress(1, m.sizeBytes))
        emit(ModelDownloadEvent.Progress(m.sizeBytes, m.sizeBytes))
        emit(ModelDownloadEvent.Verifying)
        emit(ModelDownloadEvent.Completed("/models/${m.fileName}".toPath()))
    }

    @Test
    fun `starts as installed when the file is already on disk`() = runTest {
        val c = LocalModelController(installed = { true }, fetch = { okFlow(it) }, remove = {}, scope = this)
        assertEquals(LocalModelStatus.Installed, c.status(model))
    }

    @Test
    fun `download walks through progress verifying and installed`() = runTest {
        var done = false
        val c = LocalModelController(installed = { done }, fetch = { okFlow(it) }, remove = {}, scope = this)

        c.download(model)
        advanceUntilIdle()
        done = true

        assertEquals(LocalModelStatus.Installed, c.status(model))
    }

    @Test
    fun `progress is visible while the download runs`() = runTest {
        val c = LocalModelController(
            installed = { false },
            fetch = {
                flow {
                    emit(ModelDownloadEvent.Progress(42, 100))
                    awaitCancellation()
                }
            },
            remove = {},
            scope = this,
        )

        c.download(model)
        runCurrent()

        val status = assertIs<LocalModelStatus.Downloading>(c.status(model))
        assertEquals(42, status.downloadedBytes)
        assertEquals(100, status.totalBytes)

        c.cancel(model)
        advanceUntilIdle()
        assertEquals(LocalModelStatus.NotInstalled, c.status(model))
    }

    @Test
    fun `a failed download shows the error and can be retried`() = runTest {
        var fail = true
        val c = LocalModelController(
            installed = { false },
            fetch = {
                if (fail) flow { throw ModelDownloadException(ModelDownloadException.Kind.NETWORK, "boom") }
                else okFlow(it)
            },
            remove = {},
            scope = this,
        )

        c.download(model)
        advanceUntilIdle()
        assertEquals(LocalModelFailure.NETWORK, assertIs<LocalModelStatus.Failed>(c.status(model)).failure)

        fail = false
        c.download(model)
        advanceUntilIdle()
        assertEquals(LocalModelStatus.Installed, c.status(model))
    }

    @Test
    fun `a second download call while active is a no-op`() = runTest {
        var starts = 0
        val c = LocalModelController(
            installed = { false },
            fetch = {
                starts++
                flow { emit(ModelDownloadEvent.Progress(1, 2)); awaitCancellation() }
            },
            remove = {},
            scope = this,
        )

        c.download(model)
        runCurrent()
        c.download(model)
        runCurrent()

        assertEquals(1, starts)
        c.cancel(model)
    }

    @Test
    fun `delete during an active download cancels it and resets the status`() = runTest {
        var removed = false
        var fetchCancelled = false
        val c = LocalModelController(
            installed = { false },
            fetch = {
                flow {
                    emit(ModelDownloadEvent.Progress(10, 100))
                    try {
                        awaitCancellation()
                    } finally {
                        fetchCancelled = true
                    }
                }
            },
            remove = { removed = true },
            scope = this,
        )

        c.download(model)
        runCurrent()
        assertIs<LocalModelStatus.Downloading>(c.status(model))

        c.delete(model)
        advanceUntilIdle()

        assertTrue(fetchCancelled, "an active download should be cancelled")
        assertTrue(removed)
        assertEquals(LocalModelStatus.NotInstalled, c.status(model))
    }

    @Test
    fun `delete removes the file and resets the status`() = runTest {
        var removed = false
        var present = true
        val c = LocalModelController(installed = { present }, fetch = { okFlow(it) }, remove = { removed = true; present = false }, scope = this)
        assertEquals(LocalModelStatus.Installed, c.status(model))

        c.delete(model)

        assertTrue(removed)
        assertEquals(LocalModelStatus.NotInstalled, c.status(model))
    }
}
