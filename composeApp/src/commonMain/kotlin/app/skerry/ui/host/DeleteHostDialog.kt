package app.skerry.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.host.Host
import app.skerry.ui.connection.connectionSubtitle
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_delete_host_title
import app.skerry.ui.generated.resources.shell_delete_host_body
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.shell_delete
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.design.D
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks

/**
 * Confirmation dialog for deleting a host profile (from the sidebar context menu). Only the
 * directory entry is removed; the linked keychain secret ([Host.credentialId]) stays in the vault,
 * since it's reusable across hosts and managed from the Vault tab.
 */
@Composable
fun DesktopDeleteHostDialog(host: Host, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(D.surfaceDeep)
                .border(1.dp, D.cyan14, RoundedCornerShape(12.dp))
                .consumeClicks()
                .padding(26.dp),
        ) {
            Txt(stringResource(Res.string.shell_delete_host_title, host.label), color = D.text, size = 16.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            Txt(host.connectionSubtitle(), color = D.dim, size = 12.5.sp, font = LocalFonts.current.mono, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Txt(
                stringResource(Res.string.shell_delete_host_body),
                color = D.dim, size = 12.5.sp, lineHeight = 18.sp,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Txt(stringResource(Res.string.shell_cancel), color = D.dim, size = 12.5.sp)
                }
                PrimaryButton(stringResource(Res.string.shell_delete), onClick = onConfirm, bg = D.sunset, fg = Color(0xFF1A0B07))
            }
        }
    }
}
