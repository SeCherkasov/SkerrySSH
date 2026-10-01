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
    fun `every dictionary carries the same keys as English`() {
        val source = javaClass.getResource("/web/assets/dict.js")?.readText()
        assertTrue(source != null && source.isNotEmpty(), "dict.js missing from server resources")

        val keys = listOf("en", "ru", "zh", "tr", "de").associateWith { lang -> dictionaryKeys(source, lang) }
        assertTrue(keys.getValue("en").isNotEmpty(), "no keys parsed out of the English dictionary")
        // English is the fallback and the source of truth; a key missing from another language
        // silently degrades it to English, which is exactly the kind of gap nobody notices.
        for (lang in listOf("ru", "zh", "tr", "de")) {
            assertEquals(emptySet(), keys.getValue("en") - keys.getValue(lang), "keys missing from $lang")
            assertEquals(emptySet(), keys.getValue(lang) - keys.getValue("en"), "keys in $lang that en does not have")
        }
    }

    /** Keys of one dictionary literal in dict.js: from `  <lang>: {` to the line that closes it. */
    private fun dictionaryKeys(source: String, lang: String): Set<String> {
        val start = source.indexOf("\n  $lang: {")
        check(start >= 0) { "no $lang dictionary in dict.js" }
        val end = source.indexOf("\n  },", start).takeIf { it > 0 } ?: source.length
        return Regex("\"([a-z0-9.]+)\":").findAll(source.substring(start, end)).map { it.groupValues[1] }.toSet()
    }

    /**
     * The three places a language must be registered for the web panel to speak it: the `LANGS`
     * array (runtime switcher), the dictionaries (the strings) and the page buttons (the way in).
     * Dropping any one of the three — for German it was `"de"` from `LANGS` — left every test
     * green while the panel silently stayed unreachable in that language.
     */
    @Test
    fun `langs, dictionaries and page buttons are the same set`() {
        val dict = javaClass.getResource("/web/assets/dict.js")?.readText()
        val page = javaClass.getResource("/web/index.html")?.readText()
        assertTrue(dict != null && page != null, "web assets missing from server resources")

        val langs = checkNotNull(Regex("""const LANGS = \[(.*?)]""").find(dict)) {
            "no LANGS array in dict.js"
        }.groupValues[1].split(",").map { it.trim().trim('"') }.toSet()
        val dictionaries = Regex("""^  ([a-z]+): \{$""", RegexOption.MULTILINE)
            .findAll(dict).map { it.groupValues[1] }.toSet()
        val buttons = Regex("""data-lang="([a-z]+)"""").findAll(page).map { it.groupValues[1] }.toSet()
        assertEquals(dictionaries, langs, "a language in LANGS with no dictionary degrades silently")
        assertEquals(dictionaries, buttons, "a language with no button is unreachable")
    }

    /**
     * A value that renames or drops a `{placeholder}` passes key parity and draws a sentence with
     * the number or name missing — the same hole the Compose-side placeholder check covers for the
     * app resources. Sub-form keys (`one:`/`other:`) are unquoted and never match; `{n}` does.
     */
    @Test
    fun `every dictionary value keeps English's placeholders`() {
        val source = javaClass.getResource("/web/assets/dict.js")?.readText()
        assertTrue(source != null && source.isNotEmpty(), "dict.js missing from server resources")

        fun marks(lang: String): Map<String, Set<String>> {
            val start = source.indexOf("\n  $lang: {")
            check(start >= 0) { "no $lang dictionary in dict.js" }
            val end = source.indexOf("\n  },", start).takeIf { it > 0 } ?: source.length
            val block = source.substring(start, end)
            // One line may carry several keys (`"unit.b": "B", "unit.kib": "KiB"`), so a value
            // ends at whichever comes first: its own newline or the next key on the line — a
            // greedy rest-of-line would fold the tail keys into the first one and leave their
            // values unexamined.
            val keys = Regex("\"([a-z0-9.]+)\":").findAll(block).toList()
            return keys.withIndex().associate { (index, match) ->
                val lineEnd = block.indexOf('\n', match.range.last).takeIf { it >= 0 } ?: block.length
                val nextKey = keys.getOrNull(index + 1)?.range?.first ?: block.length
                val value = block.substring(match.range.last + 1, minOf(lineEnd, nextKey))
                match.groupValues[1] to
                    Regex("""\{(\w+)\}""").findAll(value).map { it.groupValues[1] }.toSet()
            }
        }

        val english = marks("en")
        for (lang in listOf("ru", "zh", "tr", "de")) {
            val foreign = marks(lang)
            assertEquals(english.keys, foreign.keys, "keys missing from $lang")
            for ((key, expected) in english) {
                assertEquals(expected, foreign.getValue(key), "$lang/$key: placeholder drift")
            }
        }
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
