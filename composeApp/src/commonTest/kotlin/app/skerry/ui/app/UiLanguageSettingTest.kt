package app.skerry.ui.app

import app.skerry.ui.i18n.UiLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Choosing the UI language in [DesktopDesignState]/[MobileDesignState] updates state and reports it
 * for persistence; choosing the current language again is a no-op (no write), like terminal font choice.
 */
class UiLanguageSettingTest {

    @Test
    fun `desktop chooseUiLanguage updates state and persists`() {
        val persisted = mutableListOf<UiLanguage>()
        val state = DesktopDesignState(onUiLanguageChange = { persisted += it })

        assertEquals(UiLanguage.System, state.uiLanguage)
        state.chooseUiLanguage(UiLanguage.Russian)

        assertEquals(UiLanguage.Russian, state.uiLanguage)
        assertEquals(listOf(UiLanguage.Russian), persisted)
    }

    @Test
    fun `desktop choosing the same language is a no-op`() {
        val persisted = mutableListOf<UiLanguage>()
        val state = DesktopDesignState(
            initialUiLanguage = UiLanguage.English,
            onUiLanguageChange = { persisted += it },
        )

        state.chooseUiLanguage(UiLanguage.English)

        assertEquals(UiLanguage.English, state.uiLanguage)
        assertEquals(emptyList(), persisted)
    }

    @Test
    fun `mobile chooseUiLanguage updates state and persists`() {
        val persisted = mutableListOf<UiLanguage>()
        val state = MobileDesignState(onUiLanguageChange = { persisted += it })

        state.chooseUiLanguage(UiLanguage.Russian)

        assertEquals(UiLanguage.Russian, state.uiLanguage)
        assertEquals(listOf(UiLanguage.Russian), persisted)
    }
}
