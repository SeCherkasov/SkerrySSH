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

    /** The ids are stored in prefs; renaming one silently resets that user's choice to System. */
    @Test
    fun `stored ids never change`() {
        assertEquals(
            mapOf(
                UiLanguage.System to "system",
                UiLanguage.English to "en",
                UiLanguage.Russian to "ru",
                UiLanguage.Chinese to "zh",
                UiLanguage.Turkish to "tr",
                UiLanguage.German to "de",
            ),
            UiLanguage.entries.associateWith { it.id },
        )
    }

    @Test
    fun `german parses from its stored id and carries its tag`() {
        val german = UiLanguage.fromId("de")
        assertEquals("de", german.localeTag)
        assertEquals("Deutsch", german.displayName)
    }

    @Test
    fun `the AI answers in german for a de locale`() {
        assertEquals("German", aiResponseLanguageName("de-DE"))
        assertEquals("German", aiResponseLanguageName("de"))
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
        assertEquals("zh", UiLanguage.Chinese.localeTag)
        assertEquals("tr", UiLanguage.Turkish.localeTag)
        assertEquals("de", UiLanguage.German.localeTag)
    }

    @Test
    fun `the AI answers in the language the UI resolved to`() {
        assertEquals("Turkish", aiResponseLanguageName("tr-TR"))
        assertEquals("Chinese", aiResponseLanguageName("zh"))
        assertEquals("Russian", aiResponseLanguageName("ru"))
        assertEquals("English", aiResponseLanguageName("fr"))
    }

    @Test
    fun `DEFAULT is System`() {
        assertEquals(UiLanguage.System, UiLanguage.DEFAULT)
    }
}
