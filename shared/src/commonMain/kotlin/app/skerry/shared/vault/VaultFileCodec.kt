package app.skerry.shared.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path

/**
 * Plaintext part of the vault file: format version and material for dataKey derivation/wrapping.
 * [keyCheck] is an empty AEAD box sealed under the dataKey: the one thing a key handed in from outside
 * ([FileVault.unlockWithDataKey]) can be checked against without the password. `null` in files written
 * before it existed; filled in by the next password unlock. Optional, so older clients read the file.
 */
@Serializable
internal data class VaultMeta(
    val formatVersion: Int,
    val salt: ByteArray,
    val wrappedDataKey: ByteArray,
    val keyCheck: ByteArray? = null,
)

/** Root of the vault file: [VaultMeta] + encrypted records. */
@Serializable
internal data class VaultFileBody(
    val meta: VaultMeta,
    val records: List<VaultRecord>,
)

internal class ParsedVaultBody(
    val meta: VaultMeta,
    val records: List<VaultRecord>,
    val unknown: List<JsonElement>,
)

/** On-disk codec: direct serialization for known records, tolerant preservation of future types. */
internal class VaultFileCodec(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val harden: (Path) -> Unit,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun read(): ParsedVaultBody {
        val text = fileSystem.read(path) { readUtf8() }
        // The usual file needs no JSON tree or one JsonElement allocation per ciphertext byte.
        runCatching { json.decodeFromString(VaultFileBody.serializer(), text) }.getOrNull()?.let {
            return ParsedVaultBody(it.meta, it.records, emptyList())
        }
        val root = json.parseToJsonElement(text).jsonObject
        val meta = json.decodeFromJsonElement(VaultMeta.serializer(), root.getValue("meta"))
        val known = mutableListOf<VaultRecord>()
        val unknown = mutableListOf<JsonElement>()
        (root["records"] as? JsonArray)?.forEach { element ->
            runCatching { json.decodeFromJsonElement(VaultRecord.serializer(), element) }
                .onSuccess { known += it }
                .onFailure { unknown += element }
        }
        return ParsedVaultBody(meta, known, unknown)
    }

    fun write(meta: VaultMeta, records: List<VaultRecord>, unknown: List<JsonElement>) {
        val text = if (unknown.isEmpty()) {
            json.encodeToString(VaultFileBody.serializer(), VaultFileBody(meta, records))
        } else {
            // Preserve records this client cannot understand; an older client must never drop them.
            val body = buildJsonObject {
                put("meta", json.encodeToJsonElement(VaultMeta.serializer(), meta))
                put("records", buildJsonArray {
                    records.forEach { add(json.encodeToJsonElement(VaultRecord.serializer(), it)) }
                    unknown.forEach { add(it) }
                })
            }
            json.encodeToString(JsonObject.serializer(), body)
        }
        atomicWriteUtf8(fileSystem, path, text, harden)
    }
}
