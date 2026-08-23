package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.design.fieldValueName
import app.skerry.ui.design.folderLabel
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's "Group" field, shared by everything that files a record into a folder: the connection
 * sheet, the snippet sheet, the runbook sheet and the keychain's edit sheet. Mobile counterpart of
 * [app.skerry.ui.design.GroupSelectField] — same three choices ("No group", the folders that exist,
 * "New group…"), drawn at a size a finger can hit.
 *
 * No free-form entry in the field itself, only the list plus explicit creation: typing would let a
 * `Production` and a `production` become two folders that look like one.
 *
 * [onCreateGroup] is raised rather than handled here: the create dialog is a full-screen overlay
 * ([MobileGroupCreateDialog]), and it has to be mounted at the sheet's root — inside the form's
 * scroll it would scroll with the fields instead of standing over them and above the keyboard.
 */
@Composable
internal fun MobileGroupSelectField(
    value: String,
    groups: List<String>,
    onChange: (String) -> Unit,
    onCreateGroup: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val chosen = value.isNotBlank()
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            val shown = if (chosen) folderLabel(value) else stringResource(Res.string.conn_group_none)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.bg)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuOpen = !menuOpen }
                    // The caption above is a sibling this row would never reach on its own, and a
                    // trigger that merges its children cannot be named with fieldName.
                    .fieldValueName(shown)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt(
                    shown,
                    color = if (chosen) Skerry.colors.text else Skerry.colors.faint,
                    size = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Sym(if (menuOpen) "expand_less" else "expand_more", size = 20.sp, color = Skerry.colors.faint)
            }
        },
        menu = { width ->
            Column(
                Modifier
                    .width(width)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Skerry.colors.surface2)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(11.dp))
                    .heightIn(max = GROUP_MENU_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                MobileGroupOption(stringResource(Res.string.conn_group_none), selected = !chosen) {
                    onChange("")
                    menuOpen = false
                }
                groups.forEach { group ->
                    key(group) {
                        MobileGroupOption(folderLabel(group), selected = value == group) {
                            onChange(group)
                            menuOpen = false
                        }
                    }
                }
                HLine(modifier = Modifier.padding(vertical = 4.dp))
                MobileGroupOption(stringResource(Res.string.conn_group_new), selected = false, icon = "add") {
                    menuOpen = false
                    onCreateGroup()
                }
            }
        },
    )
}

/** Enough rows to scan, short enough that a long folder list scrolls instead of covering the sheet. */
private val GROUP_MENU_HEIGHT = 320.dp

/**
 * One option row of a phone dropdown: optional icon + name + a checkmark when selected. Shared by
 * this field and the connection sheet's other selects (jump host, keep-alive) — they draw the same
 * row, and a long value ellipsizes rather than pushing the checkmark off the menu.
 */
@Composable
internal fun MobileGroupOption(title: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (icon != null) Sym(icon, size = 18.sp, color = Skerry.colors.cyanBright)
        Txt(
            title,
            color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text,
            size = 14.sp,
            weight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Sym("check", size = 17.sp, color = Skerry.colors.cyanBright)
    }
}
