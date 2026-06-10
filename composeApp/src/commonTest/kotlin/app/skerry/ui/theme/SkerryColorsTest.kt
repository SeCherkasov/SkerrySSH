package app.skerry.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Токены сверяются с `:root` HTML-прототипов в docs/ (палитра «night sea»).
 * Прототипы — источник правды; при их изменении сначала обновляется этот тест.
 */
class SkerryColorsTest {

    // --- Поверхности ---

    @Test
    fun `surface tokens match prototype root`() {
        assertEquals(Color(0xFF0E2230), SkerryColors.deep)
        assertEquals(Color(0xFF0A1A26), SkerryColors.deep2)
        assertEquals(Color(0xFF07141E), SkerryColors.nightSea)
        assertEquals(Color(0xFF0B1A26), SkerryColors.nightSeaSoft)
        assertEquals(Color(0xFF050E16), SkerryColors.terminalBg)
    }

    // --- Primary: cyan ---

    @Test
    fun `cyan tokens match prototype root`() {
        assertEquals(Color(0xFF2BBDEE), SkerryColors.cyan)
        assertEquals(Color(0xFF5FD1F4), SkerryColors.cyanBright)
    }

    @Test
    fun `translucent cyan tokens are cyan with prototype alpha`() {
        // rgba(43,189,238,…) в CSS — те же RGB, отличается только альфа
        assertEquals(SkerryColors.cyan.copy(alpha = 0.10f), SkerryColors.cyanSoft)
        assertEquals(SkerryColors.cyan.copy(alpha = 0.20f), SkerryColors.cyanDim)
        assertEquals(SkerryColors.cyan.copy(alpha = 0.06f), SkerryColors.line)
        assertEquals(SkerryColors.cyan.copy(alpha = 0.14f), SkerryColors.lineStrong)
    }

    // --- Secondary: amber (только AI/lighthouse-моменты) ---

    @Test
    fun `amber tokens match prototype root`() {
        assertEquals(Color(0xFFF2A65A), SkerryColors.amber)
        assertEquals(Color(0xFFFFC078), SkerryColors.amberBright)
        assertEquals(SkerryColors.amber.copy(alpha = 0.14f), SkerryColors.amberSoft)
    }

    // --- Семантические ---

    @Test
    fun `semantic tokens match prototype root`() {
        assertEquals(Color(0xFF5DCE9E), SkerryColors.moss)
        assertEquals(Color(0xFFE07A5F), SkerryColors.sunset)
        assertEquals(Color(0xFFE94B4B), SkerryColors.storm)
    }

    // --- Текст ---

    @Test
    fun `text tokens match prototype root`() {
        assertEquals(Color(0xFFE6ECEF), SkerryColors.text)
        assertEquals(Color(0xFF8FA3B0), SkerryColors.textDim)
        assertEquals(Color(0xFF5A7080), SkerryColors.textFaint)
    }

    // --- Маппинг в Material ColorScheme ---

    @Test
    fun `dark color scheme maps tokens to material roles`() {
        val scheme = skerryDarkColorScheme()

        assertEquals(SkerryColors.cyan, scheme.primary)
        assertEquals(SkerryColors.nightSea, scheme.onPrimary)
        assertEquals(SkerryColors.cyanBright, scheme.secondary)
        // amber — узкий акцент AI/lighthouse, в Material-роли — tertiary
        assertEquals(SkerryColors.amber, scheme.tertiary)

        assertEquals(SkerryColors.nightSea, scheme.background)
        assertEquals(SkerryColors.text, scheme.onBackground)
        assertEquals(SkerryColors.nightSea, scheme.surface)
        assertEquals(SkerryColors.text, scheme.onSurface)
        assertEquals(SkerryColors.deep, scheme.surfaceVariant)
        assertEquals(SkerryColors.textDim, scheme.onSurfaceVariant)

        assertEquals(SkerryColors.storm, scheme.error)
        assertEquals(SkerryColors.lineStrong, scheme.outline)
        assertEquals(SkerryColors.line, scheme.outlineVariant)
    }
}
