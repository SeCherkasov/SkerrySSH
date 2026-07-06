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
 * "Night sea" palette design tokens plus the teal logo accent. Kept separate from
 * [app.skerry.ui.theme.SkerryColors] so the exact palette (including alpha-tinted cyan for
 * lines/backgrounds) lives next to the UI.
 */
object D {
    // Base background and surfaces
    val bg = Color(0xFF07141E)
    val railBg = Color(0xFF0A1620)
    val titleTop = Color(0xFF0C1A24)
    val titleBottom = Color(0xFF0A1620)
    val surface = Color(0xFF0A141B)
    val surface2 = Color(0xFF0B1A26)
    val surfaceDeep = Color(0xFF0E2230)
    val panel = Color(0xFF08121C)
    val terminalBg = Color(0xFF050E16)

    // Text
    val text = Color(0xFFE6ECEF)
    val textBright = Color(0xFFC9D6DE)
    val textMid = Color(0xFFB7C5CC)
    val dim = Color(0xFF8FA3B0)
    val faint = Color(0xFF5A7080)

    // Accents
    val cyan = Color(0xFF2BBDEE)
    val cyanBright = Color(0xFF5FD1F4)
    val moss = Color(0xFF5DCE9E)
    val sunset = Color(0xFFE07A5F)
    val amber = Color(0xFFF2A65A)

    // Teal logo accent
    val teal = Color(0xFF34D3C0)
    val tealLight = Color(0xFF7FF0E2)
    val tealDeep = Color(0xFF22B3A4)

    val white = Color(0xFFFFFFFF)
    val storm = Color(0xFFE94B4B)

    // Cyan-derived tones (lines / backgrounds) — alpha over cyan
    val cyan06 = cyan.copy(alpha = 0.06f)
    val cyan08 = cyan.copy(alpha = 0.08f)
    val cyan10 = cyan.copy(alpha = 0.10f)
    val cyan14 = cyan.copy(alpha = 0.14f)
    val cyan20 = cyan.copy(alpha = 0.20f)

    // Thin border lines
    val line = cyan06
    val lineStrong = cyan14

    // Badges
    val strictBg = sunset.copy(alpha = 0.16f)
    val strictFg = Color(0xFFE07060)
    val devBg = moss.copy(alpha = 0.16f)
    val whiteFaint = Color(0x1AFFFFFF) // rgba(255,255,255,0.1) — disabled toggle background

    // Shared surfaces/accents
    val ink = Color(0xFF0A1A26)   // text/icon color on cyan-accented buttons
    val card = Color(0x08FFFFFF)  // row card background (rgba(255,255,255,0.03))
    val scrim = Color(0xB304080C) // dimming under mobile sheets/dialogs
    val modalScrim = Color(0xB3060E16) // dimming under desktop modals
}

/** Layout UI font — Space Grotesk (400/500/600/700). */
@Composable
fun rememberSpaceGrotesk(): FontFamily = FontFamily(
    Font(Res.font.spacegrotesk_regular, weight = FontWeight.Normal),
    Font(Res.font.spacegrotesk_medium, weight = FontWeight.Medium),
    Font(Res.font.spacegrotesk_semibold, weight = FontWeight.SemiBold),
    Font(Res.font.spacegrotesk_bold, weight = FontWeight.Bold),
)

/** Monospace font — JetBrains Mono. */
@Composable
fun rememberMono(): FontFamily = FontFamily(
    Font(Res.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    Font(Res.font.jetbrainsmono_bold, weight = FontWeight.Bold),
)

/** Material Symbols Outlined icon font — icons render as ligatures by name (see [Sym]). */
@Composable
fun rememberMaterialSymbols(): FontFamily = FontFamily(
    Font(Res.font.material_symbols_outlined, weight = FontWeight.Normal),
)
