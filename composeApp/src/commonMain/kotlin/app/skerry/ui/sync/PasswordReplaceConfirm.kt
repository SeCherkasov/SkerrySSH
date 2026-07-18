package app.skerry.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sync_password_replace_body
import app.skerry.ui.generated.resources.sync_password_replace_cancel
import app.skerry.ui.generated.resources.sync_password_replace_confirm
import app.skerry.ui.generated.resources.sync_password_replace_title
import app.skerry.ui.generated.resources.sync_setup_title
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for [SyncStatus.NeedsPasswordReplaceConfirm] (issue #28): the typed password is a valid
 * password for the existing account [accountId] but not this device's vault password, so joining re-keys
 * the vault to the account password — this device will start unlocking with it. The user explicitly
 * agrees ([SyncCoordinator.confirmPasswordReplace]) or backs out ([SyncCoordinator.cancelPasswordReplace]).
 *
 * Column content: the caller places it inside its own Column (mobile sync body / desktop dialog). For a
 * standalone modal on desktop use [PasswordReplaceConfirmDialog].
 */
@Composable
fun PasswordReplaceConfirm(sync: SyncCoordinator, accountId: String) {
    SyncStatusNotice(
        icon = "warning",
        iconColor = D.amber,
        title = stringResource(Res.string.sync_password_replace_title),
        subtitle = stringResource(Res.string.sync_password_replace_body, accountId),
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GhostButton(
            stringResource(Res.string.sync_password_replace_cancel),
            onClick = { sync.cancelPasswordReplace() },
            fg = D.dim,
        )
        GhostButton(
            stringResource(Res.string.sync_password_replace_confirm),
            onClick = { sync.confirmPasswordReplace() },
            icon = "cloud_sync",
            fg = D.amber,
            border = D.amber.copy(alpha = 0.4f),
        )
    }
}

/** [PasswordReplaceConfirm] as a standalone desktop modal (same card chrome as [SyncSetupDialog]). */
@Composable
fun PasswordReplaceConfirmDialog(sync: SyncCoordinator, accountId: String, onDismiss: () -> Unit) {
    val noop = remember { MutableInteractionSource() }
    ModalScrim(onDismiss = onDismiss, scrimColor = Color(0xB3060E16)) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .clickable(interactionSource = noop, indication = null, onClick = {})
                .padding(26.dp),
        ) {
            Txt(
                stringResource(Res.string.sync_setup_title),
                color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            PasswordReplaceConfirm(sync, accountId)
        }
    }
}
