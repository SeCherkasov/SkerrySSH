package app.skerry.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.text.MAX_GROUP_LENGTH
import app.skerry.shared.text.capText
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * The "Group" field, shared by everything that files a record into a folder: the connection modal,
 * the snippet editor, the runbook editor and the keychain's edit dialog.
 *
 * A select rather than free text — "No group", the folders that already exist ([groups]), and
 * "New group…", which asks for the name in its own dialog. Typing into the field itself would let a
 * `Production` and a `production` (or a `Prodcution`) become two folders that look like one, which
 * is the failure a picker exists to prevent.
 *
 * [value] is the current name, empty for none; [onChange] receives the picked name as typed, with no
 * normalization — the caller stores it through [app.skerry.shared.text.normalizeGroup], which is the
 * one place that decides what a stored folder name looks like.
 */
/**
 * Width of the "Group" select in a desktop editor: wide enough for a folder name, narrow enough that
 * the select does not span the panel. One value because the editors sit in the same chrome side by
 * side — two of them drift the day one is tuned.
 */
val GROUP_FIELD_WIDTH = 220.dp

@Composable
fun GroupSelectField(value: String, groups: List<String>, onChange: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    val chosen = value.isNotBlank()
    AnchoredDropdown(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        trigger = {
            SelectTrigger(
                if (chosen) folderLabel(value) else stringResource(Res.string.conn_group_none),
                onClick = { menuOpen = !menuOpen },
            )
        },
        menu = { width ->
            DropdownMenuColumn(width, maxHeight = GROUP_MENU_HEIGHT) {
                GroupOption(stringResource(Res.string.conn_group_none), selected = !chosen) {
                    onChange("")
                    menuOpen = false
                }
                groups.forEach { group ->
                    key(group) {
                        GroupOption(folderLabel(group), selected = value == group) {
                            onChange(group)
                            menuOpen = false
                        }
                    }
                }
                HLine(modifier = Modifier.padding(vertical = 4.dp))
                GroupOption(stringResource(Res.string.conn_group_new), selected = false, icon = "add") {
                    menuOpen = false
                    createOpen = true
                }
            }
        },
    )
    if (createOpen) {
        GroupCreateDialog(
            onDismiss = { createOpen = false },
            onCreate = { name -> onChange(name.trim()); createOpen = false },
        )
    }
}

/** Taller than a name needs, short enough that a long folder list scrolls instead of covering the form. */
private val GROUP_MENU_HEIGHT = 240.dp

/** One option row of the group select: optional icon plus title plus a checkmark when selected. */
@Composable
private fun GroupOption(title: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Skerry.colors.cyan10 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) Sym(icon, size = 15.sp, color = Skerry.colors.cyanBright)
        Txt(
            title,
            color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text,
            size = 12.5.sp,
            weight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Sym("check", size = 15.sp, color = Skerry.colors.cyanBright)
    }
}

/**
 * Modal dialog for creating a new folder (a `Popup`, so it stands over the modal or the sheet that
 * opened it): name field plus Cancel/Create. A blank name creates nothing (button disabled). The
 * name is only handed back to the field — the folder itself starts existing when the record that
 * carries it is saved, which is why there is nothing to undo if the form is cancelled.
 */
@Composable
private fun GroupCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        ModalScrim(onDismiss = onDismiss) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Skerry.colors.surfaceDeep)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
                    .consumeClicks()
                    .padding(22.dp),
            ) {
                Txt(stringResource(Res.string.conn_group_new_title), color = Skerry.colors.text, size = 16.sp, weight = FontWeight.SemiBold)
                Box(Modifier.size(14.dp))
                // Capped per keystroke, so the field can never hold more than the record would keep
                // ([app.skerry.shared.text.normalizeGroup]).
                // "Production" is an example of what to type, not what the field is, and the caption
                // this dialog opens under is "Group" — which names the select, not this box.
                CompositionLocalProvider(LocalFieldLabel provides stringResource(Res.string.shell_group_name_placeholder)) {
                    ModalTextField(name, { name = capText(it, MAX_GROUP_LENGTH) }, "Production")
                }
                Box(Modifier.size(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    CancelButton(stringResource(Res.string.conn_cancel), onClick = onDismiss)
                    PrimaryButton(stringResource(Res.string.conn_create), onClick = { onCreate(name) }, enabled = canCreate)
                }
            }
        }
    }
}
