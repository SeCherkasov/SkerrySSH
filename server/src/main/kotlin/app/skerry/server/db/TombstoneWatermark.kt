package app.skerry.server.db

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Tombstone propagation watermark: the minimum cursor across all of an account's ACTIVE devices.
 * Records with `serverSeq <= watermark` have been read by every active device, so only those are
 * safe to compact/purge; otherwise a lagging device would resurrect the record on its next pull. A
 * device with no cursor (null/never synced) pins the watermark to 0, blocking compaction; an
 * account with no active devices gets watermark = MAX (nothing left to resurrect). Revoked devices
 * are excluded: a revoked device can never pull again (the server rejects its auth), so its frozen
 * cursor must not pin the watermark forever — with it included, one revoked-and-never-synced device
 * blocks tombstone compaction for the account permanently. If the device is re-activated
 * (re-authentication clears revocation, see DeviceRepository), its cursor is stale relative to
 * purged tombstones, which is safe: the tombstone's target record is long gone from every peer, and
 * the device's next delta pull simply never sees a deletion for a record it may still hold — the
 * same end state as a device that was offline past the team-scope age purge. Shared by
 * [RecordRepository.compactedTombstoneIds] and [AdminRepository.purgeTombstones].
 * Call only inside an open transaction.
 */
internal fun tombstoneWatermark(accountId: String): Long {
    val cursors = Devices.selectAll()
        .where { (Devices.accountId eq accountId) and (Devices.revoked eq false) }
        .map { it[Devices.lastSyncVersion] ?: 0L }
    return if (cursors.isEmpty()) Long.MAX_VALUE else cursors.min()
}

/** Predicate for "account tombstone already propagated to all devices" (deleted && serverSeq <= watermark). */
internal fun propagatedTombstones(accountId: String, watermark: Long): Op<Boolean> =
    (Records.accountId eq accountId) and (Records.deleted eq true) and (Records.serverSeq lessEq watermark)
