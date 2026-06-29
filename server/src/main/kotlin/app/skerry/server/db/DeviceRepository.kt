package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * Устройства аккаунта: регистрация при входе, список, отзыв, отметка активности. Все операции
 * жёстко скоупятся по `accountId` — deviceId уникален лишь в пределах аккаунта (см. составной PK
 * в [Devices] и security-ревью H2).
 */
class DeviceRepository(private val db: Database) {

    /**
     * Идемпотентно в пределах аккаунта: повторный вход того же устройства обновляет имя/активность
     * и **снимает отзыв** (`revoked=false`). Повторная аутентификация (этот метод зовётся из
     * register и srp/verify) доказывает знание мастер-пароля = владение аккаунтом, поэтому
     * переотзывать устройство нельзя — иначе отозванное устройство с верным паролем оставалось бы
     * запертым навсегда (register→409, а каждый sync-запрос→401 по [isRevoked]). Revoke в этой модели
     * гасит текущие токены до следующего входа, а не банит устройство; постоянный бан = ротация пароля.
     */
    suspend fun register(
        accountId: String,
        deviceId: String,
        name: String,
        platform: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        // Кап под varchar(64): клиентское поле, длинное значение иначе валит запись в 500
        // (truncation в SQLite, исключение в PostgreSQL) вместо тихого усечения (kotlin-ревью L).
        val plat = platform?.take(64)
        // name — text-колонка, но всё равно клиентский ввод: режем до разумного предела, чтобы
        // нельзя было раздуть строку и админ-консоль произвольно длинным именем (kotlin-ревью L).
        val safeName = name.take(128)
        val existing = Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
        if (existing == null) {
            Devices.insert {
                it[id] = deviceId
                it[Devices.accountId] = accountId
                it[Devices.name] = safeName
                it[Devices.platform] = plat
                it[createdAt] = now
                it[lastSeenAt] = now
                it[revoked] = false
            }
            false
        } else {
            val wasRevoked = existing[Devices.revoked]
            Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
                it[Devices.name] = safeName
                // platform пишем только когда клиент его прислал — иначе не затираем известное значение.
                if (plat != null) it[Devices.platform] = plat
                it[lastSeenAt] = now
                it[revoked] = false // повторная аутентификация переактивирует устройство (см. KDoc)
            }
            // true ⇒ устройство было отозвано и сейчас переактивировано — сигнал для аудита (consoleadmin
            // видит «revoked» красным, но без этого события не узнал бы о возврате с верным паролем).
            wasRevoked
        }
    }

    suspend fun list(accountId: String): List<DeviceRow> = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll().where { Devices.accountId eq accountId }.map { it.toDeviceRow() }
    }

    /**
     * Устройства инстанса для админ-консоли (zero-knowledge: только метаданные). Самые активные
     * первыми и с верхней границей [limit] — устройства не удаляются (только отзываются), поэтому
     * без лимита список и JSON-ответ росли бы безгранично (kotlin-ревью L).
     */
    suspend fun listAll(limit: Int = 200): List<DeviceRow> = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll()
            .orderBy(Devices.lastSeenAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toDeviceRow() }
    }

    suspend fun find(accountId: String, deviceId: String): DeviceRow? = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.toDeviceRow()
    }

    suspend fun revoke(accountId: String, deviceId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
            it[revoked] = true
        } > 0
    }

    /**
     * Отмечает активность. Если передан [syncVersion] (курсор после pull/push), фиксирует, до
     * какого состояния устройство дочиталось/дописалось — открытый счётчик для админ-консоли.
     */
    suspend fun touch(
        accountId: String,
        deviceId: String,
        now: Long = System.currentTimeMillis(),
        syncVersion: Long? = null,
    ): Unit = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
            it[lastSeenAt] = now
            if (syncVersion != null) it[lastSyncVersion] = syncVersion
        }
    }

    /**
     * Отозвано ли устройство. Неизвестное (отсутствующее) устройство считается отозванным —
     * JWT от устройства, которого нет в таблице (напр. после ручной чистки), отклоняется
     * (kotlin-ревью L2).
     */
    suspend fun isRevoked(accountId: String, deviceId: String): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.get(Devices.revoked) ?: true
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toDeviceRow() = DeviceRow(
        id = this[Devices.id],
        accountId = this[Devices.accountId],
        name = this[Devices.name],
        platform = this[Devices.platform],
        createdAt = this[Devices.createdAt],
        lastSeenAt = this[Devices.lastSeenAt],
        lastSyncVersion = this[Devices.lastSyncVersion],
        revoked = this[Devices.revoked],
    )
}
