package app.skerry.server.auth

import com.nimbusds.srp6.SRP6CryptoParams
import com.nimbusds.srp6.SRP6Exception
import com.nimbusds.srp6.SRP6ServerSession
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Server side of SRP-6a. The server stores only salt `s`
 * and verifier `v` (see [app.skerry.server.db.Accounts]); the client's password/authKey is never
 * sent. Login is two steps: challenge issues an ephemeral `B`, verify checks the client's proof
 * `M1` and returns the counter-proof `M2`.
 *
 * Between the two HTTP requests, the Nimbus server session (with private `b`) is held in memory
 * under a one-shot [challengeId] with a TTL; this models a single self-hosted instance.
 */
class SrpService(
    private val clock: () -> Long = System::currentTimeMillis,
    private val challengeTtlMillis: Long = 120_000,
    /** Hard cap on pending challenges; safety net against OOM under /auth/srp/challenge flooding. */
    private val maxPending: Int = 10_000,
    /** Max concurrent pending challenges allowed per accountId. */
    private val maxPerAccount: Int = 3,
    private val randomId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** Standard params: 2048-bit RFC 5054 group, SHA-256 hash. */
    val params: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")

    private data class Pending(val session: SRP6ServerSession, val accountId: String, val createdAt: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Guards compound operations on [pending] (eviction + cap applied atomically in one pass). */
    private val lock = Any()

    data class Challenge(val challengeId: String, val salt: String, val b: String)

    /** Step 1: derives an ephemeral `B` from the account's salt/verifier and registers a challenge. */
    fun startChallenge(accountId: String, salt: String, verifier: String): Challenge {
        // The expensive modexp runs outside the lock; only bookkeeping is guarded.
        val session = SRP6ServerSession(params)
        val b = session.step1(accountId, BigInteger(salt, 16), BigInteger(verifier, 16))
        val challengeId = randomId()
        synchronized(lock) {
            val now = clock()
            // 1) TTL eviction and the global cap in one pass, atomic with respect to other starts.
            pending.entries.removeIf { now - it.value.createdAt > challengeTtlMillis }
            if (pending.size >= maxPending) {
                pending.entries.sortedBy { it.value.createdAt }
                    .take(pending.size - maxPending + 1)
                    .forEach { pending.remove(it.key) }
            }
            // 2) Per-account cap: keep at most (maxPerAccount-1) older challenges for this account,
            //    dropping the oldest to free a slot, so one account flooding doesn't starve others
            //    or grow unbounded.
            val mine = pending.entries.filter { it.value.accountId == accountId }
                .sortedBy { it.value.createdAt }
            val overflow = mine.size - (maxPerAccount - 1)
            if (overflow > 0) mine.take(overflow).forEach { pending.remove(it.key) }
            pending[challengeId] = Pending(session, accountId, now)
        }
        return Challenge(challengeId, salt, b.toString(16))
    }

    /**
     * Step 2: checks the client's proof `M1` and returns the counter-proof `M2` (hex) with the
     * accountId, or `null` on a wrong password or an expired/unknown challenge. The challenge is
     * one-shot: it is removed regardless of outcome.
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
