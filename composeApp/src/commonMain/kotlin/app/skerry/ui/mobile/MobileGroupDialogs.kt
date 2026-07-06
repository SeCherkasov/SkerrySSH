package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.nav.PlatformBackHandler
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_cancel
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_delete
import app.skerry.ui.generated.resources.conn_group_new_title
import app.skerry.ui.generated.resources.conn_group_rename_hint
import app.skerry.ui.generated.resources.conn_group_rename_title
import app.skerry.ui.generated.resources.conn_save
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.Txt

// Create/Rename group dialogs for the New connection sheet, built on [MobileCenteredDialog].

/**
 * Frame for a small centered modal dialog: full-screen scrim (tap outside dismisses), centered
 * card that consumes its own clicks. Stays above the keyboard because the root `safeDrawing`
 * shrinks the area above the IME and `Center` centers within what's left.
 */
@Composable
internal fun MobileCenteredDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(D.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SheetPanel)
                .border(1.dp, D.cyan14, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(20.dp),
            content = content,
        )
    }
}

/**
 * Modal dialog for creating a new group: full-screen overlay at the sheet root (not in the form's
 * scroll), centered above the keyboard, name field + Cancel/Create. Empty name disables Create.
 * The name is only set on the form; the folder appears in the catalog once the host is saved.
 */
@Composable
internal fun MobileGroupCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    val submit = { if (canCreate) onCreate(name) }
    MobileCenteredDialog(onDismiss = onDismiss) {
        Txt(stringResource(Res.string.conn_group_new_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        MobileFormInput(name, { name = it }, "Production")
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.conn_cancel), color = D.dim, size = 15.sp, weight = FontWeight.Medium)
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canCreate) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(stringResource(Res.string.conn_create), color = D.ink, size = 15.sp, weight = FontWeight.Bold)
            }
        }
    }
}

/**
 * "Rename group" dialog (pencil icon on the folder header): full-screen overlay above the
 * keyboard. Name field pre-filled with [initialName]; "Save" renames (hosts move with the group),
 * "Delete group" ungroups (profiles are kept). Empty/unchanged name disables "Save".
 */
@Composable
internal fun MobileGroupRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val canSave = name.isNotBlank() && name.trim() != initialName
    // Trim here so the controller (Host.group) and collapsedGroups sync see the same canonical
    // key; otherwise a trailing space would desync the folder's collapsed state.
    val submit = { if (canSave) onSave(name.trim()) }
    // System back/gesture dismisses the dialog (like tapping the scrim), intercepted before frame navigation.
    PlatformBackHandler(onBack = onDismiss)
    MobileCenteredDialog(onDismiss = onDismiss) {
        Txt(stringResource(Res.string.conn_group_rename_title), color = D.text, size = 18.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Txt(stringResource(Res.string.conn_group_rename_hint), color = D.dim, size = 12.5.sp)
        Spacer(Modifier.height(14.dp))
        MobileFormInput(name, { name = it }, "Production")
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDelete)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Txt(stringResource(Res.string.conn_group_delete), color = D.sunset, size = 15.sp, weight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canSave) D.cyan else D.cyan.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = submit)
                    .padding(horizontal = 18.dp, vertical = 13.dp),
            ) {
                Txt(stringResource(Res.string.conn_save), color = D.ink, size = 15.sp, weight = FontWeight.Bold)
            }
        }
    }
}
