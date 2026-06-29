package app.skerry.shared.vault

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * Файловый [Vault] на okio — один и тот же код для desktop (JVM) и Android: I/O спрятан за
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

    // Сигнал «локальная мутация» для live-sync (см. [Vault.localChanges]). replay=0 + DROP_OLDEST:
    // сигнал идемпотентен (важен факт изменения, не их число), а tryEmit без подписчика не должен ни
    // блокировать мутатор, ни падать. Эмитим ПОСЛЕ успешного commit (после writeFile), вне зависимости
    // от того, подписан ли координатор.
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val localChanges: Flow<Unit> = _localChanges

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

    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = synchronized(lock) {
        val body = runCatching {
            json.decodeFromString<VaultFileBody>(fileSystem.read(path) { readUtf8() })
        }.getOrElse {
            dataKey.bytes.fill(0) // присвоить нечего — не оставлять переданный ключ висеть в памяти
            return@synchronized UnlockResult.Corrupted
        }
        this.dataKey?.bytes?.fill(0) // повторный unlock не должен осиротить прежний ключ
        this.dataKey = dataKey // присваиваем переданный ключ — вызывающий его не затирает (см. контракт)
        meta = body.meta
        records.clear()
        records.addAll(body.records)
        UnlockResult.Success
    }

    override fun exportDataKey(): DataKey? = synchronized(lock) {
        dataKey?.let { DataKey(it.bytes.copyOf()) } // копия: вызывающий затрёт её, не тронув живой ключ
    }

    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = synchronized(lock) {
        try {
            val key = dataKey
            check(key != null) { "vault is locked" }
            // Тот же ключ (основное устройство переподключается своим же) → не переписываем meta, иначе
            // молча сменили бы пароль vault на переданный. Лишнюю копию ключа не оставляем в памяти.
            if (newDataKey.bytes.contentEquals(key.bytes)) {
                newDataKey.bytes.fill(0)
                return@synchronized false
            }
            val newSalt = crypto.newSalt()
            val newMaster = crypto.deriveMasterKey(password, newSalt)
            val newWrapped = crypto.wrapDataKey(newMaster, newDataKey)
            newMaster.bytes.fill(0)
            val newMeta = VaultMeta(FORMAT_VERSION, newSalt, newWrapped)
            // Записи остаются как есть: синканутые придут под новым ключом, локальные (под старым)
            // станут нечитаемы. Коммит после persist — упадёт запись, поля не тронуты.
            writeFile(newMeta, records.toList())
            key.bytes.fill(0) // старый ключ больше не нужен
            dataKey = newDataKey
            meta = newMeta
            true
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

    override fun reset(): Unit = synchronized(lock) {
        dataKey?.bytes?.fill(0)
        dataKey = null
        // wrappedDataKey — обёрнутый под мастер-паролем ключ (ciphertext, бесполезен без пароля); при
        // безвозвратном сбросе затираем и его, чтобы не оставлять материал ключа в куче после wipe.
        meta?.wrappedDataKey?.fill(0)
        meta = null
        records.clear()
        // mustExist=false: сброс идемпотентен и не должен падать на уже отсутствующем/битом файле.
        fileSystem.delete(path, mustExist = false)
    }

    override fun records(): List<VaultRecord> = synchronized(lock) {
        requireUnlocked()
        records.toList()
    }

    override fun syncMeta(): SyncMeta? = synchronized(lock) {
        val m = meta ?: return@synchronized null // meta есть только на разблокированном vault
        SyncMeta(m.salt.copyOf(), m.wrappedDataKey.copyOf()) // копии: вызывающий волен затирать
    }

    override fun mergeRemote(remote: List<VaultRecord>): List<VaultRecord> = synchronized(lock) {
        requireUnlocked()
        val currentMeta = meta ?: error("unlocked vault has no metadata")
        val working = records.toMutableList()
        val applied = mutableListOf<VaultRecord>()
        for (r in remote) {
            val index = working.indexOfFirst { it.id == r.id }
            val local = if (index >= 0) working[index] else null
            // LWW: бóльшая version, при равенстве — лексикографически больший deviceId.
            val wins = local == null ||
                r.version > local.version ||
                (r.version == local.version && r.deviceId > local.deviceId)
            if (wins) {
                if (index >= 0) working[index] = r else working += r
                applied += r
            }
        }
        if (applied.isNotEmpty()) {
            writeFile(currentMeta, working) // упадёт — кеш не тронут (коммит после persist)
            records.clear(); records.addAll(working)
        }
        applied
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
        _localChanges.tryEmit(Unit) // commit удался → разбудить live-sync push
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
        _localChanges.tryEmit(Unit) // локальное удаление → разбудить live-sync push
    }

    override fun compact(ids: List<String>): Unit = synchronized(lock) {
        requireUnlocked()
        if (ids.isEmpty()) return@synchronized
        val currentMeta = meta ?: error("unlocked vault has no metadata")
        val drop = ids.toHashSet()
        // Удаляем только НАДГРОБИЯ из списка: живую (более новую, ещё не запушенную) запись с тем же
        // id сохраняем — её увезёт следующий push, иначе потеряли бы непротолкнутые данные.
        val updated = records.filterNot { it.id in drop && it.deleted }
        if (updated.size == records.size) return@synchronized // нечего компактить — файл не трогаем
        writeFile(currentMeta, updated) // упадёт — кеш не тронут (коммит после persist)
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

    override fun verifyPassword(password: CharArray): Boolean = synchronized(lock) {
        try {
            // Сверяем по метаданным открытой сессии (как changePassword сверяет старый пароль): vault
            // должен быть разблокирован. dataKey/записи не трогаем — это только проверка личности.
            // dataKey == null дублирует условие meta == null (lock() чистит оба), но делает инвариант
            // «только на открытом vault» явным и устойчивым к будущему «meta-only»-чтению.
            val currentMeta = meta ?: return@synchronized false
            if (dataKey == null) return@synchronized false
            val master = crypto.deriveMasterKey(password, currentMeta.salt)
            val verified = crypto.unwrapDataKey(master, currentMeta.wrappedDataKey)
            master.bytes.fill(0)
            if (verified == null) return@synchronized false
            verified.bytes.fill(0) // нужна была только проверка — развёрнутый ключ не оставляем в памяти
            true
        } finally {
            password.fill(' ')
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
