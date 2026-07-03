package app.skerry.shared.ai

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultSingletonStore
import kotlinx.serialization.Serializable

/** Провайдер AI по умолчанию: внешний OpenAI-совместимый (BYOK) или локальная модель. */
@Serializable
enum class AiProviderKind { CLOUD, DEVICE }

/**
 * Настройки AI-провайдера, хранимые в зашифрованном Vault: выбор провайдера по умолчанию
 * ([provider]) плюс конфиг обеих веток — BYOK ([apiKey]/[model]/[baseUrl]) и локальной модели
 * ([localModelId], id из [app.skerry.shared.ai.local.LocalModelCatalog]). Старые записи без новых
 * полей читаются дефолтами (CLOUD) — поведение BYOK-пользователей не меняется.
 *
 * Настройки синкаются между устройствами, но скачанность локальной модели — свойство устройства:
 * готовность проверяется на месте ([AiRouter] + `LocalModelStore`), не из настроек.
 */
@Serializable
data class AiSettings(
    val apiKey: String = "",
    val model: String = OpenAiConfig.DEFAULT_MODEL,
    val baseUrl: String = OpenAiConfig.DEFAULT_BASE_URL,
    val provider: AiProviderKind = AiProviderKind.CLOUD,
    val localModelId: String = "",
) {
    /** Настроен ли внешний (BYOK) провайдер — есть непустой ключ. Про локальную ветку см. [AiRouter]. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun toOpenAiConfig(): OpenAiConfig = OpenAiConfig(apiKey = apiKey, model = model, baseUrl = baseUrl)

    // apiKey — секрет: не печатаем его в toString (иначе утечёт в логи/крэш-дампы).
    override fun toString(): String =
        "AiSettings(model=$model, baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "<empty>" else "<redacted>"})"
}

/**
 * Чтение/запись [AiSettings] как singleton-записи [RecordType.SETTINGS] в [Vault] (фиксированный
 * [SETTINGS_ID], по образцу `SyncSettingsStore`). На залоченном vault [load] отдаёт дефолт
 * (не настроено), [save] требует разблокированного vault. Битый/отсутствующий payload → дефолт.
 *
 * Запись типа [RecordType.SETTINGS] синхронизируется всегда — ключ (в шифроблобе E2E) становится
 * доступен на всех устройствах пользователя; сервер видит только шифротекст (zero-knowledge).
 */
class AiSettingsStore(private val vault: Vault) {

    private val store = VaultSingletonStore(vault, SETTINGS_ID, RecordType.SETTINGS, AiSettings.serializer()) {
        AiSettings()
    }

    fun load(): AiSettings = store.load()

    fun save(settings: AiSettings) {
        store.save(settings)
    }

    companion object {
        /** Стабильный id singleton-записи AI-настроек в vault. */
        const val SETTINGS_ID = "ai.settings"
    }
}
