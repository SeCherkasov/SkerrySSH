package app.skerry.shared.ai

/**
 * Config for an external OpenAI-compatible provider (BYOK). [apiKey] is the user's key, stored in
 * the encrypted vault and must not reach logs. [baseUrl] is the compatible endpoint without a
 * trailing slash (can point at a custom proxy/gateway); [model] is the default model.
 */
data class OpenAiConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    // apiKey is a secret: excluded from toString to avoid leaking into logs/crash dumps.
    override fun toString(): String =
        "OpenAiConfig(model=$model, baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "<empty>" else "<redacted>"})"

    companion object {
        /** BYOK provider defaults — single source of truth (also referenced by [AiSettings]). */
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
