package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt

/** How long the transient "Copied" flash stays up before fading out. */
internal const val COPIED_BANNER_MS = 1400L

/**
 * Whether the transient "Copied" flash should be visible for [nonce]. 0 is the initial / reset value
 * (a tab switch re-keys the copy counter) and MUST hide the banner; any non-zero value — including a
 * negative one after the counter wraps past Int.MAX_VALUE — is a real copy and shows it. Pulled out of
 * the composable so the show/hide contract is unit-testable ([CopiedBannerTest]).
 */
internal fun shouldShowCopiedFlash(nonce: Int): Boolean = nonce != 0

/**
 * A small rounded pill floated over the terminal (disconnect status, copy confirmation, …): an icon +
 * one line of mono text, a translucent [background] and a 40%-[accent] border. [contentColor] defaults
 * to [accent] but can differ (e.g. a brighter foreground on a dark accent). [modifier] positions it
 * (typically `Modifier.align(Alignment.TopCenter)`).
 */
@Composable
internal fun TerminalOverlayBanner(
    icon: String,
    text: String,
    accent: Color,
    background: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = accent,
) {
    val mono = LocalFonts.current.mono
    Row(
        modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Sym(icon, size = 14.sp, color = contentColor)
        Txt(text, color = contentColor, size = 11.5.sp, font = mono)
    }
}
