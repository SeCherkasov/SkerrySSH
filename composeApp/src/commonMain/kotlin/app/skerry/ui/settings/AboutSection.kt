package app.skerry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.AppVersion
import app.skerry.ui.app.LocalUpdates
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.BrandPlate
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_about_check_updates
import app.skerry.ui.generated.resources.settings_about_check_updates_desc
import app.skerry.ui.generated.resources.settings_about_documentation
import app.skerry.ui.generated.resources.settings_about_footer
import app.skerry.ui.generated.resources.settings_about_licenses
import app.skerry.ui.generated.resources.settings_about_tagline
import app.skerry.ui.generated.resources.settings_about_version
import app.skerry.ui.generated.resources.settings_about_whats_new
import app.skerry.ui.update.UpdateAvailableBlock
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

// About section: logo, version ([AppVersion]), links.

@Composable
internal fun AboutSection() {
    val updates = LocalUpdates.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandPlate(size = 72.dp, corner = 16.dp, modifier = Modifier.padding(top = 20.dp))
        Txt("Skerry", color = Skerry.colors.text, size = 20.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Txt(stringResource(Res.string.settings_about_version, AppVersion.VERSION), color = Skerry.colors.dim, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
        UpdateAvailableBlock()
        Txt(stringResource(Res.string.settings_about_tagline), color = Skerry.colors.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp))
        AboutLinkButtons(Modifier.padding(top = 18.dp))
        if (updates != null) {
            SettingToggleRow(
                stringResource(Res.string.settings_about_check_updates),
                stringResource(Res.string.settings_about_check_updates_desc),
                updates.settings.checkForUpdates,
            ) { updates.setCheckForUpdates(!updates.settings.checkForUpdates) }
        }
        Txt(stringResource(Res.string.settings_about_footer), color = Skerry.colors.faint, size = 11.sp, modifier = Modifier.padding(top = 20.dp))
    }
}

// Project pages opened from the About buttons.
internal object AboutLinks {
    const val DOCUMENTATION = "https://github.com/SeCherkasov/SkerrySSH#readme"
    const val LICENSES = "https://github.com/SeCherkasov/SkerrySSH/blob/main/LICENSE"

    /** Release notes of the running version: the GitHub release tagged `v<version>`. */
    fun whatsNew(version: String) = "https://github.com/SeCherkasov/SkerrySSH/releases/tag/v$version"
}

/** What's new / Documentation / Licenses row, shared by the desktop and mobile About screens. */
@Composable
internal fun AboutLinkButtons(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    fun open(url: String): () -> Unit = { runCatching { uriHandler.openUri(url) } }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GhostButton(stringResource(Res.string.settings_about_whats_new), onClick = open(AboutLinks.whatsNew(AppVersion.VERSION)))
        GhostButton(stringResource(Res.string.settings_about_documentation), onClick = open(AboutLinks.DOCUMENTATION))
        GhostButton(stringResource(Res.string.settings_about_licenses), onClick = open(AboutLinks.LICENSES))
    }
}
