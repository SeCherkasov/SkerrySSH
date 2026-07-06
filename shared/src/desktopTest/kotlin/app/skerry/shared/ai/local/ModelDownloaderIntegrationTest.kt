package app.skerry.shared.ai.local

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Live download of the default catalog model from HuggingFace (CDN redirect, multi-gigabyte
 * body, resume, sha256) — regression for the default CIO requestTimeout (15s), which broke any
 * longer download. Skipped in normal runs; enabled by `SKERRY_LOCAL_AI_DOWNLOAD_IT` — the
 * directory to download into (the model stays there; can be moved to ~/.local/share/skerry/models).
 *
 *     SKERRY_LOCAL_AI_DOWNLOAD_IT=/tmp/skerry-models ./gradlew :shared:desktopTest \
 *         --tests 'app.skerry.shared.ai.local.ModelDownloaderIntegrationTest'
 */
class ModelDownloaderIntegrationTest {

    @Test
    fun `downloads every catalog model end-to-end`() = runTest(timeout = 60.minutes) {
        val dir = System.getenv("SKERRY_LOCAL_AI_DOWNLOAD_IT")?.takeIf { it.isNotBlank() }
            ?: return@runTest // not an e2e run: skip without a directory (CI doesn't pull gigabytes)

        val store = LocalModelStore(FileSystem.SYSTEM, dir.toPath())
        val downloader = ModelDownloader(FileSystem.SYSTEM, store)

        // Whole catalog: a broken URL or mismatched sha256 for any entry must fail here, not for
        // the user. Skip entries already installed from a previous run.
        LocalModelCatalog.models.forEach { model ->
            if (store.isInstalled(model)) {
                println("LOCAL-AI-DL-IT ${model.id}: already installed, skipping")
                return@forEach
            }
            val events = downloader.download(model).toList()
            assertIs<ModelDownloadEvent.Completed>(events.last(), "model ${model.id}")
            assertTrue(store.isInstalled(model), "${model.id} must be installed after download")
            println("LOCAL-AI-DL-IT installed ${model.id}: ${store.path(model)} (${model.sizeBytes} bytes, sha256 verified)")
        }
    }
}
