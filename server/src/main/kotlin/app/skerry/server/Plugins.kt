package app.skerry.server

import app.skerry.server.auth.TokenService
import app.skerry.server.model.ErrorResponse
import app.skerry.server.routes.adminRoutes
import app.skerry.server.routes.authRoutes
import app.skerry.server.routes.deviceRoutes
import app.skerry.server.routes.pairingClaimRoute
import app.skerry.server.routes.pairingStartRoute
import app.skerry.server.routes.syncWebSocket
import app.skerry.server.routes.teamRoutes
import app.skerry.server.routes.vaultRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Имена rate-limit бакетов (по удалённому IP). Объявлены здесь, используются в маршрутах. */
object RateLimits {
    val REGISTER = RateLimitName("auth-register")
    val SRP_CHALLENGE = RateLimitName("srp-challenge")
    val SRP_VERIFY = RateLimitName("srp-verify")
    val PAIRING_CLAIM = RateLimitName("pairing-claim")
    val REFRESH = RateLimitName("auth-refresh")
    val ADMIN = RateLimitName("admin")
}

/** Версия сервера для /healthz и админ-консоли. */
const val SERVER_VERSION = "0.1.0"

val JWTPrincipal.accountId: String get() = payload.subject
val JWTPrincipal.deviceId: String get() = payload.getClaim(TokenService.CLAIM_DEVICE).asString()

/**
 * Принципал в маршруте под `authenticate("auth-jwt")`. Кидает явную ошибку вместо `!!`: при
 * случайном переносе маршрута из-под `authenticate {}` это даст понятный сбой, не молчаливый NPE
 * (kotlin-ревью).
 */
fun ApplicationCall.jwtPrincipal(): JWTPrincipal =
    principal<JWTPrincipal>() ?: error("missing JWT principal — route must be under authenticate(\"auth-jwt\")")

/**
 * Устанавливает плагины и маршруты. Вынесено из [module] отдельной функцией, чтобы тесты
 * могли поднять сервер на тестовой БД через `testApplication { application { configureServer(services) } }`.
 */
fun Application.configureServer(services: Services) {
    // Forward-compat: незнакомые поля в JSON игнорируем (старый клиент ↔ новый сервер). Опечатки
    // полей при этом не ловятся — осознанное решение для версионируемого API.
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(WebSockets) { pingPeriodMillis = 30_000 }
    install(CallLogging) { level = Level.INFO }
    // Security-заголовки на каждый ответ. CSP заперт на 'self' (API отдаёт JSON, админ-консоль —
    // same-origin без внешних ресурсов после удаления Google Fonts CDN); inline стиль/скрипт
    // допущены, т.к. консоль их использует и встроена в ту же страницу.
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header(
            "Content-Security-Policy",
            "default-src 'self'; font-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'",
        )
    }
    // Rate-limit по удалённому IP: гасит брутфорс/флуд на регистрацию, SRP и claim паринга.
    install(RateLimit) {
        // Все бакеты одного вида: N токенов на 60 секунд, ключ — удалённый IP.
        fun perIp(name: RateLimitName, limit: Int) = register(name) {
            rateLimiter(limit = limit, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        perIp(RateLimits.REGISTER, limit = 5)
        perIp(RateLimits.SRP_CHALLENGE, limit = 10)
        perIp(RateLimits.SRP_VERIFY, limit = 10)
        perIp(RateLimits.PAIRING_CLAIM, limit = 10)
        // Refresh не требует пароля — по defense-in-depth ограничиваем флуд (подпись дёшева, но публичный
        // POST без предварительной аутентификации не должен быть единственным без лимита).
        perIp(RateLimits.REFRESH, limit = 30)
        // Admin-консоль защищена статическим токеном со сравнением constant-time, но от перебора токена
        // это не спасает — добавляем частотный лимит на /admin/*.
        perIp(RateLimits.ADMIN, limit = 30)
    }
    // Жёсткая верхняя граница тела запроса. По Content-Length отвергаем раздутые тела 413-м ещё до
    // чтения. Но одного Content-Length мало: тело с Transfer-Encoding: chunked приходит БЕЗ него, и
    // тогда проверка ниже не сработала бы, а call.receive забуферизовал бы поток любого размера в память
    // (OOM одним неаутентифицированным запросом). Наш клиент на теле всегда шлёт Content-Length, поэтому
    // POST/PUT без него отвергаем как 411 — это и закрывает chunked-обход.
    val maxBody = services.config.maxRequestBodyBytes
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        val carriesBody = method == HttpMethod.Post || method == HttpMethod.Put || method == HttpMethod.Patch
        val len = call.request.contentLength()
        if (carriesBody && len == null) {
            call.respond(HttpStatusCode.LengthRequired, ErrorResponse("Content-Length required"))
            return@intercept finish()
        }
        if (len != null && len > maxBody) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("request body too large"))
            return@intercept finish()
        }
    }
    // CORS нужен только браузерным клиентам; нативные приложения ему не подвержены, а админ-консоль
    // — same-origin. Поэтому по умолчанию (пустой список) CORS не ставим; включается явным списком
    // хостов через SKERRY_CORS_HOSTS (security-ревью M3: не оставлять anyHost).
    if (services.config.corsHosts.isNotEmpty()) {
        install(CORS) {
            services.config.corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowMethod(io.ktor.http.HttpMethod.Put)
            allowMethod(io.ktor.http.HttpMethod.Delete)
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "skerry-sync"
            verifier(services.tokens.verifier())
            validate { credential ->
                val type = credential.payload.getClaim(TokenService.CLAIM_TYPE).asString()
                val account = credential.payload.subject
                val did = credential.payload.getClaim(TokenService.CLAIM_DEVICE).asString()
                // Токен валиден только если это access-токен и устройство (в рамках аккаунта) не отозвано.
                if (type == TokenService.TYPE_ACCESS && account != null && did != null &&
                    !services.devices.isRevoked(account, did)
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        // Корень ведёт на админ-консоль — чтобы открытие сервера в браузере не давало 404.
        get("/") { call.respondRedirect("/console/") }

        // Статическая админ-консоль (self-hosted): /console -> resources/admin/index.html.
        staticResources("/console", "admin")

        authRoutes(services)
        pairingClaimRoute(services)   // без JWT: новое устройство ещё не вошло
        rateLimit(RateLimits.ADMIN) {
            adminRoutes(services)     // своя admin-аутентификация (статический токен) + лимит на перебор
        }

        authenticate("auth-jwt") {
            vaultRoutes(services)
            deviceRoutes(services)
            pairingStartRoute(services)
            teamRoutes(services)
            syncWebSocket(services)
        }
    }
}
