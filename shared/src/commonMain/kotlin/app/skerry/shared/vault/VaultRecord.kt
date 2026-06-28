package app.skerry.shared.vault

import kotlinx.serialization.Serializable

/** Тип записи vault — совпадает с моделью sync (`docs/skerry-sync-design.md` §2). */
@Serializable
enum class RecordType { HOST, GROUP, IDENTITY, CREDENTIAL, KNOWN_HOST, SNIPPET, TUNNEL }

/**
 * Запись локального vault в её зашифрованном виде на диске. Метаданные (`id`, `type`,
 * `version`, `updatedAt`, `deviceId`, `deleted`) хранятся открыто; `blob` —
 * XChaCha20-Poly1305(dataKey, payload) с AAD, привязанным к `id‖type` (защита от
 * перестановки записей между слотами). Та же структура — единица будущей E2E-синхронизации,
 * поэтому `version` — Lamport-счётчик для LWW, а удаление — tombstone (`deleted=true`),
 * а не физическое стирание.
 *
 * `blob` сериализуется kotlinx.serialization (массив байт). `equals`/`hashCode` дефолтные
 * (по ссылке для `ByteArray`) — запись используется как value-контейнер хранения, не как
 * ключ коллекции; сравнивать в тестах по полям, не по записи целиком.
 */
@Serializable
data class VaultRecord(
    val id: String,
    val type: RecordType,
    val version: Long,
    val updatedAt: String,
    val deviceId: String,
    val deleted: Boolean,
    val blob: ByteArray,
)
