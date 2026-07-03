package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.wire
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * [LocalLlmRuntime] поверх Llamatik (llama.cpp, GGUF) — одна реализация на desktop и Android
 * (KMP-артефакт несёт нативы обоих). `LlamaBridge` — глобальный синглтон нативной библиотеки
 * с ОДНОЙ загруженной моделью и глобальной генерацией, поэтому:
 * - экземпляр рантайма должен быть один на процесс (модель остаётся в памяти между запросами,
 *   перезагружается только при смене файла);
 * - конкурентные генерации сериализуются [mutex] — второй запрос ждёт, а не мешает первому;
 * - отмена коллектора гасит генерацию через `nativeCancelGenerate` при следующей дельте
 *   (JNI-вызов блокирующий, прервать его снаружи нельзя).
 *
 * Блокирующая работа (загрузка GGUF, инференс) идёт на [Dispatchers.IO]; дельты уходят в канал
 * `channelFlow` — колбэк Llamatik зовётся синхронно на том же IO-потоке.
 *
 * [contextLength] платформа задаёт при сборке графа: desktop 4096, Android 2048 (KV-кэш 4B-модели
 * на большом контексте — сотни МиБ, на телефоне это OOM).
 */
class LlamatikRuntime(
    private val contextLength: Int,
) : LocalLlmRuntime {

    private val mutex = Mutex()
    private var loadedPath: Path? = null

    override fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta> = channelFlow {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureLoaded(modelPath)
                    applyParams(request)
                    val prompt = renderPrompt(request.messages)
                    var failure: String? = null
                    LlamaBridge.generateStream(
                        prompt,
                        object : GenStream {
                            override fun onDelta(text: String) {
                                // Коллектор ушёл (отмена) — канал закрыт: просим натив остановиться.
                                if (trySendBlocking(AiDelta(text)).isClosed) LlamaBridge.nativeCancelGenerate()
                            }

                            override fun onComplete() {}

                            override fun onError(message: String) {
                                failure = message
                            }
                        },
                    )
                    failure?.let { throw AiException(AiException.Kind.PROTOCOL, "Local model error: $it") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    throw e
                } catch (e: Exception) {
                    throw AiException(AiException.Kind.PROTOCOL, "Local inference failed: ${e.message}", e)
                }
            }
        }
    }

    /** Загрузить модель, если ещё не загружена (или загружена другая). */
    private fun ensureLoaded(path: Path) {
        if (loadedPath == path) return
        // initGenerateModel сам заменяет ранее загруженную модель — отдельного unload у API нет.
        if (!LlamaBridge.initGenerateModel(path.toString())) {
            loadedPath = null
            throw AiException(AiException.Kind.INVALID_REQUEST, "Failed to load local model at $path")
        }
        loadedPath = path
    }

    /**
     * Параметры генерации — перед КАЖДЫМ запросом: temperature/maxOutputTokens приходят из
     * [AiChatRequest] (терминальный бар просит низкую температуру — команда должна быть
     * детерминированной, не творческой; чат живёт на дефолте). Вызов дешёвый (сеттер в нативе).
     */
    private fun applyParams(request: AiChatRequest) {
        LlamaBridge.updateGenerateParams(
            temperature = request.temperature?.toFloat() ?: DEFAULT_TEMPERATURE,
            maxTokens = request.maxOutputTokens ?: DEFAULT_MAX_TOKENS,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = contextLength,
            numThreads = threads(),
            useMmap = true, // GGUF мапится, а не читается в кучу — старт быстрее, RAM ниже
            flashAttention = false,
            batchSize = 256,
            gpuLayers = 0, // прекомпилированные нативы Llamatik: CPU (Metal только на macOS arm64)
        )
    }

    /**
     * Промпт по chat-шаблону из самого GGUF; модель без шаблона — ChatML-fallback
     * (родной формат Qwen/Phi из каталога).
     */
    private fun renderPrompt(messages: List<AiMessage>): String =
        LlamaBridge.applyChatTemplate(messages.map { it.role.wire to it.content }, addAssistantPrefix = true)
            ?: buildString {
                messages.forEach { m ->
                    append("<|im_start|>").append(m.role.wire).append('\n')
                    append(m.content).append("<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }

    /** Потоки инференса: физические ядра без гипертрединга обычно ~cores/2; минимум 2. */
    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    private companion object {
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_MAX_TOKENS = 1024 // ответы ассистента короткие (CMD/INFO, компактные объяснения)
    }
}
