package app.skerry.ui.design

import app.skerry.ui.i18n.UiLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Выбор языка интерфейса в [DesktopDesignState]/[MobileDesignState] обновляет состояние и сообщает
 * наружу для персиста; повтор текущего языка — no-op (ни записи), как у выбора шрифта терминала.
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
