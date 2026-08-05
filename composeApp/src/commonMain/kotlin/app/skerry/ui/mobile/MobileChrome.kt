package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.LocalFonts
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

/**
 * One catalog line of the mobile template — a saved profile or an open session. Flat by design:
 * page background, no border, only the icon tile is filled, and the rows are told apart by
 * whitespace and by the mono type of the address. [badge] rides beside the label (the production
 * marking), [trailing] sits after the status dot (closing a session). [statusText] is what the dot
 * says out loud — colour alone reaches neither a screen reader nor a colourblind eye.
 *
 * Shared by the two catalogs and the Sessions list so one change of the row shape reaches all of
 * them; the parent supplies the horizontal gutter, which is why this row pads vertically only.
 */
@Composable
internal fun MobileCatalogRow(
    icon: String,
    label: String,
    subtitle: String,
    dotColor: Color,
    statusText: String,
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(Skerry.colors.card),
            contentAlignment = Alignment.Center,
        ) {
            Sym(icon, size = 21.sp, color = Skerry.colors.dim)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(
                    label,
                    color = Skerry.colors.text,
                    size = 15.sp,
                    weight = FontWeight.SemiBold,
                    font = LocalFonts.current.mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badge?.invoke()
            }
            Spacer(Modifier.height(3.dp))
            Txt(
                subtitle,
                color = Skerry.colors.dim,
                size = 12.sp,
                font = LocalFonts.current.mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Not independently clickable, so the description merges into the row's announcement.
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor).semantics { contentDescription = statusText })
        trailing?.invoke()
    }
}

/**
 * "Nothing here yet" for a mobile screen: glyph, what is empty, and why — centered, one look for
 * every list. [content] is an optional slot under the text for whatever gets the user out of the
 * empty state (the Sessions screen puts the two catalogs there).
 *
 * Extracted because the shell had grown three near-identical private copies (tunnels, known hosts,
 * sessions) drifting in icon size and alignment. The host catalog keeps its own note: it is a
 * left-aligned two-liner under the search field, not a centered placeholder for a blank screen.
 */
@Composable
internal fun MobileEmptyNote(
    icon: String,
    title: String,
    subtitle: String,
    topPadding: Dp = 50.dp,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().padding(top = topPadding, start = 22.dp, end = 22.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sym(icon, size = 28.sp, color = Skerry.colors.faint)
            Txt(title, color = Skerry.colors.text, size = 14.sp, weight = FontWeight.Medium)
            Txt(subtitle, color = Skerry.colors.dim, size = 12.sp, lineHeight = 17.sp, align = TextAlign.Center)
            content?.invoke(this)
        }
    }
}

/** Root-tab title (28sp Bold, letterSpacing -0.5) — Hosts/Desktops/Sessions/More. */
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
