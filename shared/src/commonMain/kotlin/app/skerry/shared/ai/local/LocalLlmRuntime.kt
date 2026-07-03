package app.skerry.shared.ai.local

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import kotlinx.coroutines.flow.Flow
import okio.Path

/**
 * Движок локального инференса: контракт в ядре, реализация платформенная (`LlamatikRuntime`
 * в jvmShared — llama.cpp за KMP-биндингом). Изолирует конкретный биндинг: смена движка
 * (llama-server, свой NDK-модуль) не трогает провайдер и UI.
 *
 * Реализация обязана:
 * - держать модель загруженной между вызовами (загрузка GGUF — секунды и гигабайты RAM,
 *   не грузить на каждый запрос) и переключаться при смене [modelPath];
 * - выполнять блокирующую генерацию вне UI-потока;
 * - сигнализировать сбои [app.skerry.shared.ai.AiException], отмену — пробрасывать.
 */
interface LocalLlmRuntime {
    fun generate(modelPath: Path, request: AiChatRequest): Flow<AiDelta>
}
