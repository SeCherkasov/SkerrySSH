package app.skerry.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Layout font set provided via [LocalFonts]: UI, monospace, and icon fonts. */
@Immutable
data class DesignFonts(
    val ui: FontFamily,
    val mono: FontFamily,
    val symbols: FontFamily,
)

val LocalFonts: ProvidableCompositionLocal<DesignFonts> = staticCompositionLocalOf {
    error("DesignFonts не предоставлены — оберни UI в SkerryDesktopDesign{}")
}

/**
 * Material Symbols Outlined icon: [name] (e.g. `folder_open`) renders as an icon-font ligature
 * in [BasicText]. Size/color mirror the prototype's inline styles (`font-size`).
 */
@Composable
fun Sym(
    name: String,
    size: TextUnit = 18.sp,
    color: Color = D.dim,
    modifier: Modifier = Modifier,
) {
    val symbols = LocalFonts.current.symbols
    BasicText(
        text = name,
        modifier = modifier,
        style = TextStyle(
            fontFamily = symbols,
            fontSize = size,
            color = color,
            textAlign = TextAlign.Center,
        ),
    )
}
