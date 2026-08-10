package app.skerry.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/** Layout font set provided via [LocalFonts]: UI, monospace, and icon fonts. */
@Immutable
data class DesignFonts(
    val ui: FontFamily,
    val mono: FontFamily,
    val symbols: FontFamily,
)

val LocalFonts: ProvidableCompositionLocal<DesignFonts> = staticCompositionLocalOf {
    error("DesignFonts not provided — wrap the UI in SkerryDesktopDesign{}")
}

/**
 * Material Symbols Outlined icon: [name] (e.g. `folder_open`) renders as an icon-font ligature
 * in [BasicText]. Size/color are set per call site.
 *
 * The glyph is a ligature, so the icon's text *is* its name — and left in the semantics tree that
 * is what a screen reader says: "vpn_key". So the icon says nothing at all by default; it is
 * decoration, and the control around it carries the name. [contentDescription] is for the case
 * where the icon is the only label a control has — an icon-only button — and then the caller passes
 * the same localized string the tooltip shows.
 */
@Composable
fun Sym(
    name: String,
    size: TextUnit = 18.sp,
    color: Color = Skerry.colors.dim,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val symbols = LocalFonts.current.symbols
    BasicText(
        text = name,
        modifier = modifier.clearAndSetSemantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
        style = TextStyle(
            fontFamily = symbols,
            fontSize = size,
            color = color,
            textAlign = TextAlign.Center,
        ),
    )
}
