package app.skerry.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.AppVersion
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.app.MobileDesignState
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.BrandPlate
import app.skerry.ui.design.Toggle
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.more_about
import app.skerry.ui.generated.resources.settings_about_check_updates
import app.skerry.ui.generated.resources.settings_about_check_updates_desc
import app.skerry.ui.generated.resources.settings_about_footer
import app.skerry.ui.generated.resources.settings_about_tagline
import app.skerry.ui.generated.resources.settings_about_version
import app.skerry.ui.settings.AboutLinkButtons
import app.skerry.ui.update.UpdateAvailableBlock
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * More → About push screen (parity with the desktop [app.skerry.ui.settings.AboutSection]): brand,
 * version, the update notice with a link to the GitHub release page, the project link buttons, and
 * the "check for updates" toggle. Without a live controller (preview) the update rows are simply
 * absent.
 */
@Composable
fun MobileAboutScreen(state: MobileDesignState) {
    val updates = LocalUpdates.current
    Box(Modifier.fillMaxSize().background(Skerry.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            MobilePushHeader(stringResource(Res.string.more_about), onBack = state::pop)
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandPlate(size = 72.dp, corner = 16.dp, modifier = Modifier.padding(top = 26.dp))
                Txt("Skerry", color = Skerry.colors.text, size = 20.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Txt(stringResource(Res.string.settings_about_version, AppVersion.VERSION), color = Skerry.colors.dim, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
                UpdateAvailableBlock()
                Txt(stringResource(Res.string.settings_about_tagline), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
                AboutLinkButtons(Modifier.padding(top = 18.dp))
                if (updates != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Txt(stringResource(Res.string.settings_about_check_updates), color = Skerry.colors.text, size = 14.5.sp)
                            Txt(stringResource(Res.string.settings_about_check_updates_desc), color = Skerry.colors.dim, size = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Toggle(
                            on = updates.settings.checkForUpdates,
                            onToggle = { updates.setCheckForUpdates(!updates.settings.checkForUpdates) },
                        )
                    }
                }
                Txt(stringResource(Res.string.settings_about_footer), color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(top = 24.dp, bottom = 26.dp))
            }
        }
    }
}
