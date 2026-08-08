package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.theme.Skerry

/**
 * Form select built on [AnchoredDropdown]: a "value + chevron" trigger row ([SelectTrigger])
 * opens a menu of equal-width options ([DropdownMenuColumn]/[DropdownOption]); picking closes it.
 * [label] renders an option's localized text (may call stringResource, hence @Composable).
 */
@Composable
fun <T> DropdownField(
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    AnchoredDropdown(
        expanded = open,
        onDismiss = { open = false },
        trigger = { SelectTrigger(label(value), onClick = { open = !open }) },
        menu = { width ->
            DropdownMenuColumn(width) {
                options.forEach { option ->
                    DropdownOption(label(option), selected = option == value) { onPick(option); open = false }
                }
            }
        },
    )
}

/** Layout select trigger: value on the left, chevron on the right (clickable, opens the dropdown). */
@Composable
fun SelectTrigger(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).clickable(onClick = onClick).background(Skerry.colors.bg).border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Single line: a value longer than the trigger ellipsizes instead of wrapping to a second
        // line (which would grow the trigger's height and desync it from neighbouring controls).
        Txt(value, color = Skerry.colors.text, size = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Sym("expand_more", size = 16.sp, color = Skerry.colors.faint)
    }
}

/**
 * Dropdown menu column (layout surface + border). Height is capped at [maxHeight] with internal
 * scrolling, so a menu longer than the screen stays reachable instead of overflowing the popup.
 * (The AI model picker does not come through here: it needs a lazy list, which cannot live inside
 * this scroll — see `ModelPickerMenu`.)
 */
@Composable
fun DropdownMenuColumn(width: Dp, maxHeight: Dp = 320.dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(width)
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.surface2)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(8.dp))
            // scroll AFTER the surface/border modifiers: they must bound the viewport, not the
            // full (unscrolled) content height, or the frame slides away and overflows the popup.
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/**
 * Arrow button that opens a combo's menu, sitting next to an editable field (AI model picker on
 * both desktop and mobile). Icon-only, so it carries [label] for screen readers; the geometry is
 * per call site because the mobile field is taller than the desktop one.
 */
@Composable
fun ComboArrow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 7.dp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 11.dp,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Skerry.colors.bg)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(corner))
            .semantics { contentDescription = label }
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) { Sym("expand_more", size = 16.sp, color = Skerry.colors.faint) }
}

/** Dropdown menu item; the selected one is highlighted cyan. Long labels ellipsize on one line. */
@Composable
fun DropdownOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Txt(
        label,
        color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text,
        size = 12.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}
