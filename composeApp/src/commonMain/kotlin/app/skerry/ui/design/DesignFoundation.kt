package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import app.skerry.ui.generated.resources.material_symbols_outlined
import app.skerry.ui.generated.resources.spacegrotesk_bold
import app.skerry.ui.generated.resources.spacegrotesk_medium
import app.skerry.ui.generated.resources.spacegrotesk_regular
import app.skerry.ui.generated.resources.spacegrotesk_semibold

/**
 * Дизайн-токены десктопного макета `docs/new/Skerry.html` — палитра «night sea» + бирюзовый
 * сигнал логотипа. Значения перенесены 1:1 из инлайн-стилей и `renderVals()` прототипа.
 * Намеренно отдельный объект от [app.skerry.ui.theme.SkerryColors], чтобы точная палитра макета
 * (включая alpha-оттенки cyan для линий/фонов) жила рядом с воспроизведённым UI.
 */
object D {
    // Базовый фон и поверхности
    val bg = Color(0xFF07141E)
    val railBg = Color(0xFF0A1620)
    val titleTop = Color(0xFF0C1A24)
    val titleBottom = Color(0xFF0A1620)
    val surface = Color(0xFF0A141B)
    val surface2 = Color(0xFF0B1A26)
    val surfaceDeep = Color(0xFF0E2230)
    val panel = Color(0xFF08121C)
    val terminalBg = Color(0xFF050E16)

    // Текст
    val text = Color(0xFFE6ECEF)
    val textBright = Color(0xFFC9D6DE)
    val textMid = Color(0xFFB7C5CC)
    val dim = Color(0xFF8FA3B0)
    val faint = Color(0xFF5A7080)

    // Акценты
    val cyan = Color(0xFF2BBDEE)
    val cyanBright = Color(0xFF5FD1F4)
    val moss = Color(0xFF5DCE9E)
    val sunset = Color(0xFFE07A5F)
    val amber = Color(0xFFF2A65A)

    // Бирюза-сигнал (логотип)
    val teal = Color(0xFF34D3C0)
    val tealLight = Color(0xFF7FF0E2)
    val tealDeep = Color(0xFF22B3A4)

    val white = Color(0xFFFFFFFF)
    val storm = Color(0xFFE94B4B)

    // Cyan-производные (линии / фоны) — alpha поверх cyan
    val cyan06 = cyan.copy(alpha = 0.06f)
    val cyan08 = cyan.copy(alpha = 0.08f)
    val cyan10 = cyan.copy(alpha = 0.10f)
    val cyan14 = cyan.copy(alpha = 0.14f)
    val cyan20 = cyan.copy(alpha = 0.20f)

    // Тонкие линии границ
    val line = cyan06
    val lineStrong = cyan14

    // Бейджи
    val strictBg = sunset.copy(alpha = 0.16f)
    val strictFg = Color(0xFFE07060)
    val devBg = moss.copy(alpha = 0.16f)
    val whiteFaint = Color(0x1AFFFFFF) // rgba(255,255,255,0.1) — фон выключенного тумблера
}

/** UI-шрифт макета — Space Grotesk (400/500/600/700). */
@Composable
fun rememberSpaceGrotesk(): FontFamily = FontFamily(
    Font(Res.font.spacegrotesk_regular, weight = FontWeight.Normal),
    Font(Res.font.spacegrotesk_medium, weight = FontWeight.Medium),
    Font(Res.font.spacegrotesk_semibold, weight = FontWeight.SemiBold),
    Font(Res.font.spacegrotesk_bold, weight = FontWeight.Bold),
)

/** Моноширинный шрифт — JetBrains Mono. */
@Composable
fun rememberMono(): FontFamily = FontFamily(
    Font(Res.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    Font(Res.font.jetbrainsmono_bold, weight = FontWeight.Bold),
)

/** Иконочный шрифт Material Symbols Outlined — иконки рендерятся лигатурами по имени (см. [Sym]). */
@Composable
fun rememberMaterialSymbols(): FontFamily = FontFamily(
    Font(Res.font.material_symbols_outlined, weight = FontWeight.Normal),
)
