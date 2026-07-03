package app.skerry.shared.ai

/**
 * Конфиг внешнего OpenAI-совместимого провайдера (BYOK). [apiKey] — ключ пользователя;
 * хранится в зашифрованном Vault и не должен попадать в логи. [baseUrl] — совместимый endpoint
 * без хвостового слэша (можно указать свой прокси/шлюз), [model] — модель по умолчанию.
 */
data class OpenAiConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    // apiKey — секрет: не печатаем его в toString (иначе утечёт в логи/крэш-дампы при логировании объекта).
    override fun toString(): String =
        "OpenAiConfig(model=$model, baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "<empty>" else "<redacted>"})"

    companion object {
        /** Дефолты BYOK-провайдера — единственный источник (на них же ссылается [AiSettings]). */
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
