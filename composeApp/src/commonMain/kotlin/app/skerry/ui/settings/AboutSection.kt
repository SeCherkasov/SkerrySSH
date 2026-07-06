package app.skerry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.app.AppVersion
import app.skerry.ui.design.BrandMark
import app.skerry.ui.design.D
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_about_documentation
import app.skerry.ui.generated.resources.settings_about_footer
import app.skerry.ui.generated.resources.settings_about_licenses
import app.skerry.ui.generated.resources.settings_about_tagline
import app.skerry.ui.generated.resources.settings_about_version
import app.skerry.ui.generated.resources.settings_about_whats_new
import org.jetbrains.compose.resources.stringResource

// About section: logo, version ([AppVersion]), links.

@Composable
internal fun AboutSection() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 20.dp).size(72.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A141B)), contentAlignment = Alignment.Center) {
            BrandMark(size = 72.dp)
        }
        Txt("Skerry", color = D.text, size = 20.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Txt(stringResource(Res.string.settings_about_version, AppVersion.VERSION, AppVersion.BUILD), color = D.dim, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Txt(stringResource(Res.string.settings_about_tagline), color = D.dim, size = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(Res.string.settings_about_whats_new), onClick = {})
            GhostButton(stringResource(Res.string.settings_about_documentation), onClick = {})
            GhostButton(stringResource(Res.string.settings_about_licenses), onClick = {})
        }
        Txt(stringResource(Res.string.settings_about_footer), color = D.faint, size = 11.sp, modifier = Modifier.padding(top = 20.dp))
    }
}
