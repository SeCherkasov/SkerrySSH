package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_trash_subtitle
import app.skerry.ui.generated.resources.settings_trash_title
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.TrashList
import app.skerry.ui.vault.rememberTrashController
import org.jetbrains.compose.resources.stringResource

/**
 * More → Trash push screen (parity with the desktop [app.skerry.ui.settings.TrashSection]): the
 * same list over the same vault trash, so a record deleted on the desktop is restorable here.
 */
@Composable
fun MobileTrashScreen(state: MobileDesignState) {
    val controller = rememberTrashController()
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.settings_trash_title), onBack = state::pop)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Txt(
                    stringResource(Res.string.settings_trash_subtitle),
                    color = Skerry.colors.dim,
                    size = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                TrashList(controller)
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}
