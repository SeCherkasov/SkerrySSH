package app.skerry.server.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query

/**
 * How much of a delta one pull answers with. The client refuses any response past 32 MiB, so an
 * unpaged delta made a vault or a share space that grew past it unpullable for every device that
 * had to start from zero. A page stops at [maxRecords], or before the record that would take it past
 * [maxBytes] of ciphertext, whichever comes first — but always holds at least one record, which the
 * request-body cap already keeps below the client's limit.
 */
data class PullPageLimits(val maxRecords: Int = MAX_RECORDS, val maxBytes: Long = MAX_BYTES) {
    init {
        require(maxRecords > 0 && maxBytes > 0) { "a pull page must be able to hold a record" }
    }

    companion object {
        const val MAX_RECORDS = 500
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}

/** Which cap a push ran into: the one who has to delete something needs to know where. */
enum class StorageQuota { ACCOUNT, SPACE }

/** A push would grow an account or a share space past its [limit] of stored ciphertext, in bytes. */
class StorageQuotaExceededException(val quota: StorageQuota, val limit: Long) :
    RuntimeException("${quota.name.lowercase()} storage quota of $limit bytes exceeded")

/** Rows fetched per round trip while a page is cut, so a driver that buffers (PostgreSQL) holds a few, not the delta. */
private const val PAGE_FETCH_ROWS = 8

/** One page of a delta; [complete] when nothing past it was left out. */
internal class DeltaPage<T>(val records: List<T>, val complete: Boolean)

/**
 * The first page of [this] query's rows, in its order, under [limits]. The rows are walked, never
 * loaded whole: `LIMIT` alone would still let PostgreSQL buffer [PullPageLimits.maxRecords] rows of
 * ciphertext before the byte budget is even looked at.
 */
internal fun <T> Query.page(limits: PullPageLimits, blob: Column<ExposedBlob>, toRecord: (ResultRow) -> T): DeltaPage<T> {
    val records = mutableListOf<T>()
    var bytes = 0L
    for (row in limit(limits.maxRecords).fetchSize(PAGE_FETCH_ROWS)) {
        val size = row[blob].bytes.size
        if (records.isNotEmpty() && bytes + size > limits.maxBytes) return DeltaPage(records, complete = false)
        records += toRecord(row)
        bytes += size
    }
    // A page that filled up by count may have been the last one; the next pull finds out, and costs
    // one request, rather than this one counting what is left.
    return DeltaPage(records, complete = records.size < limits.maxRecords)
}

/**
 * Refuses the write this transaction holds when it grew what [sql] measures past [limit]. Only
 * growth is refused: the client pushes every record it holds on every cycle, so a space already
 * over its quota — one filled before the quota existed, or before an operator lowered it — keeps
 * syncing edits that do not add to it, and can always be brought back under by deleting.
 */
internal fun JdbcTransaction.enforceQuota(
    quota: StorageQuota,
    limit: Long,
    grew: Long,
    sql: String,
    args: List<Pair<IColumnType<*>, Any?>>,
) {
    if (limit <= 0 || grew <= 0) return
    val stored = exec(sql, args, StatementType.SELECT) { rs -> if (rs.next()) rs.getLong(1) else 0L } ?: 0L
    // Thrown inside the transaction, so the whole push rolls back rather than landing in part.
    if (stored > limit) throw StorageQuotaExceededException(quota, limit)
}

/** Width of `accounts.id`, for binding one in raw SQL. */
internal const val ACCOUNT_ID_LENGTH = 320

/**
 * What an account stores: its own vault, and every share space of the teams it owns. A team's bytes
 * go to its owner — the one account every team has (an heir, once that account is deleted) — so
 * creating teams and scopes, which nothing caps, does not multiply what one account can put on the
 * server's disk.
 */
private const val ACCOUNT_USAGE_SQL =
    "SELECT (SELECT COALESCE(SUM(LENGTH(blob)), 0) FROM records WHERE account_id = ?) + " +
        "(SELECT COALESCE(SUM(LENGTH(tr.blob)), 0) FROM team_records tr JOIN teams t ON t.id = tr.team_id " +
        "WHERE t.owner_account_id = ?) + " +
        "(SELECT COALESCE(SUM(r.reserved_bytes), 0) FROM team_recordings r JOIN teams t ON t.id = r.team_id " +
        "WHERE t.owner_account_id = ?)"

/** [enforceQuota] for [accountId]'s whole footprint (see [ACCOUNT_USAGE_SQL]). */
internal fun JdbcTransaction.enforceAccountQuota(limit: Long, grew: Long, accountId: String) = enforceQuota(
    StorageQuota.ACCOUNT,
    limit,
    grew,
    ACCOUNT_USAGE_SQL,
    List(3) { VarCharColumnType(ACCOUNT_ID_LENGTH) to accountId },
)
