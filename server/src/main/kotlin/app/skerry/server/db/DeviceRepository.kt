package app.skerry.server.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Устройства аккаунта: регистрация при входе, список, отзыв, отметка активности. Все операции
 * жёстко скоупятся по `accountId` — deviceId уникален лишь в пределах аккаунта (см. составной PK
 * в [Devices] и security-ревью H2).
 */
class DeviceRepository(private val db: Database) {

    /** Идемпотентно в пределах аккаунта: повторный вход того же устройства обновляет имя/активность. */
    fun register(
        accountId: String,
        deviceId: String,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): Unit = transaction(db) {
        val existing = Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
        if (existing == null) {
            Devices.insert {
                it[id] = deviceId
                it[Devices.accountId] = accountId
                it[Devices.name] = name
                it[createdAt] = now
                it[lastSeenAt] = now
                it[revoked] = false
            }
        } else {
            Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
                it[Devices.name] = name
                it[lastSeenAt] = now
            }
        }
    }

    fun list(accountId: String): List<DeviceRow> = transaction(db) {
        Devices.selectAll().where { Devices.accountId eq accountId }.map { it.toDeviceRow() }
    }

    /**
     * Устройства инстанса для админ-консоли (zero-knowledge: только метаданные). Самые активные
     * первыми и с верхней границей [limit] — устройства не удаляются (только отзываются), поэтому
     * без лимита список и JSON-ответ росли бы безгранично (kotlin-ревью L).
     */
    fun listAll(limit: Int = 200): List<DeviceRow> = transaction(db) {
        Devices.selectAll()
            .orderBy(Devices.lastSeenAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toDeviceRow() }
    }

    fun find(accountId: String, deviceId: String): DeviceRow? = transaction(db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.toDeviceRow()
    }

    fun revoke(accountId: String, deviceId: String): Boolean = transaction(db) {
        Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
            it[revoked] = true
        } > 0
    }

    fun touch(accountId: String, deviceId: String, now: Long = System.currentTimeMillis()): Unit = transaction(db) {
        Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
            it[lastSeenAt] = now
        }
    }

    /**
     * Отозвано ли устройство. Неизвестное (отсутствующее) устройство считается отозванным —
     * JWT от устройства, которого нет в таблице (напр. после ручной чистки), отклоняется
     * (kotlin-ревью L2).
     */
    fun isRevoked(accountId: String, deviceId: String): Boolean = transaction(db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.get(Devices.revoked) ?: true
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toDeviceRow() = DeviceRow(
        id = this[Devices.id],
        accountId = this[Devices.accountId],
        name = this[Devices.name],
        createdAt = this[Devices.createdAt],
        lastSeenAt = this[Devices.lastSeenAt],
        revoked = this[Devices.revoked],
    )
}
