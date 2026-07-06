package app.skerry.shared.ai

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultSingletonStore
import kotlinx.serialization.Serializable

/**
 * Default AI provider: external OpenAI-compatible (BYOK), local model, or [OFF] — AI disabled
 * app-wide (global kill switch, overrides per-host policies, see [AiRouter]).
 */
@Serializable
enum class AiProviderKind { CLOUD, DEVICE, OFF }

/**
 * AI provider settings stored in the encrypted vault: default provider choice ([provider]) plus
 * config for both branches — BYOK ([apiKey]/[model]/[baseUrl]) and local model ([localModelId],
 * an id from [app.skerry.shared.ai.local.LocalModelCatalog]). Old records without the new fields
 * read as defaults (CLOUD).
 *
 * Settings sync across devices, but whether the local model is downloaded is a device property:
 * readiness is checked on the spot ([AiRouter] + `LocalModelStore`), not from settings.
 */
@Serializable
data class AiSettings(
    val apiKey: String = "",
    val model: String = OpenAiConfig.DEFAULT_MODEL,
    val baseUrl: String = OpenAiConfig.DEFAULT_BASE_URL,
    val provider: AiProviderKind = AiProviderKind.CLOUD,
    val localModelId: String = "",
) {
    /** Whether the external (BYOK) provider is configured — a non-blank key. See [AiRouter] for the local branch. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun toOpenAiConfig(): OpenAiConfig = OpenAiConfig(apiKey = apiKey, model = model, baseUrl = baseUrl)

    // apiKey is a secret: excluded from toString to avoid leaking into logs/crash dumps.
    override fun toString(): String =
        "AiSettings(model=$model, baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "<empty>" else "<redacted>"})"
}

/**
 * Reads/writes [AiSettings] as a singleton [RecordType.SETTINGS] record in [Vault] (fixed
 * [SETTINGS_ID], mirrors `SyncSettingsStore`). On a locked vault, [load] returns the default
 * (unconfigured); [save] requires an unlocked vault. A corrupt/missing payload falls back to default.
 *
 * [RecordType.SETTINGS] records always sync — the key (inside the E2E ciphertext) becomes
 * available on all of the user's devices; the server only sees ciphertext (zero-knowledge).
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
        /** Stable id of the AI settings singleton record in the vault. */
        const val SETTINGS_ID = "ai.settings"
    }
}
