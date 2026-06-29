package app.skerry.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/**
 * Одноразовые pairing-сессии (вариант B, design §3). Сервер хранит dataKey зашифрованным
 * одноразовым transferKey и не может его прочитать; сессия живёт до TTL и сгорает при выдаче.
 */
class PairingRepository(private val db: Database) {

    suspend fun create(code: String, accountId: String, encryptedDataKey: ByteArray, expiresAt: Long): Unit =
        newSuspendedTransaction(Dispatchers.IO, db) {
            Pairing.insert {
                it[Pairing.code] = code
                it[Pairing.accountId] = accountId
                it[Pairing.encryptedDataKey] = ExposedBlob(encryptedDataKey)
                it[Pairing.expiresAt] = expiresAt
                it[consumed] = false
            }
        }

    /**
     * Атомарно выдаёт и гасит сессию. Возвращает `null`, если кода нет, он уже выдан или истёк
     * (на момент [now]).
     *
     * TOCTOU-устойчиво: гашение делается одним conditional UPDATE (`consumed=false AND
     * expiresAt>now`), а не read-then-update. Только транзакция, чей UPDATE реально изменил строку
     * (count==1), считается победителем и строит [PairingRow]; параллельный второй claim того же
     * кода обновит 0 строк и получит `null`. Так один код нельзя выдать дважды даже при гонке.
     */
    suspend fun consume(code: String, now: Long = System.currentTimeMillis()): PairingRow? =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val claimed = Pairing.update({
                (Pairing.code eq code) and (Pairing.consumed eq false) and (Pairing.expiresAt greater now)
            }) {
                it[consumed] = true
            }
            if (claimed != 1) return@newSuspendedTransaction null
            // Эта транзакция выиграла гонку — теперь безопасно прочитать неизменяемые поля сессии.
            val row = Pairing.selectAll().where { Pairing.code eq code }.single()
            PairingRow(
                code = row[Pairing.code],
                accountId = row[Pairing.accountId],
                encryptedDataKey = row[Pairing.encryptedDataKey].bytes,
                expiresAt = row[Pairing.expiresAt],
                consumed = true,
            )
        }

    suspend fun cleanupExpired(now: Long = System.currentTimeMillis()): Int =
        newSuspendedTransaction(Dispatchers.IO, db) {
            Pairing.deleteWhere { expiresAt lessEq now }
        }
}
