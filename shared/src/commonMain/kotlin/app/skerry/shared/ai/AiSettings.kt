package app.skerry.shared.ai

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Настройки внешнего AI-провайдера (BYOK), хранимые в зашифрованном Vault. Пустой [apiKey] значит
 * «не настроено» ([isConfigured] == false) — AI-возможности в UI остаются выключенными.
 */
@Serializable
data class AiSettings(
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val baseUrl: String = "https://api.openai.com/v1",
) {
    /** Настроен ли внешний провайдер (есть непустой ключ). */
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

    fun load(): AiSettings {
        if (!vault.isUnlocked) return AiSettings()
        val record = vault.records().firstOrNull { it.id == SETTINGS_ID && it.type == RecordType.SETTINGS && !it.deleted }
            ?: return AiSettings()
        return decode(runCatching { vault.openPayload(record.id) }.getOrNull()) ?: AiSettings()
    }

    fun save(settings: AiSettings) {
        vault.put(SETTINGS_ID, RecordType.SETTINGS, json.encodeToString(settings).encodeToByteArray())
    }

    private fun decode(payload: ByteArray?): AiSettings? =
        payload?.let { runCatching { json.decodeFromString<AiSettings>(it.decodeToString()) }.getOrNull() }

    companion object {
        /** Стабильный id singleton-записи AI-настроек в vault. */
        const val SETTINGS_ID = "ai.settings"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
