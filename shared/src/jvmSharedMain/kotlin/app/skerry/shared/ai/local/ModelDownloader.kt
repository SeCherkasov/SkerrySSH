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
 * Downloads a GGUF model from the catalog ([LocalModel.url], always https) with resume and
 * integrity verification. Bytes go to `<file>.part` ([LocalModelStore.partPath]); on restart the
 * download resumes from the current part-file size via `Range` (falls back to starting over if
 * the server doesn't return 206). Install is atomic: sha256 is checked on the full part file and
 * only on match is it renamed to the target, so a half-downloaded model never looks installed.
 *
 * Coroutine cancellation and network failures both preserve the part file for later resume.
 * A catalog mismatch (extra bytes, wrong sha256) deletes the download: resuming corrupt data is
 * pointless, and CDN/proxy garbage must not reach the runtime loader.
 */
class ModelDownloader(
    private val http: HttpClient,
    private val fileSystem: FileSystem,
    private val store: LocalModelStore,
) {
    /**
     * Downloader with its own CIO client, owned by the process and lives for its duration.
     * CIO's default requestTimeout of 15s applies to the whole request, which would abort a
     * gigabyte download mid-body with "Network error" at any speed, so it's disabled here
     * (cancellation/socket drop still terminate normally and are picked up by Range resume).
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
            // emit() inside the execute block is safe on the calling coroutine, same Ktor <4.0
            // JVM contract as OpenAiProvider.chat (see the comment there).
            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    throw ModelDownloadException(ModelDownloadException.Kind.NETWORK, "Model server returned ${response.status}")
                }
                // Server may have ignored Range (200 instead of 206), then write from scratch.
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
            throw e // cancellation is not a failure; the part file remains for later resume
        } catch (e: ModelDownloadException) {
            if (e.kind == ModelDownloadException.Kind.INTEGRITY) store.delete(model)
            throw e
        } catch (e: Exception) {
            // As in OpenAiProvider: not just IOException (CIO also throws non-IO on a bad host).
            throw ModelDownloadException(ModelDownloadException.Kind.NETWORK, "Model download failed: ${e.message}", e)
        }
    }

    /** Streaming sha256 of the file (the model doesn't fit in memory). */
    private fun sha256Hex(path: okio.Path): String =
        HashingSource.sha256(fileSystem.source(path)).use { hashing ->
            hashing.buffer().readAll(blackholeSink())
            hashing.hash.hex()
        }

    private companion object {
        const val DEFAULT_CHUNK = 64 * 1024
    }
}
