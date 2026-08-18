package app.skerry.ui.i18n

import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.allPluralStringResources
import app.skerry.ui.generated.resources.allStringArrayResources
import app.skerry.ui.generated.resources.allStringResources
import app.skerry.ui.generated.resources.shell_delete_host_title
import app.skerry.ui.generated.resources.shell_disconnect_title
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an Android-style escape does to a Compose Resources string, and which quote marks a locale
 * writes once the escape is gone.
 *
 * The gradle plugin decodes `\n`, `\t`, `\uXXXX` and `\\` and nothing else, so the `\"`, `\'`, `\@`
 * and `\?` an Android `strings.xml` defines survive the build and reach the screen with the
 * backslash still on them: `Disconnect \"my-host\"?`. Nothing else in the project catches it — the
 * XML is valid, the key exists in all three locales, and the parity check compares keys, not text.
 *
 * The strings are read through the same resource reader the UI uses, which resolves the locale from
 * the JVM default and asks AWT for the screen density — so this test needs the display `desktopTest`
 * already runs under, and cannot move to `commonTest`.
 */
class StringEscapeTest {

    @Test
    fun `the disconnect and delete dialogs quote the host in every locale`() {
        inLocale("en") {
            assertEquals("Disconnect “my-host”?", getString(Res.string.shell_disconnect_title, HOST))
            assertEquals("Delete “my-host”?", getString(Res.string.shell_delete_host_title, HOST))
        }
        inLocale("ru") {
            assertEquals("Отключить «my-host»?", getString(Res.string.shell_disconnect_title, HOST))
            assertEquals("Удалить «my-host»?", getString(Res.string.shell_delete_host_title, HOST))
        }
        inLocale("zh") {
            assertEquals("断开“my-host”？", getString(Res.string.shell_disconnect_title, HOST))
            assertEquals("删除“my-host”？", getString(Res.string.shell_delete_host_title, HOST))
        }
    }

    @Test
    fun `no locale renders an escape the resource pipeline never decodes`() {
        forEachLocale { locale, rendered ->
            val stray = rendered.filterValues { text -> ANDROID_ESCAPE.containsMatchIn(text) }
            assertEquals(
                emptyMap(), stray,
                "$locale: Compose Resources leaves an Android escape alone — the backslash is drawn",
            )
        }
    }

    /**
     * A locale quotes with its own marks: “ ” in English and Chinese, « » in Russian, corner
     * brackets nowhere. Mixed marks are what the escape sweep leaves behind — a string can lose the
     * backslash and still end up with another language's quotes around the host name.
     */
    @Test
    fun `no locale borrows another locale's quote marks`() {
        forEachLocale { locale, rendered ->
            val forbidden = FOREIGN_QUOTES.getValue(locale)
            val borrowed = rendered.filterValues { text -> text.any { it in forbidden } }
            assertEquals(emptyMap(), borrowed, "$locale: quoted with $forbidden")
        }
    }

    /**
     * Compose Resources substitutes with a regex over `%N$s` / `%N$d` — it is not `String.format`,
     * so `%%` stays doubled and a non-positional `%s` is never filled at all. Both reach the screen
     * the way #281's backslash did.
     */
    @Test
    fun `no locale draws a format placeholder the substitution never fills`() {
        forEachLocale { locale, rendered ->
            val leftover = rendered.filterValues { text -> FORMAT_ARTIFACT.containsMatchIn(text) }
            assertEquals(emptyMap(), leftover, "$locale: a `%` construct the resource reader leaves alone")
        }
    }

    /** Runs [body] against every string the app can draw in that locale. */
    private fun forEachLocale(body: (String, Map<String, String>) -> Unit) {
        for (locale in LOCALES) {
            inLocale(locale) {
                // A collector that stopped collecting would leave every filter below empty and the
                // file green for good. Each kind is asserted on its own: strings outnumber the other
                // two by two orders of magnitude, so one shared floor cannot see them reach zero.
                // The collectors are the declarations, not the rendered values — same in every
                // locale; it is renderEverything() below that depends on the one in force.
                assertTrue(Res.allStringResources.size > MIN_STRINGS, "only ${Res.allStringResources.size} strings")
                assertTrue(Res.allStringArrayResources.isNotEmpty(), "no string arrays collected")
                assertTrue(Res.allPluralStringResources.isNotEmpty(), "no plurals collected")
                body(locale, renderEverything())
            }
        }
    }

