package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.theme.Skerry

// Shared mobile screen chrome: push-screen header, root-tab title, FAB.

/**
 * Push-screen header: chevron_left (back) + 18sp Bold title. [plainBack] = true suppresses the
 * arrow's click indication (interactionSource + indication = null), the historical variant used
 * by some screens (Ports/Known/HostDetail); false is a plain clickable. The parameter preserves
 * each call site's prior behavior without visual changes.
 */
@Composable
internal fun MobilePushHeader(title: String, onBack: () -> Unit, plainBack: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val backModifier = if (plainBack) {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            )
        } else {
            Modifier.clickable(onClick = onBack)
        }
        Sym("chevron_left", size = 27.sp, color = Skerry.colors.cyanBright, modifier = backModifier)
        Txt(title, color = Skerry.colors.text, size = 18.sp, weight = FontWeight.Bold)
    }
}

/** Root-tab title (28sp Bold, letterSpacing -0.5) — Hosts/Snippets/Vault/More/Files. */
@Composable
internal fun MobileScreenTitle(text: String) {
    Txt(text, color = Skerry.colors.text, size = 28.sp, weight = FontWeight.Bold, letterSpacing = (-0.5).sp)
}

/**
 * Round mobile-tab FAB (cyan 56dp, radius 18, dark icon). [onClick] == null makes it inert (the
 * mock preview path). [icon]/[iconSize] are parameterized: Files toggles "+"/"x" at 26sp,
 * Hosts/Snippets use "+" at 28sp (each site's historical metrics preserved).
 */
@Composable
internal fun MobileFabButton(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: String = "add",
    iconSize: TextUnit = 28.sp,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Skerry.colors.cyan)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = iconSize, color = Skerry.colors.ink)
    }
}
