package app.skerry.shared.vault

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

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
 * Файловый [Vault] на okio — один и тот же код для desktop (JVM), Android и iOS: I/O спрятан за
 * [FileSystem] (десктоп/мобайл подают `FileSystem.SYSTEM`, тесты — `FakeFileSystem`). По образцу
 * [app.skerry.shared.host.FileHostStore]: in-memory кеш записей, файл переписывается целиком
 * атомарно (tmp + [FileSystem.atomicMove]); битый файл при unlock — [UnlockResult.Corrupted].
 * Жизненный цикл добавляет `dataKey` в памяти — он есть только между [unlock]/[create] и [lock] и
 * наружу не выходит. Метку времени записей даёт [now] (инжектится — нет привязки к платформенным
 * часам в commonMain; тесты передают детерминированную заглушку).
 *
 * Атомарность состояния: мутаторы сначала записывают новый снимок на диск ([writeFile]) и лишь
 * **после** успеха коммитят его в поля. Если запись упала — кеш, `meta` и `dataKey` остаются
 * прежними, файл не рассинхронизируется. Все публичные методы синхронизированы (vault зовут из
 * UI-корутины и потенциально из фонового sync) через мультиплатформенный [SynchronizedObject].
 * Переданные пароли затираются.
 */
class FileVault(
    private val path: Path,
    private val crypto: VaultCrypto,
    private val deviceId: String,
    private val fileSystem: FileSystem,
    private val now: () -> String,
) : Vault {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    private var dataKey: DataKey? = null
    private var meta: VaultMeta? = null
    private val records = mutableListOf<VaultRecord>()

    override fun exists(): Boolean = fileSystem.exists(path)

    override val isUnlocked: Boolean get() = synchronized(lock) { dataKey != null }

    override fun create(password: CharArray): Unit = synchronized(lock) {
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

    override fun unlock(password: CharArray): UnlockResult = synchronized(lock) {
        try {
            val body = runCatching {
                json.decodeFromString<VaultFileBody>(fileSystem.read(path) { readUtf8() })
            }.getOrElse { return@synchronized UnlockResult.Corrupted }
            val masterKey = crypto.deriveMasterKey(password, body.meta.salt)
            val unwrapped = crypto.unwrapDataKey(masterKey, body.meta.wrappedDataKey)
            masterKey.bytes.fill(0)
            if (unwrapped == null) return@synchronized UnlockResult.WrongPassword
            dataKey?.bytes?.fill(0) // повторный unlock не должен осиротить прежний ключ
            dataKey = unwrapped
            meta = body.meta
            records.clear()
            records.addAll(body.records)
            UnlockResult.Success
        } finally {
            password.fill(' ')
        }
    }

    override fun lock(): Unit = synchronized(lock) {
        dataKey?.bytes?.fill(0)
        dataKey = null
        meta = null
        records.clear()
    }

    override fun records(): List<VaultRecord> = synchronized(lock) {
        requireUnlocked()
        records.toList()
    }

    override fun openPayload(id: String): ByteArray? = synchronized(lock) {
        val key = requireUnlocked()
        val record = records.firstOrNull { it.id == id } ?: return@synchronized null
        // tombstone не отдаёт payload: blob удалённой записи сохранён для sync, но наружу не выходит.
        if (record.deleted) return@synchronized null
        crypto.open(key, record.blob, aad(record.id, record.type))
    }

    override fun put(id: String, type: RecordType, payload: ByteArray): Unit = synchronized(lock) {
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

    override fun remove(id: String): Unit = synchronized(lock) {
        requireUnlocked()
        val currentMeta = meta ?: error("unlocked vault has no metadata")
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val current = records[index]
        if (current.deleted) return@synchronized
        // blob сохраняется в tombstone намеренно: ciphertext нужен LWW-sync, чтобы донести удаление
        // до других устройств (открытым он всё равно не выдаётся — см. openPayload). Не очищать.
        val tombstone = current.copy(deleted = true, version = current.version + 1, updatedAt = now())
        val updated = records.toMutableList().also { it[index] = tombstone }
        writeFile(currentMeta, updated)
        records.clear(); records.addAll(updated)
    }

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean = synchronized(lock) {
        try {
            val currentMeta = meta
            val key = dataKey
            check(currentMeta != null && key != null) { "vault is locked" }
            val oldMaster = crypto.deriveMasterKey(oldPassword, currentMeta.salt)
            val verified = crypto.unwrapDataKey(oldMaster, currentMeta.wrappedDataKey)
            oldMaster.bytes.fill(0)
            if (verified == null) return@synchronized false
            verified.bytes.fill(0) // нужна была только проверка старого пароля
            val newSalt = crypto.newSalt()
            val newMaster = crypto.deriveMasterKey(newPassword, newSalt)
            val newWrapped = crypto.wrapDataKey(newMaster, key)
            newMaster.bytes.fill(0)
            val newMeta = currentMeta.copy(salt = newSalt, wrappedDataKey = newWrapped)
            writeFile(newMeta, records.toList()) // упадёт — meta не подменяется
            meta = newMeta
            true
        } finally {
            oldPassword.fill(' ')
            newPassword.fill(' ')
        }
    }

    private fun requireUnlocked(): DataKey =
        dataKey ?: throw IllegalStateException("vault is locked")

    /**
     * Атомарно записать снимок vault. Чистая функция от аргументов — поля не читает и не пишет.
     * [FileSystem.atomicMove] заменяет существующую цель на всех таргетах Skerry (okio: NIO —
     * `ATOMIC_MOVE+REPLACE_EXISTING`; legacy/native POSIX `rename(2)`; `FakeFileSystem`), поэтому
     * отдельного «move с перезаписью» не нужно. Если move не поддержан — исключение всплывает, а
     * поля остаются прежними (коммит идёт после persist): данные не теряются, ошибка видна выше.
     */
    private fun writeFile(meta: VaultMeta, records: List<VaultRecord>) {
        path.parent?.let { fileSystem.createDirectories(it) }
        val tmp = path.parent?.resolve("${path.name}.tmp") ?: "${path.name}.tmp".toPath()
        fileSystem.write(tmp) { writeUtf8(json.encodeToString(VaultFileBody(meta, records))) }
        fileSystem.atomicMove(tmp, path)
    }

    /**
     * Стабильный AAD слота записи: `id` + [AAD_SEP] + `type.name`. Привязывает blob к id/типу,
     * чтобы запись нельзя было подставить в чужой слот (и наоборот). Разделитель вынесен в
     * константу с явным escape, чтобы он был виден в исходнике и не потерялся при правке.
     */
    private fun aad(id: String, type: RecordType): ByteArray =
        "$id$AAD_SEP${type.name}".encodeToByteArray()

    private companion object {
        const val FORMAT_VERSION = 1

        /** Unit Separator (U+001F) между id и типом в AAD. Явный escape — управляющий байт 0x1F. */
        const val AAD_SEP = ""
    }
}
