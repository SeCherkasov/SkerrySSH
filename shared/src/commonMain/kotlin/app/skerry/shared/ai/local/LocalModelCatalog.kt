package app.skerry.shared.ai.local

/**
 * Curated catalog of local GGUF models. Only ungated HuggingFace repos (no-token download) and
 * OSI licenses (Apache-2.0/MIT — the app is GPL-3.0). Sizes and sha256 come from HF API LFS
 * metadata (`lfs.oid` = file sha256).
 *
 * The catalog skews toward code/commands: the terminal assistant needs precise CMD/INFO, not
 * general knowledge. [default] is Qwen2.5-Coder-1.5B: code-specialized, no reasoning mode
 * (instant response, no extraSystem needed), and fits comfortably in phone RAM (~1.4 GB at ctx
 * 4096). The rest are desktop models: Coder-7B (best at commands), Qwen3-4B and Phi-4 Mini
 * (general-purpose fallback).
 *
 * `/no_think` in [LocalModel.extraSystem] is Qwen3-only: disables the template's thinking mode
 * (residual `<think>` blocks are additionally stripped by the provider). Qwen2.5-Coder has no
 * reasoning mode; Qwen3.5's soft-switch `/no_think` doesn't work (needs `enable_thinking=false`,
 * which the bridge doesn't forward), so 3.5 isn't included in the catalog.
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

    /** Display order in the UI: default first, then desktop models by size. */
    val models: List<LocalModel> = listOf(qwen25_coder_1_5b, qwen3_4b, phi4mini, qwen25_coder_7b)

    val default: LocalModel = qwen25_coder_1_5b

    fun byId(id: String): LocalModel? = models.find { it.id == id }

    /** Model from settings: empty/stale id falls back to [default] (an update may have changed the catalog). */
    fun resolve(id: String): LocalModel = byId(id) ?: default
}
