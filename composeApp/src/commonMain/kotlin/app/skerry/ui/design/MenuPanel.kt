package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * The app's pop-up menu: a rounded panel of rows. Every menu the app opens on a right click or a
 * "⋮" button wears this — including the one Compose itself opens over selected text, which is why
 * it lives in the design layer rather than next to any one call site.
 *
 * Two row shapes live here and there is no third for an action: [MenuItem] for a label alone (the
 * host row's menu, the text context menu) and [MenuActionRow] for a glyph beside it (the chrome
 * menus, whose entries also exist as icons in a toolbar and are recognized by the same glyph). A
 * row that carries *state* rather than an action is the remote desktop's pair, `RemoteDesktopMenus`
 * `MenuRow` (picked, with a check) and `CheckRow` (a switch) — local to that menu on purpose, and
 * where to look before writing a fifth.
 *
 * The panel sizes itself to its widest row, with the floor [MenuItem] and [MenuActionRow] carry.
 * [width] is for a menu that cannot be measured that way — one holding a preview of arbitrary text,
 * which would otherwise stretch the panel across the screen.
 */
@Composable
fun MenuPanel(modifier: Modifier = Modifier, width: Dp? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            // Intrinsic rather than wrap-content: the rows fill the panel so a short label still
            // gets the whole row as a click target, and a popup measures its content against the
            // window — so in a wrap-content column those rows would open the menu as a bar the full
            // width of the screen instead of sizing it to the widest one.
            .then(if (width != null) Modifier.width(width) else Modifier.width(IntrinsicSize.Max))
            .clip(RoundedCornerShape(7.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.lineStrong, RoundedCornerShape(7.dp))
            .padding(MENU_PADDING),
    ) {
        content()
    }
}

/** One row of a [MenuPanel]. [color] carries the destructive variant (`Skerry.colors.sunset`). */
@Composable
fun MenuItem(label: String, color: Color = Skerry.colors.text, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(min = MENU_MIN_WIDTH)
            .clip(RoundedCornerShape(5.dp))
            // The row that opens a dialog took the keyboard on its press; see [handsKeyboardBack].
            .handsKeyboardBack()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Txt(label, color = color, size = 12.sp)
    }
}

/** A [MenuPanel] row that carries the glyph its action has in the toolbar it collapsed out of. */
@Composable
fun MenuActionRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(min = MENU_MIN_WIDTH)
            .clip(RoundedCornerShape(5.dp))
            .handsKeyboardBack()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(icon, size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(label, color = Skerry.colors.dim, size = 12.sp)
    }
}

/** The air the panel keeps between its frame and its rows. */
internal val MENU_PADDING = 4.dp

/**
 * The width floor keeps a menu from collapsing onto its longest label — short verbs in some locales
 * leave a sliver of a click target, and the same menu would change width from row to row depending
 * on which actions that row offers.
 */
internal val MENU_MIN_WIDTH = 140.dp
