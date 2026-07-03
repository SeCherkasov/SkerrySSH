package app.skerry.shared.ai.local

/**
 * Описание локальной GGUF-модели из курируемого каталога ([LocalModelCatalog]). Каталог — часть
 * приложения, не пользовательский ввод: [url] всегда https на официальный репозиторий модели,
 * [sha256] проверяется после скачивания ([ModelDownloader]) — скачанный блоб не считается
 * моделью, пока дайджест не совпал.
 *
 * [id] — стабильный идентификатор в настройках ([app.skerry.shared.ai.AiSettings]); менять между
 * версиями нельзя (настройки синкаются между устройствами). [sizeBytes] — точный размер файла:
 * по нему считается прогресс и валидируется установка (см. [LocalModelStore.isInstalled]).
 */
data class LocalModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    /**
     * Модель-специфичная добавка к системному промпту (напр. `/no_think` у Qwen3 — выключить
     * thinking-режим chat-шаблона); `null` — не нужна. Данные каталога вместо if-по-имени в коде.
     */
    val extraSystem: String? = null,
)
