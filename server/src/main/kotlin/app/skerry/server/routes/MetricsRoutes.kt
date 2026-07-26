package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.config.MetricsExposure
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Prometheus exposition. Not registered at all when metrics are off, so the endpoint answers 404
 * instead of announcing itself with a 401 — there is no UI behind it that needs a distinguishable
 * error, and the exposition is instance metadata (account counts, ciphertext volume, failed logins).
 *
 * The credential is deliberately **not** [app.skerry.server.config.ServerConfig.adminToken]: that
 * token also authorizes `DELETE /admin/accounts/{id}`, and a scraper's config file has no business
 * holding it. `Authorization: Bearer` is what Prometheus supports natively
 * (`authorization.credentials_file`), unlike a custom header.
 */
fun Route.metricsRoutes(services: Services) {
    if (services.config.metrics == MetricsExposure.OFF) return

    get("/metrics") {
        // Set before the gate: scraped values are point-in-time, and a caching proxy that pinned the
        // 401 would keep rejecting legitimate scrapes long after the token was fixed.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (services.config.metrics == MetricsExposure.TOKEN && !call.hasMetricsToken(services)) {
            services.metrics.metricsAuthFailure()
            call.respondText("metrics token required\n", status = HttpStatusCode.Unauthorized)
            return@get
        }
        call.respondText(services.metrics.scrape(), PROMETHEUS_TEXT)
    }
}

private val PROMETHEUS_TEXT = ContentType.Text.Plain.withParameter("version", "0.0.4").withCharset(Charsets.UTF_8)

private fun io.ktor.server.application.ApplicationCall.hasMetricsToken(services: Services): Boolean {
    val expected = services.config.metricsToken
    if (expected.isBlank()) return false // startup already refuses this combination; belt and braces
    val provided = request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
    return provided != null && constantTimeEquals(provided, expected)
}
