package app.skerry.shared.ai.local

/**
 * Курируемый каталог локальных GGUF-моделей. Только ungated-репозитории HuggingFace (скачивание
 * без токена) и OSI-лицензии (Apache-2.0/MIT — приложение GPL-3.0). Размеры и sha256 — из
 * LFS-метаданных HF API (`lfs.oid` = sha256 файла); проверены 2026-07-05.
 *
 * Уклон каталога — на код/команды: терминальному ассистенту нужен точный CMD/INFO, а не общая
 * эрудиция. [default] — Qwen2.5-Coder-1.5B: code-специализирована, без reasoning-режима
 * (мгновенный ответ, extraSystem не нужен) и комфортно живёт в RAM телефона (~1.4 ГБ на ctx 4096).
 * Остальные — desktop: Coder-7B (топ по командам), Qwen3-4B и Phi-4 Mini (general-запас).
 *
 * `/no_think` в [LocalModel.extraSystem] — только у Qwen3: гасит thinking-режим шаблона (остаточные
 * `<think>`-блоки дополнительно режет провайдер). Qwen2.5-Coder reasoning-режима не имеет; у Qwen3.5
 * soft-switch `/no_think` не работает (нужен `enable_thinking=false`, мост его не пробрасывает) —
 * поэтому 3.5 в каталог не берём.
 */
object LocalModelCatalog {

    val qwen25_coder_1_5b = LocalModel(
        id = "qwen25-coder-1.5b-q4km",
        displayName = "Qwen2.5 Coder 1.5B",
        fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_117_320_768,
        sha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
        license = "Apache-2.0",
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

    val qwen25_coder_7b = LocalModel(
        id = "qwen25-coder-7b-q4km",
        displayName = "Qwen2.5 Coder 7B",
        fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
        sizeBytes = 4_683_073_536,
        sha256 = "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c",
        license = "Apache-2.0",
    )

    /** Порядок отображения в UI: дефолт первым, затем desktop-модели по размеру. */
    val models: List<LocalModel> = listOf(qwen25_coder_1_5b, qwen3_4b, phi4mini, qwen25_coder_7b)

    val default: LocalModel = qwen25_coder_1_5b

    fun byId(id: String): LocalModel? = models.find { it.id == id }

    /** Модель из настроек: пустой/устаревший id → [default] (каталог мог смениться апдейтом). */
    fun resolve(id: String): LocalModel = byId(id) ?: default
}
