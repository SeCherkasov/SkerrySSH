package app.skerry.shared.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.FileSystem
import okio.HashingSource
import okio.blackholeSink
import okio.buffer

/**
 * Скачивание GGUF-модели из каталога ([LocalModel.url], всегда https) с докачкой и проверкой
 * целостности. Байты идут в `<файл>.part` ([LocalModelStore.partPath]); при повторном запуске
 * закачка продолжается с текущего размера part-файла через `Range` (сервер без 206 — начинаем
 * заново). Установка атомарна: sha256 проверяется на полном part-файле и только после совпадения
 * файл переименовывается в целевой — полускачанная модель никогда не выглядит установленной.
 *
 * Отмена корутины сохраняет part-файл (докачаем в следующий раз); сбои сети — тоже.
 * Расхождение с каталогом (лишние байты, чужой sha256) удаляет скачанное: докачивать битое
 * бессмысленно, а подсунутый CDN/прокси мусор не должен дожить до загрузки в рантайм.
 */
class ModelDownloader(
    private val http: HttpClient,
    private val fileSystem: FileSystem,
    private val store: LocalModelStore,
) {
    /**
     * Загрузчик с собственным CIO-клиентом (владение — у процесса, живёт до его конца).
     * У CIO дефолтный requestTimeout — 15 с НА ВЕСЬ ЗАПРОС: гигабайтная закачка рвалась бы
     * посреди тела «Network error» на любой скорости — отключаем (отмена/обрыв сокета
     * по-прежнему обрываются штатно, докачка по Range их подхватывает).
     */
    constructor(fileSystem: FileSystem, store: LocalModelStore) : this(
        HttpClient(CIO) { engine { requestTimeout = 0 } },
        fileSystem,
        store,
    )

    fun download(model: LocalModel): Flow<ModelDownloadEvent> = flow {
        val part = store.partPath(model)
        part.parent?.let { fileSystem.createDirectories(it) }
        try {
            val resumeFrom = store.downloadedBytes(model).takeIf { it in 1 until model.sizeBytes } ?: 0L
            val statement = http.prepareGet(model.url) {
                if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
            }
            // emit() внутри execute-блока безопасен на вызывающей корутине — тот же JVM-контракт
            // Ktor <4.0, что и в OpenAiProvider.chat (см. комментарий там).
            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    throw ModelDownloadException(ModelDownloadException.Kind.NETWORK, "Model server returned ${response.status}")
                }
                // Сервер мог проигнорировать Range (200 вместо 206) — тогда пишем с нуля.
                val appending = resumeFrom > 0 && response.status == HttpStatusCode.PartialContent
                var written = if (appending) resumeFrom else 0L
                val sink = if (appending) fileSystem.appendingSink(part) else fileSystem.sink(part)
                sink.buffer().use { out ->
                    val channel = response.bodyAsChannel()
                    val chunk = ByteArray(DEFAULT_CHUNK)
                    while (true) {
                        val n = channel.readAvailable(chunk, 0, chunk.size)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(chunk, 0, n)
                        written += n
                        if (written > model.sizeBytes) {
                            throw ModelDownloadException(
                                ModelDownloadException.Kind.INTEGRITY,
                                "Model is larger than the catalog size (${model.sizeBytes} bytes)",
                            )
                        }
                        emit(ModelDownloadEvent.Progress(written, model.sizeBytes))
                    }
                }
                if (written < model.sizeBytes) {
                    throw ModelDownloadException(ModelDownloadException.Kind.NETWORK, "Connection closed at $written/${model.sizeBytes} bytes")
                }
            }
            emit(ModelDownloadEvent.Verifying)
            val actual = sha256Hex(part)
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                throw ModelDownloadException(ModelDownloadException.Kind.INTEGRITY, "Model checksum mismatch")
            }
            val target = store.path(model)
            fileSystem.atomicMove(part, target)
            emit(ModelDownloadEvent.Completed(target))
        } catch (e: CancellationException) {
            throw e // отмена — не сбой; part-файл остаётся для докачки
        } catch (e: ModelDownloadException) {
            if (e.kind == ModelDownloadException.Kind.INTEGRITY) store.delete(model)
            throw e
        } catch (e: Exception) {
            // Как в OpenAiProvider: не только IOException (CIO кидает и не-IO на кривой хост).
            throw ModelDownloadException(ModelDownloadException.Kind.NETWORK, "Model download failed: ${e.message}", e)
        }
    }

    /** Потоковый sha256 файла (модель в память не влезает). */
    private fun sha256Hex(path: okio.Path): String =
        HashingSource.sha256(fileSystem.source(path)).use { hashing ->
            hashing.buffer().readAll(blackholeSink())
            hashing.hash.hex()
        }

    private companion object {
        const val DEFAULT_CHUNK = 64 * 1024
    }
}
