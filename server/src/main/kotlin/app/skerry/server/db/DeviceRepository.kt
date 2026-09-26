package app.skerry.server.db

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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
     * The tokens it held before the revoke stay dead: [revokeDevices] moved its generation on.
     */
    suspend fun register(
        accountId: String,
        deviceId: String,
        name: String,
        platform: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean = dbTransaction(db) {
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
                // Covers a row revoked before generations existed, whose old tokens still read as 0.
                if (wasRevoked) it[tokenGeneration] = tokenGeneration + 1L
            }
            // true means the device was revoked and is now reactivated — signal for the audit log.
            wasRevoked
        }
    }

    suspend fun list(accountId: String): List<DeviceRow> = dbTransaction(db) {
        // Most recently active first, like [listAll]. The account zone calls a device stale after a
        // week of silence, so this ordering is also what keeps the live ones above the quiet ones.
        Devices.selectAll()
            .where { Devices.accountId eq accountId }
            .orderBy(Devices.lastSeenAt to SortOrder.DESC)
            .map { it.toDeviceRow() }
    }

    /**
     * Instance-wide devices for the admin console (zero-knowledge: metadata only). Most recently
     * active first, capped at [limit]. Revoked devices are excluded: they're inert (no sync) and are
     * never deleted, so including them would let the list grow without bound. A revoked device that
     * re-authenticates clears its revocation and reappears here.
     */
    suspend fun listAll(limit: Int = 200, accountId: String? = null, offset: Long = 0): List<DeviceRow> = dbTransaction(db) {
        Devices.selectAll()
            .where { activeDevices(accountId) }
            .orderBy(Devices.lastSeenAt to SortOrder.DESC)
            .limit(limit).offset(offset)
            .map { it.toDeviceRow() }
    }

    /** Active (non-revoked) devices on the instance, matching [listAll] for an accurate "N of M". */
    suspend fun count(accountId: String? = null): Long = dbTransaction(db) {
        Devices.selectAll().where { activeDevices(accountId) }.count()
    }

    /** The listing predicate, shared by [listAll] and [count] so both speak about the same list. */
    private fun activeDevices(accountId: String?) =
        (Devices.revoked eq false).let { active ->
            if (accountId == null) active else active and (Devices.accountId eq accountId)
        }

    suspend fun find(accountId: String, deviceId: String): DeviceRow? = dbTransaction(db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.toDeviceRow()
    }

    suspend fun revoke(accountId: String, deviceId: String): Boolean = dbTransaction(db) {
        revokeDevices(accountId, Devices.id eq deviceId).isNotEmpty()
    }

    /**
     * Records activity. If [syncVersion] (the cursor after a pull) is given, records how far the
     * device has read: the admin console shows it and [tombstoneWatermark] trusts it.
     */
    suspend fun touch(
        accountId: String,
        deviceId: String,
        now: Long = System.currentTimeMillis(),
        syncVersion: Long? = null,
    ): Unit = dbTransaction(db) {
        Devices.update({ (Devices.accountId eq accountId) and (Devices.id eq deviceId) }) {
            it[lastSeenAt] = now
            if (syncVersion != null) it[lastSyncVersion] = syncVersion
        }
    }

    /**
     * Records activity after a push that took the account from [seqBefore] to [cursor]. The device's
     * cursor moves to [cursor] only when it had already read everything up to [seqBefore]: the range
     * in between is then this push's own writes. Otherwise the account cursor also covers other
     * devices' changes — tombstones among them — this one never pulled, and claiming them would let
     * [tombstoneWatermark] purge a tombstone it has not seen.
     */
    suspend fun touchAfterPush(
        accountId: String,
        deviceId: String,
        seqBefore: Long,
        cursor: Long,
        now: Long = System.currentTimeMillis(),
    ): Unit = dbTransaction(db) {
        val device = (Devices.accountId eq accountId) and (Devices.id eq deviceId)
        Devices.update({ device }) { it[lastSeenAt] = now }
        // The read cursor is tested inside the UPDATE, not read ahead of it: a concurrent pull's
        // touch landing in between would otherwise be overwritten with an older cursor.
        val unread = if (0L in seqBefore until cursor) Devices.lastSyncVersion.isNull() else Op.FALSE
        val caughtUp = (Devices.lastSyncVersion greaterEq seqBefore) and (Devices.lastSyncVersion less cursor)
        Devices.update({ device and (unread or caughtUp) }) { it[lastSyncVersion] = cursor }
    }

    /**
     * The generation a token for this device must carry to be accepted, or null when the device is
     * revoked or unknown — either way nothing it presents is valid.
     */
    suspend fun liveTokenGeneration(accountId: String, deviceId: String): Long? = dbTransaction(db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.takeUnless { it[Devices.revoked] }
            ?.get(Devices.tokenGeneration)
    }

    /**
     * Whether the device is revoked. An unknown (missing) device counts as revoked, so a JWT for
     * a device no longer in the table is rejected.
     */
    suspend fun isRevoked(accountId: String, deviceId: String): Boolean = dbTransaction(db) {
        Devices.selectAll()
            .where { (Devices.accountId eq accountId) and (Devices.id eq deviceId) }
            .singleOrNull()
            ?.get(Devices.revoked) ?: true
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toDeviceRow() = DeviceRow(
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

/**
 * Revokes the account's devices [match] selects and retires what they already hold: their token
 * generation moves on, so tokens issued before this stay dead even after a re-login clears the flag,
 * and the pairing codes they started are deleted. Returns the ids it revoked. Call inside a transaction.
 */
internal fun revokeDevices(accountId: String, match: Op<Boolean>): List<String> {
    val scope = (Devices.accountId eq accountId) and match
    val ids = Devices.selectAll().where { scope }.map { it[Devices.id] }
    if (ids.isEmpty()) return ids
    Devices.update({ scope }) {
        it[revoked] = true
        it[tokenGeneration] = tokenGeneration + 1L
    }
    Pairing.deleteWhere { (Pairing.accountId eq accountId) and (Pairing.deviceId inList ids) }
    return ids
}
