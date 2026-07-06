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
 * Account devices: registration on login, listing, revocation, activity tracking. All operations
 * are scoped by `accountId` — deviceId is unique only within an account (see the composite PK in
 * [Devices]).
 */
class DeviceRepository(private val db: Database) {

    /**
     * Idempotent within an account: re-registering the same device updates name/activity and
     * **clears revocation** (`revoked=false`) — re-authentication proves master password
     * knowledge, so a revoked device with the correct password must not stay locked out permanently.
     */
    suspend fun register(
        accountId: String,
        deviceId: String,
        name: String,
        platform: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean = newSuspendedTransaction(Dispatchers.IO, db) {
        // Cap to varchar(64): a longer client value would otherwise fail the insert with a 500
        // instead of silently truncating.
        val plat = platform?.take(64)
        // name is a text column but still client input: cap it to a reasonable length to
        // prevent an arbitrarily long name.
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
                // Only write platform when the client sent one, so we don't overwrite a known value.
                if (plat != null) it[Devices.platform] = plat
                it[lastSeenAt] = now
                it[revoked] = false // re-authentication reactivates the device
            }
            // true means the device was revoked and is now reactivated — signal for the audit log.
            wasRevoked
        }
    }

    suspend fun list(accountId: String): List<DeviceRow> = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll().where { Devices.accountId eq accountId }.map { it.toDeviceRow() }
    }

    /**
     * Instance-wide devices for the admin console (zero-knowledge: metadata only). Most recently
     * active first, capped at [limit] — devices are never deleted, only revoked, so an unbounded
     * list would grow indefinitely.
     */
    suspend fun listAll(limit: Int = 200): List<DeviceRow> = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll()
            .orderBy(Devices.lastSeenAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toDeviceRow() }
    }

    /** Total devices on the instance, for an accurate "N of M" in the console. */
    suspend fun count(): Long = newSuspendedTransaction(Dispatchers.IO, db) {
        Devices.selectAll().count()
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
     * Records activity. If [syncVersion] (the cursor after a pull/push) is given, records how
     * far the device has synced — an open counter for the admin console.
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
     * Whether the device is revoked. An unknown (missing) device counts as revoked, so a JWT for
     * a device no longer in the table is rejected.
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
