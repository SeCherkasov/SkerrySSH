package app.skerry.shared.ai.local

/**
 * Курируемый каталог локальных GGUF-моделей. Только ungated-репозитории HuggingFace (скачивание
 * без токена) и OSI-лицензии (Apache-2.0/MIT — приложение GPL-3.0). Размеры и sha256 — из
 * LFS-метаданных HF API (`lfs.oid` = sha256 файла); проверены 2026-07-04.
 *
 * [default] — Qwen3 1.7B: единственная из каталога, комфортно живущая в RAM телефона
 * (~2 ГБ на ctx 4096); 4B-модели — для desktop. `/no_think` в [LocalModel.extraSystem] у Qwen3
 * отключает thinking-режим шаблона: терминальному ассистенту нужен прямой ответ CMD/INFO,
 * а не рассуждения (остаточные `<think>`-блоки дополнительно режет провайдер).
 */
object LocalModelCatalog {

    val qwen3_1_7b = LocalModel(
        id = "qwen3-1.7b-q4km",
        displayName = "Qwen3 1.7B",
        fileName = "Qwen3-1.7B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
        sizeBytes = 1_107_409_472,
        sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        license = "Apache-2.0",
        extraSystem = "/no_think",
    )

    val qwen3_4b = LocalModel(
        id = "qwen3-4b-q4km",
        displayName = "Qwen3 4B",
        fileName = "Qwen3-4B-Q4_K_M.gguf",
        url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
        sizeBytes = 2_497_280_256,
        sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
        license = "Apache-2.0",
        extraSystem = "/no_think",
    )

    val phi4mini = LocalModel(
        id = "phi-4-mini-q4km",
        displayName = "Phi-4 Mini 3.8B",
        fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
        sizeBytes = 2_491_874_272,
        sha256 = "88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a",
        license = "MIT",
    )

    /** Порядок отображения в UI: дефолт первым. */
    val models: List<LocalModel> = listOf(qwen3_1_7b, qwen3_4b, phi4mini)

    val default: LocalModel = qwen3_1_7b

    fun byId(id: String): LocalModel? = models.find { it.id == id }

    /** Модель из настроек: пустой/устаревший id → [default] (каталог мог смениться апдейтом). */
    fun resolve(id: String): LocalModel = byId(id) ?: default
}
