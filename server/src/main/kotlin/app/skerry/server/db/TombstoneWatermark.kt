package app.skerry.server.db

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Tombstone propagation watermark: the minimum cursor across all of an account's devices.
 * Records with `serverSeq <= watermark` have been read by every device, so only those are safe
 * to compact/purge; otherwise a lagging device would resurrect the record on its next pull. A
 * device with no cursor (null/never synced) pins the watermark to 0, blocking compaction; an
 * account with no devices gets watermark = MAX (nothing left to resurrect). Shared by
 * [RecordRepository.compactedTombstoneIds] and [AdminRepository.purgeTombstones].
 * Call only inside an open transaction.
 */
internal fun tombstoneWatermark(accountId: String): Long {
    val cursors = Devices.selectAll()
        .where { Devices.accountId eq accountId }
        .map { it[Devices.lastSyncVersion] ?: 0L }
    return if (cursors.isEmpty()) Long.MAX_VALUE else cursors.min()
}

/** Predicate for "account tombstone already propagated to all devices" (deleted && serverSeq <= watermark). */
internal fun propagatedTombstones(accountId: String, watermark: Long): Op<Boolean> =
    (Records.accountId eq accountId) and (Records.deleted eq true) and (Records.serverSeq lessEq watermark)
