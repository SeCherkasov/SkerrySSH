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
    /**
     * Max concurrent pending challenges per accountId **from one client**. Per client, because the
     * challenge route is anonymous: a per-account cap alone lets anyone evict the owner's pending
     * login by asking for challenges in their name.
     */
    private val maxPerAccount: Int = 3,
    private val randomId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** Standard params: 2048-bit RFC 5054 group, SHA-256 hash. */
    val params: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")

    private data class Pending(
        val session: SRP6ServerSession,
        val accountId: String,
        val client: String,
        val createdAt: Long,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Guards compound operations on [pending] (eviction + cap applied atomically in one pass). */
    private val lock = Any()

    data class Challenge(val challengeId: String, val salt: String, val b: String)

    /**
     * Step 1: derives an ephemeral `B` from the account's salt/verifier and registers a challenge.
     * [client] is the caller's rate-limit key (its address), which scopes the per-account cap.
     */
    fun startChallenge(accountId: String, salt: String, verifier: String, client: String): Challenge {
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
            // 2) Per-account cap, per client: keep at most (maxPerAccount-1) older challenges this
            //    client holds for this account, dropping the oldest to free a slot, so one client
            //    flooding an account neither grows unbounded nor evicts anyone else's login.
            val mine = pending.entries.filter { it.value.accountId == accountId && it.value.client == client }
                .sortedBy { it.value.createdAt }
            val overflow = mine.size - (maxPerAccount - 1)
            if (overflow > 0) mine.take(overflow).forEach { pending.remove(it.key) }
            pending[challengeId] = Pending(session, accountId, client, now)
        }
        return Challenge(challengeId, salt.lowercase().padStart(SALT_HEX_DIGITS, '0'), b.toString(16))
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

    private companion object {
        /**
         * Hex digits every challenge's salt is padded to. A client salt is a 256-bit random rendered
         * with [BigInteger.toString], which drops leading zeros, while the synthesized salt an
         * unknown account gets is always 32 bytes of HMAC — so without padding one real account in
         * sixteen answers with a shorter string than any unknown one ever does, and the length says
         * the account exists. Padding is value-preserving: both ends parse the salt as a number.
         */
        const val SALT_HEX_DIGITS = 64
    }

    private fun evictExpired() {
        val now = clock()
        pending.entries.removeIf { now - it.value.createdAt > challengeTtlMillis }
    }
}
