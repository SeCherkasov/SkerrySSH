package app.skerry.ui.update

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_about_update_available
import app.skerry.ui.generated.resources.settings_about_update_open
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * "Version X is available" line plus the release-page button, shared by the desktop About section
 * and the mobile About screen. Shows [UpdateNoticeController.available] rather than `notice`: a
 * status-bar dismissal must not hide the release here. Emits nothing when there is no update.
 */
@Composable
fun UpdateAvailableBlock() {
    val update = LocalUpdates.current?.available ?: return
    val uriHandler = LocalUriHandler.current
    Txt(
        stringResource(Res.string.settings_about_update_available, update.versionLabel),
        color = Skerry.colors.amber, size = 12.sp, weight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp),
    )
    GhostButton(
        stringResource(Res.string.settings_about_update_open),
        onClick = { runCatching { uriHandler.openUri(update.releaseUrl) } },
        modifier = Modifier.padding(top = 8.dp),
    )
}
