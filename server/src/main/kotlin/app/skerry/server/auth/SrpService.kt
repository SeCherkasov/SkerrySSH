package app.skerry.server.auth

import com.nimbusds.srp6.SRP6CryptoParams
import com.nimbusds.srp6.SRP6Exception
import com.nimbusds.srp6.SRP6ServerSession
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Серверная сторона SRP-6a (`docs/skerry-sync-design.md` §1, §3). Сервер хранит только
 * соль `s` и верификатор `v` (см. [app.skerry.server.db.Accounts]); пароль/authKey клиента
 * никогда не передаётся. Вход — двухшаговый: challenge выдаёт эфемерный `B`, verify проверяет
 * доказательство `M1` клиента и возвращает встречное `M2`.
 *
 * Между двумя HTTP-запросами серверная сессия Nimbus (с приватным `b`) держится в памяти под
 * одноразовым [challengeId] с TTL — модель одиночного self-hosted инстанса.
 */
class SrpService(
    private val clock: () -> Long = System::currentTimeMillis,
    private val challengeTtlMillis: Long = 120_000,
    /** Жёсткий предел незавершённых challenge — страховка от OOM при флуде /auth/srp/challenge. */
    private val maxPending: Int = 10_000,
    /** Сколько одновременных незавершённых challenge допускается на один accountId. */
    private val maxPerAccount: Int = 3,
    private val randomId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** Стандартные параметры: 2048-битная группа RFC 5054, хеш SHA-256. */
    val params: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")

    private data class Pending(val session: SRP6ServerSession, val accountId: String, val createdAt: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Монитор для compound-операций над [pending] (эвикция + кап считаются и применяются одним проходом). */
    private val lock = Any()

    data class Challenge(val challengeId: String, val salt: String, val b: String)

    /** Шаг 1: по соли/верификатору аккаунта порождает эфемерный `B` и регистрирует challenge. */
    fun startChallenge(accountId: String, salt: String, verifier: String): Challenge {
        // Дорогой modexp считаем вне монитора — под локом только учёт записей.
        val session = SRP6ServerSession(params)
        val b = session.step1(accountId, BigInteger(salt, 16), BigInteger(verifier, 16))
        val challengeId = randomId()
        synchronized(lock) {
            val now = clock()
            // 1) TTL-эвикция и глобальный кап — одним проходом, атомарно относительно других стартов.
            pending.entries.removeIf { now - it.value.createdAt > challengeTtlMillis }
            if (pending.size >= maxPending) {
                pending.entries.sortedBy { it.value.createdAt }
                    .take(pending.size - maxPending + 1)
                    .forEach { pending.remove(it.key) }
            }
            // 2) Per-account кап: оставляем максимум (maxPerAccount-1) старых challenge этого аккаунта,
            //    самые старые сбрасываем, чтобы освободить слот под новый (флуд одного аккаунта не
            //    вытесняет challenge остальных и не растёт без предела).
            val mine = pending.entries.filter { it.value.accountId == accountId }
                .sortedBy { it.value.createdAt }
            val overflow = mine.size - (maxPerAccount - 1)
            if (overflow > 0) mine.take(overflow).forEach { pending.remove(it.key) }
            pending[challengeId] = Pending(session, accountId, now)
        }
        return Challenge(challengeId, salt, b.toString(16))
    }

    /**
     * Шаг 2: проверяет доказательство клиента `M1` и возвращает встречное `M2` (hex) с
     * accountId, либо `null` при неверном пароле/просроченном или неизвестном challenge.
     * Challenge одноразовый — снимается при любом исходе.
     */
    fun verify(challengeId: String, a: String, m1: String): Verified? {
        evictExpired()
        val p = pending.remove(challengeId) ?: return null
        if (clock() - p.createdAt > challengeTtlMillis) return null
        return try {
            val m2 = p.session.step2(BigInteger(a, 16), BigInteger(m1, 16))
            Verified(p.accountId, m2.toString(16))
        } catch (_: SRP6Exception) {
            null
        }
    }

    data class Verified(val accountId: String, val m2: String)

    private fun evictExpired() {
        val now = clock()
        pending.entries.removeIf { now - it.value.createdAt > challengeTtlMillis }
    }
}
