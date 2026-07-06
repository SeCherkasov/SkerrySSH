package app.skerry.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [UiLanguage] contract: stable [UiLanguage.id] for persistence, BCP-47 [UiLanguage.localeTag] for
 * locale override, and safe parsing of a stored value.
 */
class UiLanguageTest {

    @Test
    fun `fromId round-trips every known id`() {
        UiLanguage.entries.forEach { lang ->
            assertEquals(lang, UiLanguage.fromId(lang.id))
        }
    }

    @Test
    fun `fromId falls back to System for unknown, null or blank`() {
        assertEquals(UiLanguage.System, UiLanguage.fromId(null))
        assertEquals(UiLanguage.System, UiLanguage.fromId("fr"))
        assertEquals(UiLanguage.System, UiLanguage.fromId(""))
    }

    @Test
    fun `System means auto-detect - no locale override`() {
        assertNull(UiLanguage.System.localeTag)
    }

    @Test
    fun `explicit languages carry their BCP-47 tag`() {
        assertEquals("en", UiLanguage.English.localeTag)
        assertEquals("ru", UiLanguage.Russian.localeTag)
    }

    @Test
    fun `DEFAULT is System`() {
        assertEquals(UiLanguage.System, UiLanguage.DEFAULT)
    }
}
