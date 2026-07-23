package app.skerry.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_update_status
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * Passive update notice in the desktop status bar: appears only while
 * [UpdateNoticeController.notice] has an undismissed newer release. Clicking the label opens the
 * GitHub release page (all platform artifacts live there — the app never self-updates); the small
 * cross dismisses this version until a newer one shows up.
 */
@Composable
fun UpdateStatusItem() {
    val updates = LocalUpdates.current ?: return
    val update = updates.notice ?: return
    val uriHandler = LocalUriHandler.current
    val mono = LocalFonts.current.mono
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { runCatching { uriHandler.openUri(update.releaseUrl) } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Sym("arrow_circle_up", size = 13.sp, color = Skerry.colors.cyanBright)
            Txt(stringResource(Res.string.settings_update_status, update.versionLabel), color = Skerry.colors.cyanBright, size = 10.5.sp, font = mono)
        }
        Sym(
            "close", size = 12.sp, color = Skerry.colors.faint,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = updates::dismiss,
            ),
        )
    }
}
