package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

/** Locale-aware uppercasing behind the design system's small-caps labels. */
class LabelCaseTest {
    @Test
    fun latin_labels_uppercase_as_usual() {
        assertEquals("HOST", uppercaseForLocale("Host", "en"))
        assertEquals("ХОСТ", uppercaseForLocale("Хост", "ru-RU"))
    }

    @Test
    fun caseless_scripts_are_untouched() {
        assertEquals("主机", uppercaseForLocale("主机", "zh-CN"))
    }

    @Test
    fun turkish_keeps_the_dot_on_i() {
        assertEquals("İSTEMCİ", uppercaseForLocale("istemci", "tr-TR"))
        assertEquals("IIK", uppercaseForLocale("ıık", "az"))
    }
}
