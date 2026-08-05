package app.skerry.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.ui.app.LocalCredentials
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSnippets
import app.skerry.ui.app.LocalTunnels
import app.skerry.ui.app.LocalVault
import app.skerry.ui.design.ConfirmActionDialog
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_trash_confirm_empty
import app.skerry.ui.generated.resources.settings_trash_confirm_forget
import app.skerry.ui.generated.resources.settings_trash_days_left
import app.skerry.ui.generated.resources.settings_trash_empty
import app.skerry.ui.generated.resources.settings_trash_empty_all
import app.skerry.ui.generated.resources.settings_trash_empty_hint
import app.skerry.ui.generated.resources.settings_trash_forget
import app.skerry.ui.generated.resources.settings_trash_gone
import app.skerry.ui.generated.resources.settings_trash_kind_credential
import app.skerry.ui.generated.resources.settings_trash_kind_host
import app.skerry.ui.generated.resources.settings_trash_kind_snippet
import app.skerry.ui.generated.resources.settings_trash_kind_tunnel
import app.skerry.ui.generated.resources.settings_trash_locked
import app.skerry.ui.generated.resources.settings_trash_restore
import app.skerry.ui.generated.resources.settings_trash_title
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/**
 * Live [TrashController] over the vault trash, or `null` without a vault (preview/mock). Restoring
 * writes straight into the vault, so the callback reloads the in-memory managers — the same thing
 * sync does after a pull, or a restored host stays invisible until the app is reopened.
 */
@Composable
fun rememberTrashController(): TrashController? {
    val vault = LocalVault.current
    val hosts = LocalHosts.current
    val credentials = LocalCredentials.current
    val snippets = LocalSnippets.current
    val tunnels = LocalTunnels.current
    return remember(vault, hosts, credentials, snippets, tunnels) {
        vault?.let {
            TrashController(TrashStore(it)) {
                hosts?.reload()
                credentials?.reload()
                snippets?.reload()
                tunnels?.reload()
            }
        }
    }
}

/**
 * Trash list shared by the desktop settings section and the Android screen: one row per deleted
 * record with Restore / Delete forever, plus the "Empty trash" action above it. Refreshes when it
 * appears — that's also when the retention window is applied ([TrashController.refresh]).
 */
@Composable
fun TrashList(controller: TrashController?, modifier: Modifier = Modifier) {
    if (controller == null) {
        Txt(stringResource(Res.string.settings_trash_locked), color = Skerry.colors.dim, size = 12.5.sp, modifier = modifier)
        return
    }
    LaunchedEffect(controller) { controller.refresh() }
    var pendingForget by remember { mutableStateOf<TrashItem?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    // Set when a restore found nothing left to restore (another device got there first): the row is
    // already gone from the refreshed list, so the message is the only feedback.
    var goneNotice by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        if (controller.items.isEmpty()) {
            EmptyTrash()
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                GhostButton(
                    stringResource(Res.string.settings_trash_empty_all),
                    onClick = { confirmEmpty = true },
                    icon = "delete",
                    fg = Skerry.colors.sunset,
                )
            }
            controller.items.forEach { item ->
                TrashRow(
                    item = item,
                    onRestore = {
                        goneNotice = !controller.restore(item)
                    },
                    onForget = { pendingForget = item },
                )
            }
            if (goneNotice) {
                Txt(
                    stringResource(Res.string.settings_trash_gone),
                    color = Skerry.colors.sunset,
                    size = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }

    pendingForget?.let { item ->
        ConfirmActionDialog(
            title = stringResource(Res.string.settings_trash_forget),
            message = stringResource(Res.string.settings_trash_confirm_forget, item.label),
            confirmLabel = stringResource(Res.string.settings_trash_forget),
            onConfirm = { controller.purge(item); pendingForget = null },
            onDismiss = { pendingForget = null },
        )
    }
    if (confirmEmpty) {
        ConfirmActionDialog(
            title = stringResource(Res.string.settings_trash_title),
            message = stringResource(Res.string.settings_trash_confirm_empty),
            confirmLabel = stringResource(Res.string.settings_trash_empty_all),
            onConfirm = { controller.emptyAll(); confirmEmpty = false },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun EmptyTrash() {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Sym("delete", size = 26.sp, color = Skerry.colors.faint)
        Txt(
            stringResource(Res.string.settings_trash_empty),
            color = Skerry.colors.text,
            size = 13.5.sp,
            weight = FontWeight.Medium,
            modifier = Modifier.padding(top = 10.dp),
        )
        Txt(
            stringResource(Res.string.settings_trash_empty_hint),
            color = Skerry.colors.dim,
            size = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Width a trash row needs before Restore / Delete forever fit beside the label. Not a breakpoint
 * system — a guard: the two buttons are unweighted children of the row, so below this they eat the
 * whole line and the weighted label is measured at ~zero (it rendered one character per line).
 * Generous on purpose, and generous in the safe direction: too high only stacks a row that could
 * have stayed inline.
 */
internal val TRASH_ROW_INLINE_MIN_WIDTH = 480.dp

/** Whether a trash row of [rowWidth] must move its actions to a line of their own. */
internal fun trashActionsStacked(rowWidth: Dp): Boolean = rowWidth < TRASH_ROW_INLINE_MIN_WIDTH

@Composable
private fun TrashRow(item: TrashItem, onRestore: () -> Unit, onForget: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Skerry.colors.card)
            .border(1.dp, Skerry.colors.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val actions: @Composable () -> Unit = {
            GhostButton(stringResource(Res.string.settings_trash_restore), onClick = onRestore, icon = "restart_alt")
            GhostButton(
                stringResource(Res.string.settings_trash_forget),
                onClick = onForget,
                icon = "delete",
                fg = Skerry.colors.sunset,
            )
        }
        if (trashActionsStacked(maxWidth)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Sym(item.type.trashIcon(), size = 17.sp, color = Skerry.colors.cyanBright)
                    TrashRowLabel(item, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym(item.type.trashIcon(), size = 17.sp, color = Skerry.colors.cyanBright)
                TrashRowLabel(item, Modifier.weight(1f))
                actions()
            }
        }
    }
}

/** Name of the deleted record over its kind and how long it has left. */
@Composable
private fun TrashRowLabel(item: TrashItem, modifier: Modifier = Modifier) {
    Column(modifier) {
        Txt(item.label, color = Skerry.colors.text, size = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val daysLeft = stringResource(Res.string.settings_trash_days_left, item.daysLeft)
        val kind = item.type.trashKind()
        Txt(
            if (kind.isEmpty()) daysLeft else "$kind · $daysLeft",
            color = Skerry.colors.dim,
            size = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// The branches cover TrashStore.SUPPORTED; anything else falls back to a neutral icon/blank kind
// rather than pretending to be a host — a type added to SUPPORTED without a label here is then
// visible as unlabeled instead of silently mislabeled.
private fun RecordType.trashIcon(): String = when (this) {
    RecordType.HOST -> "dns"
    RecordType.CREDENTIAL -> "key"
    RecordType.SNIPPET -> "code_blocks"
    RecordType.TUNNEL -> "lan"
    else -> "delete"
}

@Composable
private fun RecordType.trashKind(): String = when (this) {
    RecordType.HOST -> stringResource(Res.string.settings_trash_kind_host)
    RecordType.CREDENTIAL -> stringResource(Res.string.settings_trash_kind_credential)
    RecordType.SNIPPET -> stringResource(Res.string.settings_trash_kind_snippet)
    RecordType.TUNNEL -> stringResource(Res.string.settings_trash_kind_tunnel)
    else -> ""
}
