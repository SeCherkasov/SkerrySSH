package app.skerry.ui.design

import androidx.compose.runtime.Composable
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