    /**
     * Every string the app can draw, keyed by type and name — a `<string>` and a `<plurals>` may
     * share a name, and one silently replacing the other would drop it out of the sweep.
     * Placeholders are filled because the no-argument overload substitutes nothing at all, so every
     * string that takes an argument would reach the format sweep with its `%1$s` intact. That sweep
     * is the real backstop here: the vararg overload only throws once a placeholder outruns the
     * arguments given. Plural forms Russian reaches only with a fraction are out of reach, for the
     * same reason they are out of reach in the app: the quantity is an `Int`.
     */
    private suspend fun renderEverything(): Map<String, String> = buildMap {
        Res.allStringResources.forEach { (name, resource) -> put("string/$name", resource.filled()) }
        Res.allStringArrayResources.forEach { (name, resource) ->
            put("array/$name", getStringArray(resource).joinToString("\n"))
        }
        Res.allPluralStringResources.forEach { (name, resource) ->
            put("plurals/$name", QUANTITIES.map { resource.filled(it) }.joinToString("\n"))
        }
    }

    /** More placeholders than any string uses; the extras are ignored by the formatter. */
    private suspend fun StringResource.filled(): String = getString(this, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    private suspend fun PluralStringResource.filled(quantity: Int): String =
        getPluralString(this, quantity, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    private fun inLocale(tag: String, body: suspend () -> Unit) = runBlocking { withLocale(tag, body) }

    private companion object {
        const val HOST = "my-host"
        /**
         * The languages the app ships, from the enum that defines them — a fourth one fails on
         * [FOREIGN_QUOTES] until its quote marks are declared, rather than going unswept.
         */
        val LOCALES = UiLanguage.entries.mapNotNull { it.localeTag }

        /** One plural form per rule Russian needs — 1, 2 and 5 land in three different buckets. */
        val QUANTITIES = listOf(1, 2, 5)

        /**
         * Every escape Android's `strings.xml` defines and the resource plugin does not decode. A
         * lone `\` is left alone: it is what `\\` decodes to, and a Windows path may need one.
         * The sweep reads rendered text, where those two are the same character — so copy that
         * deliberately draws `\"` (a shell quoting example) trips this and has to say so here.
         */
        val ANDROID_ESCAPE = Regex("""\\["'@?]""")

        /**
         * The marks a locale must not quote with — its neighbours', the straight ASCII pair an
         * editor types when it does not know the convention, and the typographic apostrophe, which
         * English copy here writes as a plain `'`.
         *
         * Nesting is out of scope: no string here quotes inside a quote, so the inner marks each
         * language would then use (`‘ ’` in en and zh, `„ “` in ru) are forbidden rather than
         * declared. The first translation that needs one relaxes this set — it is a convention,
         * not a translator error.
         */
        val FOREIGN_QUOTES = mapOf(
            "en" to "\"«»„「」’‘『』",
            "ru" to "\"“”„「」’‘『』",
            "zh" to "\"«»„「」’‘『』",
        )

        /**
         * `%%` or a bare `%s`/`%d` — the substituter is a regex over `%N$s`, so it fills neither
         * and both reach the screen as typed. The conversions are the ones the resources use; a
         * new one has to be added here or it goes unswept. An index past the arguments handed in
         * is *not* reported here: the resource reader throws on it before the sweep sees the text.
         * Copy that means a literal `%s` — a strftime or shell fragment — trips this, because the
         * substituter cannot tell the two apart either, which is the point. So does a placeholder
         * inside a `<string-array>`, and rightly: `getStringArray` returns items verbatim, with no
         * substitution pass at all, so an array can carry no arguments.
         */
        val FORMAT_ARTIFACT = Regex("""%(%|\d+\$|[sdf])""")

        /** Just under the ~1870 strings the app ships; the count only grows. */
        const val MIN_STRINGS = 1_800
    }
}
