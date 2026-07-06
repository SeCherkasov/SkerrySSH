package app.skerry.shared.ai.local

/**
 * Description of a local GGUF model from the curated catalog ([LocalModelCatalog]). The catalog is
 * part of the app, not user input: [url] is always https to the model's official repo, [sha256] is
 * verified after download ([ModelDownloader]) — a downloaded blob isn't considered a model until
 * the digest matches.
 *
 * [id] is a stable identifier in settings ([app.skerry.shared.ai.AiSettings]); must not change
 * across versions (settings sync between devices). [sizeBytes] is the exact file size, used for
 * progress and to validate installation (see [LocalModelStore.isInstalled]).
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
     * Model-specific addition to the system prompt (e.g. Qwen3's `/no_think` disables the chat
     * template's thinking mode); `null` if not needed. Catalog data instead of an if-by-name in code.
     */
    val extraSystem: String? = null,
)
