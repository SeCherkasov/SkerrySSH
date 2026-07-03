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
 * Живая закачка дефолтной каталожной модели с HuggingFace (redirect на CDN, гигабайтное тело,
 * докачка, sha256) — регрессия на дефолтный requestTimeout CIO (15 с), рвавший любую закачку
 * длиннее. В обычном прогоне пропускается; включается `SKERRY_LOCAL_AI_DOWNLOAD_IT` — каталог,
 * куда скачать (модель остаётся там: можно перекинуть в ~/.local/share/skerry/models).
 *
 *     SKERRY_LOCAL_AI_DOWNLOAD_IT=/tmp/skerry-models ./gradlew :shared:desktopTest \
 *         --tests 'app.skerry.shared.ai.local.ModelDownloaderIntegrationTest'
 */
class ModelDownloaderIntegrationTest {

    @Test
    fun `downloads every catalog model end-to-end`() = runTest(timeout = 60.minutes) {
        val dir = System.getenv("SKERRY_LOCAL_AI_DOWNLOAD_IT")?.takeIf { it.isNotBlank() }
            ?: return@runTest // не e2e-прогон: без каталога пропускаем (CI не тянет гигабайты)

        val store = LocalModelStore(FileSystem.SYSTEM, dir.toPath())
        val downloader = ModelDownloader(FileSystem.SYSTEM, store)

        // Весь каталог: битый URL или разъехавшийся sha256 любой записи должен падать здесь,
        // а не у пользователя. Уже установленные (с прошлого прогона) пропускаем.
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
