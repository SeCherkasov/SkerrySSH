package app.skerry.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ibmplexsans_bold
import app.skerry.ui.generated.resources.ibmplexsans_medium
import app.skerry.ui.generated.resources.ibmplexsans_regular
import app.skerry.ui.generated.resources.ibmplexsans_semibold
import app.skerry.ui.generated.resources.ibmplexsanssc_regular
import app.skerry.ui.generated.resources.ibmplexsanssc_semibold
import app.skerry.ui.generated.resources.jetbrainsmono_bold
import app.skerry.ui.generated.resources.jetbrainsmono_regular
import app.skerry.ui.generated.resources.material_symbols_outlined
import app.skerry.ui.i18n.LocalAppLocale

/**
 * Layout UI font — IBM Plex Sans (400/500/600/700): one family whose Latin, Cyrillic and Greek
 * share a single design, so mixed-script labels don't fall apart into system fallbacks.
 *
 * The Simplified-Chinese UI swaps the whole family to the matching IBM Plex Sans SC (same Plex
 * design, CJK + harmonized Latin/Cyrillic): Compose has no portable per-glyph fallback to a
 * bundled font, so the family follows the resolved locale instead. SC ships two masters
 * (Regular/SemiBold, ~8 MB each) — Medium/Bold map onto them.
 */
@Composable
fun rememberUiFont(): FontFamily {
    return if (LocalAppLocale.current.startsWith("zh", ignoreCase = true)) {
        FontFamily(
            Font(Res.font.ibmplexsanssc_regular, weight = FontWeight.Normal),
            Font(Res.font.ibmplexsanssc_regular, weight = FontWeight.Medium),
            Font(Res.font.ibmplexsanssc_semibold, weight = FontWeight.SemiBold),
            Font(Res.font.ibmplexsanssc_semibold, weight = FontWeight.Bold),
        )
    } else {
        FontFamily(
            Font(Res.font.ibmplexsans_regular, weight = FontWeight.Normal),
            Font(Res.font.ibmplexsans_medium, weight = FontWeight.Medium),
            Font(Res.font.ibmplexsans_semibold, weight = FontWeight.SemiBold),
            Font(Res.font.ibmplexsans_bold, weight = FontWeight.Bold),
        )
    }
}

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
