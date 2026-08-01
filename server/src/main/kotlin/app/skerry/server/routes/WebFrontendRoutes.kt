package app.skerry.server.routes

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Where the same page answers: the public front, the account cabinet, the operator console. */
private val ZONE_PATHS = listOf("/", "/account", "/account/", "/console", "/console/")

/**
 * The self-hosted web frontend: one bundle, three entrances. The page picks its zone from the path,
 * so `/console` opens the operator console and not a redirect to it — the URL is what a bookmark and
 * a shared link carry.
 *
 * Assets sit under a single `/assets` prefix and are referenced absolutely, so the page is byte-wise
 * identical at every entrance and a trailing slash changes nothing about where its script comes from.
 */
fun Route.webFrontendRoutes() {
    staticResources("/assets", "web/assets")
    for (path in ZONE_PATHS) {
        get(path) { call.respondText(WEB_PAGE, ContentType.Text.Html) }
    }
}

/** Read once at class init: the page is a build artifact of the jar, not something that can change. */
private val WEB_PAGE: String =
    object {}.javaClass.getResource("/web/index.html")?.readText()
        ?: error("web/index.html missing from server resources")
