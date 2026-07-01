package app.skerry.shared.vault

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Тип события безопасности. Фиксированный набор того, что приложение реально контролирует и может
 * записать честно (без выдуманных данных): жизненный цикл мастер-пароля, биометрии и привязки
 * устройств. Подпись для UI локализуется отдельно — здесь только стабильные идентификаторы.
 */
enum class SecurityEventType {
    /** Vault создан (первичная установка мастер-пароля) — базовая точка для «последней смены пароля». */
    VaultCreated,

    /** Мастер-пароль сменён ([Vault.changePassword]). */
    MasterPasswordChanged,

    /** Включена разблокировка биометрией. */
    BiometricEnabled,

    /** Выключена разблокировка биометрией. */
    BiometricDisabled,

    /** Успешная разблокировка биометрией. */
    UnlockedBiometric,

    /** Привязано новое устройство (быстрый паринг) — [detail] несёт имя устройства. */
    DevicePaired,
}

/**
 * Одно событие журнала: тип, ISO-8601 штамп времени ([at], как в [Vault] — строкой из инъектируемых
 * часов) и необязательная деталь ([detail], например имя привязанного устройства).
 */
@Serializable
data class SecurityEvent(
    val type: SecurityEventType,
    val at: String,
    val detail: String? = null,
)

/**
 * Локальный журнал событий безопасности. Сознательно **не** синкается между устройствами: это
 * аудит действий на конкретном устройстве (как системный лог входов), а не общие данные аккаунта.
 */
interface SecurityLog {
    /** Записать событие с текущим временем (часы внутри реализации). */
    fun record(type: SecurityEventType, detail: String? = null)

    /** Последние события, новейшие первыми, не больше [limit]. */
    fun recent(limit: Int = 20): List<SecurityEvent>

    /**
     * Время последней смены мастер-пароля: штамп новейшего события [SecurityEventType.VaultCreated]
     * или [SecurityEventType.MasterPasswordChanged]. `null`, если таких событий ещё нет (например
     * vault создан до появления журнала) — UI показывает нейтральный текст, а не выдуманную дату.
     */
    fun lastPasswordChangeAt(): String?

    /** Очистить журнал (сброс vault / заводской сброс). */
    fun clear()
}

/**
 * Реализация [SecurityLog] поверх okio-[FileSystem]: JSON-массив событий в одном файле (кроссплатформенно
 * — desktop и Android одним кодом, как [FileVault]). Хранится в хронологическом порядке; при превышении
 * [max] вытесняются самые старые. Чтение битого/отсутствующего файла даёт пустой журнал (журнал —
 * вспомогательный, его порча не должна ничего ронять). Запись атомарна через временный файл.
 *
 * Мутации ([record]/[clear]) — read-modify-write, поэтому сериализуются мультиплатформенным
 * [SynchronizedObject] (как [FileVault]): журнал зовут с UI-корутины и потенциально из фонового
 * паринга/sync, две гонящиеся записи без блокировки потеряли бы событие (lost update).
 *
 * [harden] — платформенный хук, выставляющий готовому файлу приватные права (0600 на POSIX), чтобы
 * аудит-метаданные (имена привязанных устройств, штампы смены пароля) не были мир-читаемыми под общим
 * домашним каталогом. По умолчанию no-op (тесты на [okio.fakefilesystem]); JVM-сайты передают
 * `PrivateConfig.harden`. Хук зовётся на временном файле до [FileSystem.atomicMove], чтобы у цели не
 * было окна с правами по umask.
 */
class FileSecurityLog(
    private val path: Path,
    private val fileSystem: FileSystem,
    private val max: Int = 50,
    private val harden: (Path) -> Unit = {},
    private val clock: () -> String,
) : SecurityLog {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()

    override fun record(type: SecurityEventType, detail: String?): Unit = synchronized(lock) {
        val events = (read() + SecurityEvent(type, clock(), detail)).takeLast(max)
        write(events)
    }

    override fun recent(limit: Int): List<SecurityEvent> = synchronized(lock) {
        read().asReversed().take(limit)
    }

    override fun lastPasswordChangeAt(): String? = synchronized(lock) {
        read().lastOrNull {
            it.type == SecurityEventType.VaultCreated || it.type == SecurityEventType.MasterPasswordChanged
        }?.at
    }

    override fun clear(): Unit = synchronized(lock) {
        if (fileSystem.exists(path)) fileSystem.delete(path)
    }

    /** Прочитать журнал в хронологическом порядке; любая ошибка (нет файла/битый JSON) → пусто. */
    private fun read(): List<SecurityEvent> = runCatching {
        if (!fileSystem.exists(path)) return emptyList()
        val text = fileSystem.read(path) { readUtf8() }
        json.decodeFromString<List<SecurityEvent>>(text)
    }.getOrDefault(emptyList())

    private fun write(events: List<SecurityEvent>) {
        path.parent?.let { fileSystem.createDirectories(it) }
        val tmp = path.parent?.resolve("${path.name}.tmp") ?: "${path.name}.tmp".toPath()
        fileSystem.write(tmp) { writeUtf8(json.encodeToString(events)) }
        harden(tmp) // права ставим на tmp до move — у цели не будет окна с 0644
        fileSystem.atomicMove(tmp, path)
    }
}
