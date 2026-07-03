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
import app.skerry.server.sync.ChangeNotifier
import org.jetbrains.exposed.sql.Database

/** Собранные зависимости одного инстанса сервера. Создаётся один раз в [module]. */
class Services(val config: ServerConfig, private val database: Database) {
    val accounts = AccountRepository(database)
    val devices = DeviceRepository(database)
    // На PostgreSQL сериализуем upsert'ы блокировкой строки аккаунта; на SQLite (pool=1) не нужно.
    val records = RecordRepository(database, lockAccountRow = config.isPostgres)
    val pairing = PairingRepository(database)
    val stats = StatsRepository(database)
    val activity = ActivityRepository(database)
    val admin = AdminRepository(database)
    val srp = SrpService()
    val tokens = TokenService(config)
    val notifier = ChangeNotifier()
}
