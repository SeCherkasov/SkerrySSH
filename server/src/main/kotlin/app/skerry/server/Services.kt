package app.skerry.server

import app.skerry.server.auth.SrpService
import app.skerry.server.auth.TokenService
import app.skerry.server.config.ServerConfig
import app.skerry.server.db.AccountRepository
import app.skerry.server.db.ActivityRepository
import app.skerry.server.db.AdminRepository
import app.skerry.server.db.DeviceRepository
import app.skerry.server.db.PairingRepository
import app.skerry.server.db.RecordRepository
import app.skerry.server.db.StatsRepository
import app.skerry.server.db.TeamRecordRepository
import app.skerry.server.db.TeamRepository
import app.skerry.server.db.TeamScopeRepository
import app.skerry.server.metrics.DbProbe
import app.skerry.server.metrics.InventoryCollector
import app.skerry.server.metrics.ServerMetrics
import app.skerry.server.sync.ChangeNotifier
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Wired dependencies for one server instance. Created once in [module].
 *
 * [metrics] is per instance on purpose: a global registry would accumulate duplicate meters across
 * the many servers the test suite starts in one JVM.
 */
class Services(
    val config: ServerConfig,
    private val database: Database,
    val metrics: ServerMetrics = ServerMetrics(config, version = SERVER_VERSION),
    /**
     * What the readiness probe runs. Overridden only by tests: without a seam here, the 503 path of
     * `/readyz` cannot be exercised through the real route at all.
     */
    dbCheck: (suspend () -> Unit)? = null,
) {
    val accounts = AccountRepository(database)
    val devices = DeviceRepository(database)
    // On PostgreSQL, serialize upserts with an account-row lock; not needed on SQLite (pool=1).
    val records = RecordRepository(database, lockAccountRow = config.isPostgres)
    val pairing = PairingRepository(database)
    val teams = TeamRepository(database)
    val teamRecords = TeamRecordRepository(database, lockTeamRow = config.isPostgres)
    val teamScopes = TeamScopeRepository(database)
    val stats = StatsRepository(database)
    val activity = ActivityRepository(database)
    val admin = AdminRepository(database)
    val srp = SrpService()
    val tokens = TokenService(config)
    val notifier = ChangeNotifier(metrics)

    /** Feeds `GET /readyz` and `skerry_db_up`; started by [module], never queried per request. */
    val dbProbe = DbProbe(metrics) { dbCheck?.invoke() ?: stats.ping() }
    val inventory = InventoryCollector(stats, metrics, config.databaseUrl)
}
