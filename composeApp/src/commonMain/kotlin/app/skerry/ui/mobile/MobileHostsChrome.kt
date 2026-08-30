package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalSync
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.design.fieldName
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_screen_title
import app.skerry.ui.generated.resources.rd_search_placeholder
import app.skerry.ui.generated.resources.shell_hosts
import app.skerry.ui.generated.resources.shell_search_hosts
import app.skerry.ui.generated.resources.shtail_group_collapse
import app.skerry.ui.generated.resources.shtail_group_expand
import app.skerry.ui.generated.resources.shtail_group_rename
import app.skerry.ui.host.ALL_HOSTS_CHIP
import app.skerry.ui.host.HostSection
import app.skerry.ui.host.hostChipLabel
import app.skerry.ui.sync.SyncIndicatorLevel
import app.skerry.ui.sync.syncIndicatorLocalized
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

// Chrome of the phone catalog screen ([MobileCatalogScreen]): its title strip, the search box, the
// filter chips, and a folder's header. Split out of MobileHostsView so the screen file holds the
// list and its drag-and-drop, and this one holds what is drawn around them (guidelines section 2).

/** Header: the section title (28sp) + sync indicator on the right. */
@Composable
internal fun HostsHeader(section: HostSection) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MobileScreenTitle(
            stringResource(
                when (section) {
                    HostSection.Terminal -> Res.string.shell_hosts
                    HostSection.RemoteDesktops -> Res.string.rd_screen_title
                },
            ),
        )
        // Sync indicator driven by session status (see syncIndicator), not just server
        // reachability: shows "paused/error" without a working session instead of a false green online.
        val syncC = LocalSync.current
        val ind = syncC?.let { syncIndicatorLocalized(it.status.collectAsState().value, it.serverReachable.collectAsState().value) }
        if (ind != null) {
            // Named for the same reason as the desktop status bar's: a bare glyph carrying the only
            // "sync has stopped" signal in the header.
            Sym(ind.icon, contentDescription = ind.label, size = 19.sp, color = when (ind.level) {
                SyncIndicatorLevel.OK -> Skerry.colors.moss
                SyncIndicatorLevel.WARN -> Skerry.colors.amber
                SyncIndicatorLevel.ERROR -> Skerry.colors.sunset
            })
        }
    }
}

/** Search field over host name/address/username/group of this section. */
@Composable
internal fun HostsSearch(query: String, section: HostSection, onChange: (String) -> Unit) {
    val placeholder = stringResource(
        when (section) {
            HostSection.Terminal -> Res.string.shell_search_hosts
            HostSection.RemoteDesktops -> Res.string.rd_search_placeholder
        },
    )
    // Outer padding is on the wrapper; the border lives in decorationBox so a click anywhere places the caret.
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Skerry.colors.text, fontSize = 15.sp, fontFamily = LocalFonts.current.ui),
        cursorBrush = SolidColor(Skerry.colors.cyan),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp)
            // As on desktop: the placeholder is the only label this field has (see fieldName).
            .fieldName(placeholder),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.card)
                    .border(1.dp, Skerry.colors.cyan08, RoundedCornerShape(11.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("search", size = 19.sp, color = Skerry.colors.faint)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Txt(placeholder, color = Skerry.colors.faint, size = 15.sp)
                    inner()
                }
            }
        },
    )
}

/** Filter-chip row: "All" + tags (prefixed with `#`); active chip highlighted cyan, horizontally scrollable. */
@Composable
internal fun HostsChips(chips: List<String>, active: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        chips.forEach { chip ->
            key(chip) {
                val on = chip == active
                val onClick = remember(chip) { { onSelect(chip) } }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) Skerry.colors.cyan14 else Skerry.colors.overlayMed)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                        .padding(horizontal = 13.dp, vertical = 5.dp),
                ) {
                    Txt(
                        hostChipLabel(chip),
                        color = if (on) Skerry.colors.cyanBright else Skerry.colors.dim,
                        size = 12.5.sp,
                        weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * Folder section header: collapse chevron + uppercase name + (edit pencil) + host count. Chevron
 * click toggles collapsed state, pencil click opens the Rename/Delete group dialog — hit zones are
 * strictly on the icons ([onToggle]/[onEdit]) so taps don't conflict with header drag (folder
 * reorder), as on desktop. [dropTarget] highlights the uppercase name when a host is dropped here.
 * [onEdit] == null for the synthetic "Ungrouped" bucket and the preview path (pencil hidden).
 */
@Composable
internal fun MobileFolderHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    dropTarget: Boolean,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)?,
    isDragging: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 22.dp, top = if (isDragging) 8.dp else 16.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isDragging) Skerry.colors.card else Color.Transparent)
            .border(1.dp, if (isDragging) Skerry.colors.cyan else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = if (isDragging) 8.dp else 0.dp, vertical = if (isDragging) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Icon-only, so it names itself after what it does and to which folder — parity with the
        // desktop sidebar's header (see FolderCollapseToggle).
        val toggleLabel = stringResource(
            if (collapsed) Res.string.shtail_group_expand else Res.string.shtail_group_collapse,
            name,
        )
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Sym(
                if (collapsed) "chevron_right" else "expand_more",
                size = 16.sp,
                color = Skerry.colors.faint,
                contentDescription = toggleLabel,
            )
        }
        // Template form: "PRODUCTION · 3" — the count rides with the name instead of sitting on the
        // far right, where it read as a column of unrelated numbers down the screen.
        Txt(
            "${name.uppercase()} · $count",
            color = if (isDragging || dropTarget) Skerry.colors.cyanBright else Skerry.colors.faint,
            size = 12.sp,
            weight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        if (onEdit != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEdit,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Sym(
                    "edit",
                    size = 14.sp,
                    color = Skerry.colors.faint,
                    contentDescription = stringResource(Res.string.shtail_group_rename, name),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
