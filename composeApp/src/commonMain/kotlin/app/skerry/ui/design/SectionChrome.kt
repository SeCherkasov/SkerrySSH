package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.skerry.ui.theme.Skerry

// Shared "section chrome": the sidebar/header/empty-state building blocks every section screen
// (hosts, snippets, vault, teams, tunnels, known hosts) draws from, so they read as one system
// instead of each re-inventing widths, caption sizes, header strips, and empty states.

/**
 * Width of a section's left sidebar (host tree / snippet library / vault categories / teams). One
 * value across sections so their left edges and content columns line up when switching views.
 */
val SIDEBAR_WIDTH = 262.dp

/**
 * Caption above a group inside a sidebar — "HOSTS", "LIBRARY", "VAULT", "TEAMS", "RECENT". Small,
 * faint, letter-spaced; the single source of that treatment so captions don't drift between 10 and
 * 11sp per screen.
 */
@Composable
fun SidebarSectionTitle(text: String, modifier: Modifier = Modifier) {
    Txt(
        text,
        modifier = modifier,
        color = Skerry.colors.faint,
        size = 10.sp,
        weight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

/**
 * Centered empty state for a section's content area: a glyph, a title, and one line explaining what
 * to do. Fills the area it is given and centers within it.
 */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = Skerry.colors.faint,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Sym(icon, size = 30.sp, color = tint)
        Spacer(Modifier.height(14.dp))
        Txt(title, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            // Wide enough that a normal subtitle stays on one line; only a long message (e.g. a
            // connection error) wraps, and then into a centered block rather than one wide line.
            Txt(
                subtitle,
                modifier = Modifier.widthIn(max = 680.dp),
                color = Skerry.colors.faint,
                size = 13.sp,
                lineHeight = 19.sp,
                align = TextAlign.Center,
            )
        }
        // Optional call-to-action (e.g. "Launch local shell" on the empty terminal), below the copy.
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/**
 * Top header strip of a section's content area: a title (with an optional subtitle) on the left and
 * an [actions] cluster on the right, over the panel surface, closed by a divider. One height and
 * padding for every section so their headers align with the sidebar captions and each other.
 */
/**
 * Search field for a section sidebar (hosts / snippet library / …). One look everywhere: rounded
 * card, a search glyph, the [placeholder] until text is typed, and a clear cross once it is. Border
 * and icon live in the decoration box so a click anywhere places the caret.
 */
@Composable
fun SidebarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Skerry.colors.text, fontSize = 12.5.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Skerry.colors.card)
                    .border(1.dp, Skerry.colors.line, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Sym("search", size = 16.sp, color = Skerry.colors.faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 12.5.sp)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Sym("close", size = 14.sp, color = Skerry.colors.faint)
                    }
                }
            }
        },
    )
}

/**
 * A single horizontally-scrollable row of filter [Chip]s (tags / categories). One row, never wraps —
 * the vertical mouse wheel scrolls it horizontally, so every section's filter strip reads the same.
 * [label] maps a chip key to its display text (e.g. an "All" chip to a localized word).
 */
@Composable
fun FilterChipRow(
    chips: List<String>,
    activeChip: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (String) -> String = { it },
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Row(
        modifier
            .horizontalScroll(scroll)
            // Desktop's vertical wheel doesn't scroll horizontally on its own, so Scroll events are
            // caught and the row is driven manually (delta.y, or delta.x on a horizontal wheel).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val d = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val delta = if (d.y != 0f) d.y else d.x
                        if (delta != 0f) {
                            scope.launch { scroll.scrollBy(delta * 64f) }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chips.forEach { chip ->
            key(chip) { Chip(label(chip), active = chip == activeChip, onClick = { onSelect(chip) }) }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(Skerry.colors.surface2)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Txt(title, color = Skerry.colors.text, size = 15.sp, weight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Txt(subtitle, modifier = Modifier.padding(top = 2.dp), color = Skerry.colors.dim, size = 12.sp)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
        HLine()
    }
}
