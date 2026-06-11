package app.skerry.shared.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Открытая часть файла vault: версия формата и материал для деривации/обёртки dataKey. */
@Serializable
internal data class VaultMeta(
    val formatVersion: Int,
    val salt: ByteArray,
    val wrappedDataKey: ByteArray,
)

/** Корень файла vault: [VaultMeta] + зашифрованные записи. */
@Serializable
internal data class VaultFileBody(
    val meta: VaultMeta,
    val records: List<VaultRecord>,
)

/**
 * Файловый [Vault] (desktop). По образцу [app.skerry.shared.host.FileHostStore]: in-memory кеш
 * записей, файл переписывается целиком атомарно (tmp + ATOMIC_MOVE); битый файл при unlock —
 * [UnlockResult.Corrupted]. Жизненный цикл добавляет `dataKey` в памяти — он есть только между
 * [unlock]/[create] и [lock] и наружу не выходит.
 *
 * Атомарность состояния: мутаторы сначала записывают новый снимок на диск ([writeFile]) и лишь
 * **после** успеха коммитят его в поля. Если запись упала — кеш, `meta` и `dataKey` остаются
 * прежними, файл не рассинхронизируется. Все публичные методы синхронизированы (vault зовут из
 * UI-корутины и потенциально из фонового sync). Переданные пароли затираются.
 */
class FileVault(
    private val path: Path,
    private val crypto: VaultCrypto,
    private val deviceId: String,
) : Vault {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var dataKey: DataKey? = null
    private var meta: VaultMeta? = null
    private val records = mutableListOf<VaultRecord>()

    override fun exists(): Boolean = Files.exists(path)

    @get:Synchronized
    override val isUnlocked: Boolean get() = dataKey != null

    @Synchronized
    override fun create(password: CharArray) {
        try {
            val salt = crypto.newSalt()
            val masterKey = crypto.deriveMasterKey(password, salt)
            val freshDataKey = crypto.newDataKey()
            val wrapped = crypto.wrapDataKey(masterKey, freshDataKey)
            masterKey.bytes.fill(0)
            val newMeta = VaultMeta(FORMAT_VERSION, salt, wrapped)
            try {
                writeFile(newMeta, emptyList())
            } catch (e: Throwable) {
                freshDataKey.bytes.fill(0) // запись не удалась — ключ никуда не уходит
                throw e
            }
            dataKey?.bytes?.fill(0) // не осиротить старый ключ при повторном create
            dataKey = freshDataKey
            meta = newMeta
            records.clear()
        } finally {
            password.fill(' ')
        }
    }

    @Synchronized
    override fun unlock(password: CharArray): UnlockResult {
        try {
            val body = runCatching { json.decodeFromString<VaultFileBody>(Files.readString(path)) }
                .getOrElse { return UnlockResult.Corrupted }
            val masterKey = crypto.deriveMasterKey(password, body.meta.salt)
            val unwrapped = crypto.unwrapDataKey(masterKey, body.meta.wrappedDataKey)
            masterKey.bytes.fill(0)
            if (unwrapped == null) return UnlockResult.WrongPassword
            dataKey?.bytes?.fill(0) // повторный unlock не должен осиротить прежний ключ
            dataKey = unwrapped
            meta = body.meta
            records.clear()
            records.addAll(body.records)
            return UnlockResult.Success
        } finally {
            password.fill(' ')
        }
    }

    @Synchronized
    override fun lock() {
        dataKey?.bytes?.fill(0)
        dataKey = null
        meta = null
        records.clear()
    }

    @Synchronized
    override fun records(): List<VaultRecord> {
        requireUnlocked()
        return records.toList()
    }

    @Synchronized
    override fun openPayload(id: String): ByteArray? {
        val key = requireUnlocked()
        val record = records.firstOrNull { it.id == id } ?: return null
        return crypto.open(key, record.blob, aad(record.id, record.type))
    }

    @Synchronized
    override fun put(id: String, type: RecordType, payload: ByteArray) {
        val key = requireUnlocked()
        val currentMeta = meta ?: error("unlocked vault has no metadata")
        val blob = crypto.seal(key, payload, aad(id, type))
        val index = records.indexOfFirst { it.id == id }
        val version = if (index >= 0) records[index].version + 1 else 1L
        val record = VaultRecord(id, type, version, now(), deviceId, deleted = false, blob = blob)
        val updated = records.toMutableList().also {
            if (index >= 0) it[index] = record else it += record
        }
        writeFile(currentMeta, updated) // упадёт — кеш не тронут
        records.clear(); records.addAll(updated)
    }

    @Synchronized
    override fun remove(id: String) {
        requireUnlocked()
        val currentMeta = meta ?: error("unlocked vault has no metadata")
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = records[index]
        if (current.deleted) return
        val tombstone = current.copy(deleted = true, version = current.version + 1, updatedAt = now())
        val updated = records.toMutableList().also { it[index] = tombstone }
        writeFile(currentMeta, updated)
        records.clear(); records.addAll(updated)
    }

    @Synchronized
    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        try {
            val currentMeta = meta
            val key = dataKey
            check(currentMeta != null && key != null) { "vault is locked" }
            val oldMaster = crypto.deriveMasterKey(oldPassword, currentMeta.salt)
            val verified = crypto.unwrapDataKey(oldMaster, currentMeta.wrappedDataKey)
            oldMaster.bytes.fill(0)
            if (verified == null) return false
            verified.bytes.fill(0) // нужна была только проверка старого пароля
            val newSalt = crypto.newSalt()
            val newMaster = crypto.deriveMasterKey(newPassword, newSalt)
            val newWrapped = crypto.wrapDataKey(newMaster, key)
            newMaster.bytes.fill(0)
            val newMeta = currentMeta.copy(salt = newSalt, wrappedDataKey = newWrapped)
            writeFile(newMeta, records.toList()) // упадёт — meta не подменяется
            meta = newMeta
            return true
        } finally {
            oldPassword.fill(' ')
            newPassword.fill(' ')
        }
    }

    private fun requireUnlocked(): DataKey =
        dataKey ?: throw IllegalStateException("vault is locked")

    /** Атомарно записать снимок vault. Чистая функция от аргументов — поля не читает и не пишет. */
    private fun writeFile(meta: VaultMeta, records: List<VaultRecord>) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(VaultFileBody(meta, records)))
        runCatching { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    /** Стабильный AAD слота записи: `id␟type` (␟ = U+001F). Привязывает blob к id/типу. */
    private fun aad(id: String, type: RecordType): ByteArray =
        "$id${type.name}".encodeToByteArray()

    private fun now(): String = Instant.now().toString()

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
