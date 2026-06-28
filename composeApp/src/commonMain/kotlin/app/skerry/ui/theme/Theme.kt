package app.skerry.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Дизайн-токены Skerry — палитра «night sea».
 */
object SkerryColors {
    // Поверхности
    val deep = Color(0xFF0E2230)
    val deep2 = Color(0xFF0A1A26)
    val nightSea = Color(0xFF07141E)
    val nightSeaSoft = Color(0xFF0B1A26)
    val terminalBg = Color(0xFF050E16)

    // Primary: cyan — active, focus, links, status
    val cyan = Color(0xFF2BBDEE)
    val cyanBright = Color(0xFF5FD1F4)
    val cyanSoft = cyan.copy(alpha = 0.10f)
    val cyanDim = cyan.copy(alpha = 0.20f)

    // Secondary: amber — ТОЛЬКО AI suggestions и lighthouse-моменты
    val amber = Color(0xFFF2A65A)
    val amberBright = Color(0xFFFFC078)
    val amberSoft = amber.copy(alpha = 0.14f)

    // Семантические
    val moss = Color(0xFF5DCE9E)
    val sunset = Color(0xFFE07A5F)
    val storm = Color(0xFFE94B4B)

    // Линии и текст
    val line = cyan.copy(alpha = 0.06f)
    val lineStrong = cyan.copy(alpha = 0.14f)
    val text = Color(0xFFE6ECEF)
    val textDim = Color(0xFF8FA3B0)
    val textFaint = Color(0xFF5A7080)
}

fun skerryDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = SkerryColors.cyan,
    onPrimary = SkerryColors.nightSea,
    primaryContainer = SkerryColors.deep,
    onPrimaryContainer = SkerryColors.cyanBright,
    secondary = SkerryColors.cyanBright,
    onSecondary = SkerryColors.nightSea,
    tertiary = SkerryColors.amber,
    onTertiary = SkerryColors.nightSea,
    background = SkerryColors.nightSea,
    onBackground = SkerryColors.text,
    surface = SkerryColors.nightSea,
    onSurface = SkerryColors.text,
    surfaceVariant = SkerryColors.deep,
    onSurfaceVariant = SkerryColors.textDim,
    surfaceContainer = SkerryColors.deep2,
    surfaceContainerHigh = SkerryColors.deep,
    surfaceContainerLow = SkerryColors.nightSeaSoft,
    error = SkerryColors.storm,
    onError = SkerryColors.nightSea,
    outline = SkerryColors.lineStrong,
    outlineVariant = SkerryColors.line,
)

@Composable
fun SkerryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = skerryDarkColorScheme(),
        content = content,
    )
}
