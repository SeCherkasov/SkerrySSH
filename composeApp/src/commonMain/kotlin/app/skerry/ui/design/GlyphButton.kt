package app.skerry.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * Square icon-glyph tap target: a [Sym] centered in a rounded box that carries the accessible
 * name and [Role.Button]. Extracted once the third private copy of the shape appeared (terminal
 * header icons, the auto-fit nudge) — each copy was free to drift, and one already had (a text
 * glyph where its visual sibling used the icon font).
 *
 * [onLongClick] adds a secondary action; pass [onLongClickLabel] with it so assistive tech can
 * announce the gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlyphButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    box: Dp = 40.dp,
    iconSize: TextUnit = 21.sp,
    iconColor: Color = Skerry.colors.dim,
    background: Color = Color.Transparent,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    Box(
        modifier
            .size(box)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .semantics { contentDescription = label }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = iconSize, color = iconColor)
    }
}
