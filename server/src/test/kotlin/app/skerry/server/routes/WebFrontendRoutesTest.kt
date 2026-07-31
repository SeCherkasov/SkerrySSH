package app.skerry.server.routes

import app.skerry.server.configureServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three entrances of the web frontend and the assets behind them. One bundle: the same page
 * answers `/`, `/account` and `/console`, and the zone is chosen from the path — a deep link must
 * open where it says it does, not on the front page.
 */
class WebFrontendRoutesTest {

    @Test
    fun `every zone prefix serves the page, with or without a trailing slash`() = testApplication {
        val services = testServices()
        application { configureServer(services) }

        for (path in listOf("/", "/account", "/account/", "/console", "/console/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, "GET $path")
            assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters(), "GET $path")
            val body = response.bodyAsText()
            // The approved brand mark and the bundle, not a placeholder page.
            assertTrue(body.contains("id=\"skerry-mark\""), "GET $path has no brand mark")
            assertTrue(body.contains("/assets/app.js"), "GET $path does not load the bundle")
        }
    }

    @Test
    fun `a path below a zone is not the page`() = testApplication {
        val services = testServices()
        application { configureServer(services) }

        // The zones are exact paths, not a client-side router: a deeper URL is a 404, so a typo
        // never renders a page that then quietly fails every request it makes.
        for (path in listOf("/console/settings", "/account/devices", "/nope")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, "GET $path")
        }
    }

    @Test
    fun `the bundle and the self-hosted fonts are served`() = testApplication {
        val services = testServices()
        application { configureServer(services) }

        for (asset in listOf("/assets/app.js", "/assets/panes.js", "/assets/api.js", "/assets/i18n.js", "/assets/dict.js")) {
            assertEquals(HttpStatusCode.OK, client.get(asset).status, "GET $asset")
        }
        // Chinese falls through to the system stack, but latin must never reach for a CDN.
        assertEquals(HttpStatusCode.OK, client.get("/assets/fonts/space-grotesk-latin.woff2").status)
        assertEquals(HttpStatusCode.OK, client.get("/assets/fonts/jetbrains-mono-latin.woff2").status)
    }

    @Test
    fun `the three dictionaries carry the same keys`() {
        val source = javaClass.getResource("/web/assets/dict.js")?.readText()
        assertTrue(source != null && source.isNotEmpty(), "dict.js missing from server resources")

        val keys = listOf("en", "ru", "zh").associateWith { lang -> dictionaryKeys(source, lang) }
        assertTrue(keys.getValue("en").isNotEmpty(), "no keys parsed out of the English dictionary")
        // English is the fallback and the source of truth; a key missing from ru or zh silently
        // degrades that language to English, which is exactly the kind of gap nobody notices.
        assertEquals(emptySet(), keys.getValue("en") - keys.getValue("ru"), "keys missing from ru")
        assertEquals(emptySet(), keys.getValue("en") - keys.getValue("zh"), "keys missing from zh")
        assertEquals(emptySet(), keys.getValue("ru") - keys.getValue("en"), "keys in ru that en does not have")
        assertEquals(emptySet(), keys.getValue("zh") - keys.getValue("en"), "keys in zh that en does not have")
    }

    /** Keys of one dictionary literal in dict.js: from `  <lang>: {` to the line that closes it. */
    private fun dictionaryKeys(source: String, lang: String): Set<String> {
        val start = source.indexOf("\n  $lang: {")
        check(start >= 0) { "no $lang dictionary in dict.js" }
        val end = source.indexOf("\n  },", start).takeIf { it > 0 } ?: source.length
        return Regex("\"([a-z0-9.]+)\":").findAll(source.substring(start, end)).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the page is served under a self-only CSP`() = testApplication {
        val services = testServices()
        application { configureServer(services) }

        val csp = client.get("/").headers["Content-Security-Policy"]
        // The bundle has to stay self-contained: no CDN, no remote font, no third-party script.
        // `script-src 'self'` without 'unsafe-inline' is the second line of defence behind the
        // frontend's own escaping — the page builds its markup by string concatenation, so an
        // injected <script> must be unable to run even if one value ever reaches the DOM unescaped.
        assertEquals(
            "default-src 'self'; font-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'",
            csp,
        )
    }

    @Test
    fun `the page carries no inline script for the CSP to have to allow`() {
        val page = javaClass.getResource("/web/index.html")?.readText()
        assertTrue(page != null && page.isNotEmpty(), "index.html missing from server resources")
        // An inline <script> block or an on*= handler would be dead on arrival under the CSP above,
        // so the page must not grow one unnoticed.
        assertTrue(
            Regex("<script(?![^>]*\\ssrc=)").findAll(page).none(),
            "index.html has an inline <script> block",
        )
        assertTrue(Regex("\\son[a-z]+\\s*=").findAll(page).none(), "index.html has an inline event handler")
    }
}
