package app.skerry.server.auth

import app.skerry.server.config.ServerConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.util.Date

/**
 * Выпуск и проверка JWT (`docs/skerry-sync-design.md` §4: короткий TTL + refresh). Токен
 * привязан к accountId и deviceId; даёт доступ только к шифроблобам, не к содержимому.
 *
 * ## Отзыв refresh-токенов: осознанный stateless trade-off
 *
 * Refresh-токены здесь — самодостаточные (stateless) JWT: сервер не хранит их в БД и не ведёт
 * per-token список выданных. Это упрощает self-hosted инстанс (нет таблицы токенов, нет уборки),
 * но означает, что отдельный refresh-токен нельзя «погасить» индивидуально — подписанный JWT
 * действителен до своего `exp`.
 *
 * Канал отзыва при компрометации — **revoke устройства**: и `/auth/refresh`, и access-валидатор
 * (`auth-jwt`) на каждый запрос сверяются с [app.skerry.server.db.DeviceRepository.isRevoked]
 * (accountId+deviceId). Отзыв устройства мгновенно перекрывает и его access-, и его refresh-поток,
 * не трогая остальные устройства. Глобальный отзыв всех токенов аккаунта = ротация
 * `SKERRY_JWT_SECRET` (инвалидирует все подписи) или смена мастер-пароля (ротация SRP-верификатора).
 *
 * Поэтому generation-counter / БД-список refresh-токенов НЕ реализуется намеренно: device-revoke
 * уже даёт нужную гранулярность отзыва при принятой модели одиночного инстанса.
 */
class TokenService(private val config: ServerConfig, private val clock: () -> Long = System::currentTimeMillis) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    companion object {
        const val CLAIM_DEVICE = "did"
        const val CLAIM_TYPE = "typ"
        const val TYPE_ACCESS = "access"
        const val TYPE_REFRESH = "refresh"
    }

    fun issueAccess(accountId: String, deviceId: String): String =
        issue(accountId, deviceId, TYPE_ACCESS, config.accessTokenTtlSeconds)

    fun issueRefresh(accountId: String, deviceId: String): String =
        issue(accountId, deviceId, TYPE_REFRESH, config.refreshTokenTtlSeconds)

    private fun issue(accountId: String, deviceId: String, type: String, ttlSeconds: Long): String {
        val now = clock()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(accountId)
            .withClaim(CLAIM_DEVICE, deviceId)
            .withClaim(CLAIM_TYPE, type)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + ttlSeconds * 1000))
            .sign(algorithm)
    }

    /** Верификатор для Ktor `jwt {}` — проверяет подпись и издателя (срок проверяет сам Ktor/JWT). */
    fun verifier(): JWTVerifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).build()

    /** Декодирует и проверяет refresh-токен; `null`, если он не refresh, просрочен или подделан. */
    fun verifyRefresh(token: String): DecodedJWT? = try {
        val decoded = verifier().verify(token)
        if (decoded.getClaim(CLAIM_TYPE).asString() != TYPE_REFRESH) null else decoded
    } catch (_: Exception) {
        null
    }
}
